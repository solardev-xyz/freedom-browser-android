#!/usr/bin/env node
'use strict';

/**
 * Redirector for the virtual origins the app loads dweb content from.
 *
 * The app serves every content root from its own synthetic https origin
 * (`https://<label(s)>.{bzz,ipfs,ipns,ens}.freedom.baby/…`, see
 * `app/src/main/java/baby/freedom/mobile/browser/VirtualOrigin.kt`).
 * Those hosts never hit the network inside the app — the WebView's
 * request interceptor answers first — but pages copy
 * `window.location.href` into share buttons, so the synthetic URL
 * escapes into the wild. Because we own the domain, that leak becomes a
 * feature: this service decodes the label back to the content id and
 * 301s to a public gateway, so a shared link works in any browser (and,
 * on a device with the app installed, App Links opens it in the app
 * instead — see `infra/applinks/`).
 *
 * The label encodings below are a faithful port of VirtualOrigin.kt.
 * The two implementations are pinned to the same cross-implementation
 * vectors in `test-vectors.json` (mirrored literally by
 * `app/src/test/java/baby/freedom/mobile/browser/VirtualOriginVectorsTest.kt`)
 * so they can't drift.
 *
 * No npm dependencies — plain `node:http`, so the Docker image is
 * `node:22-alpine` plus this one file.
 */

const http = require('node:http');

// ---------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------

/**
 * Gateway bases are env-configurable so a deployment can point at its
 * own gateways (or a paid Swarm gateway) without a code change. The
 * defaults are the public ones and are what `test-vectors.json` pins.
 */
function configFromEnv(env = process.env) {
  return {
    baseDomain: env.BASE_DOMAIN || 'freedom.baby',
    // Path-style Swarm gateway: `<base>/bzz/<ref><path>`.
    swarmGateway: env.SWARM_GATEWAY || 'https://gateway.ethswarm.org',
    // Subdomain-style IPFS gateways: `https://<id>.ipfs|ipns.<host><path>`.
    // Subdomain (not path) style on purpose — it preserves the per-root
    // origin isolation that the whole virtual-origin design is about.
    ipfsGatewayHost: env.IPFS_GATEWAY_HOST || 'dweb.link',
    ipnsGatewayHost: env.IPNS_GATEWAY_HOST || 'dweb.link',
    // ENS gateway suffix appended to the name: `<name>.limo` (eth.limo).
    ensGatewaySuffix: env.ENS_GATEWAY_SUFFIX || 'limo',
    port: Number(env.PORT || 8080),
  };
}

const DEFAULT_CONFIG = configFromEnv();

const NAMESPACES = ['bzz', 'ipfs', 'ipns', 'ens'];
const BZZ_REF_CHUNK_HEX = 64;
const B36 = '0123456789abcdefghijklmnopqrstuvwxyz';
const LOWER_MULTIBASE = /^[a-z0-9]+$/;

// ---------------------------------------------------------------------
// Base36 (multibase leading-zero convention)
// ---------------------------------------------------------------------

/** Parse a lowercase base36 string as a BigInt, or `null` if it isn't one. */
function base36ToBigInt(s) {
  let n = 0n;
  for (const c of s) {
    const d = B36.indexOf(c);
    if (d < 0) return null;
    n = n * 36n + BigInt(d);
  }
  return n;
}

/**
 * base36 label → 64-hex chunk, or `null` if it doesn't decode.
 *
 * Multibase convention: leading zero *bytes* of the chunk encode as
 * leading `'0'` characters (base36 alone can't represent them), and the
 * remaining big-integer body is left-padded back out to 64 hex chars.
 */
function base36DecodeToHexChunk(label) {
  if (!label || !LOWER_MULTIBASE.test(label)) return null;
  let zeros = 0;
  while (zeros < label.length && label[zeros] === '0') zeros++;
  const body = label.slice(zeros);
  const n = body === '' ? 0n : base36ToBigInt(body);
  if (n === null) return null;
  const hex = n === 0n ? '' : n.toString(16);
  if (zeros * 2 + hex.length > BZZ_REF_CHUNK_HEX) return null;
  return '0'.repeat(BZZ_REF_CHUNK_HEX - hex.length) + hex;
}

/** 64-hex chunk → base36 label. The inverse of [base36DecodeToHexChunk]. */
function base36EncodeHexChunk(hexChunk) {
  let leadingZeroBytes = 0;
  let i = 0;
  while (i + 1 < hexChunk.length && hexChunk[i] === '0' && hexChunk[i + 1] === '0') {
    leadingZeroBytes++;
    i += 2;
  }
  const n = BigInt('0x' + hexChunk);
  const body = n === 0n ? '' : n.toString(36);
  return '0'.repeat(leadingZeroBytes) + body;
}

// ---------------------------------------------------------------------
// Base58 → base36 CIDv1
//
// The encode direction lives in the app (hostnames are case-folded, so
// case-sensitive `Qm…` CIDv0 / `12D3Koo…` PeerIDs are converted to
// lowercase base36 CIDv1 before they ever become a label). The
// redirector only ever sees the lowercase form — these two are ported
// anyway so `test-vectors.json` is checkable from both sides.
// ---------------------------------------------------------------------

const B58 = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';

/** Bitcoin-alphabet base58 decode, or `null` on a bad character. */
function base58Decode(s) {
  if (!s) return null;
  let n = 0n;
  for (const c of s) {
    const idx = B58.indexOf(c);
    if (idx < 0) return null;
    n = n * 58n + BigInt(idx);
  }
  let hex = n === 0n ? '' : n.toString(16);
  if (hex.length % 2 === 1) hex = '0' + hex;
  const body = [];
  for (let i = 0; i < hex.length; i += 2) body.push(parseInt(hex.slice(i, i + 2), 16));
  let zeros = 0;
  while (zeros < s.length && s[zeros] === '1') zeros++;
  return Uint8Array.from([...new Array(zeros).fill(0), ...body]);
}

/** Multibase base36 (`k` prefix) of [bytes]. */
function multibase36(bytes) {
  let zeros = 0;
  while (zeros < bytes.length && bytes[zeros] === 0) zeros++;
  let n = 0n;
  for (const b of bytes) n = (n << 8n) | BigInt(b);
  const body = n === 0n ? '' : n.toString(36);
  return 'k' + '0'.repeat(zeros) + body;
}

/** CIDv0 (`Qm…`) → the lowercase base36 CIDv1 the app uses as a label. */
function cidV0ToBase36(cid) {
  if (cid.length !== 46 || !cid.startsWith('Qm')) return null;
  const multihash = base58Decode(cid);
  // CIDv0 *is* a bare sha2-256 multihash (0x12 0x20 …).
  if (!multihash || multihash.length !== 34 || multihash[0] !== 0x12) return null;
  return multibase36(Uint8Array.from([0x01, 0x70, ...multihash]));
}

/** Base58 PeerID → the base36 libp2p-key CIDv1 the app uses as a label. */
function peerIdToBase36(peerId) {
  const multihash = base58Decode(peerId);
  if (!multihash) return null;
  return multibase36(Uint8Array.from([0x01, 0x72, ...multihash]));
}

// ---------------------------------------------------------------------
// Name escaping (ENS / DNSLink) — the IPFS subdomain-gateway
// convention ("inline DNSLink"): `-` → `--`, then `.` → `-`.
// ---------------------------------------------------------------------

function escapeName(name) {
  return name.split('-').join('--').split('.').join('-');
}

function unescapeName(label) {
  let out = '';
  let i = 0;
  while (i < label.length) {
    if (label[i] === '-') {
      if (label[i + 1] === '-') {
        out += '-';
        i += 2;
      } else {
        out += '.';
        i += 1;
      }
    } else {
      out += label[i];
      i += 1;
    }
  }
  return out;
}

// ---------------------------------------------------------------------
// Host → content root
// ---------------------------------------------------------------------

/**
 * Split a virtual hostname into its label(s) and namespace, or `null`
 * if it isn't one of ours.
 */
function splitHost(host, cfg = DEFAULT_CONFIG) {
  const h = String(host || '').toLowerCase();
  for (const ns of NAMESPACES) {
    const suffix = `.${ns}.${cfg.baseDomain}`;
    if (h.endsWith(suffix)) {
      const labels = h.slice(0, -suffix.length).split('.');
      if (labels.length === 0 || labels.some((l) => l === '')) return null;
      return { labels, ns };
    }
  }
  return null;
}

/**
 * Decode a virtual hostname back to its content root, or `null` for
 * hosts outside the virtual suffixes / labels that don't decode.
 *
 * Roots are `{ kind: 'bzz', ref }`, `{ kind: 'ipfs', cid }`,
 * `{ kind: 'ipnsKey', key }`, `{ kind: 'ipnsName', name }`,
 * `{ kind: 'ens', name }` — one per `ContentRoot` variant in Kotlin.
 */
function parseHost(host, cfg = DEFAULT_CONFIG) {
  const split = splitHost(host, cfg);
  if (!split) return null;
  const { labels, ns } = split;
  switch (ns) {
    case 'bzz': {
      if (labels.length < 1 || labels.length > 2) return null;
      let hex = '';
      for (const label of labels) {
        const chunk = base36DecodeToHexChunk(label);
        if (chunk === null) return null;
        hex += chunk;
      }
      return { kind: 'bzz', ref: hex };
    }
    case 'ipfs': {
      if (labels.length !== 1) return null;
      const cid = labels[0];
      // Already a lowercase CIDv1 multibase string — the app never puts
      // a case-sensitive CIDv0 in a hostname, so nothing to convert.
      if (!LOWER_MULTIBASE.test(cid)) return null;
      return { kind: 'ipfs', cid };
    }
    case 'ipns': {
      if (labels.length !== 1) return null;
      const label = labels[0];
      // A hyphen means the label is a dot-escaped DNSLink name; a bare
      // base36 (`k…`) / base32 (`b…`) string of key length is a key.
      if (label.includes('-')) return { kind: 'ipnsName', name: unescapeName(label) };
      if ((label.startsWith('k') || label.startsWith('b')) && label.length >= 30) {
        return { kind: 'ipnsKey', key: label };
      }
      return { kind: 'ipnsName', name: label };
    }
    case 'ens': {
      if (labels.length !== 1) return null;
      return { kind: 'ens', name: unescapeName(labels[0]) };
    }
    default:
      return null;
  }
}

// ---------------------------------------------------------------------
// Content root → public gateway URL
// ---------------------------------------------------------------------

/**
 * The public gateway URL that serves [root] with [pathAndQuery]
 * appended (already starting with `/`).
 *
 * DNSLink names go back out in their *escaped* form: dweb.link's
 * subdomain gateway uses the very same inline-DNSLink convention, and a
 * raw `en.wikipedia-on-ipfs.org` can't be one DNS label anyway. The
 * decode/re-escape round trip is still worth doing — it validates the
 * label instead of blindly forwarding whatever arrived.
 */
function gatewayUrlFor(root, pathAndQuery, cfg = DEFAULT_CONFIG) {
  const tail = pathAndQuery || '/';
  switch (root.kind) {
    case 'bzz':
      return `${cfg.swarmGateway}/bzz/${root.ref}${tail}`;
    case 'ipfs':
      return `https://${root.cid}.ipfs.${cfg.ipfsGatewayHost}${tail}`;
    case 'ipnsKey':
      return `https://${root.key}.ipns.${cfg.ipnsGatewayHost}${tail}`;
    case 'ipnsName':
      return `https://${escapeName(root.name)}.ipns.${cfg.ipnsGatewayHost}${tail}`;
    case 'ens':
      return `https://${root.name}.${cfg.ensGatewaySuffix}${tail}`;
    default:
      return null;
  }
}

/** Virtual host + path → the gateway URL to 301 to, or `null`. */
function redirectFor(host, pathAndQuery, cfg = DEFAULT_CONFIG) {
  const root = parseHost(host, cfg);
  if (!root) return null;
  return gatewayUrlFor(root, pathAndQuery, cfg);
}

/** The display URL the app would show for this virtual host + path. */
function displayUrlFor(host, pathAndQuery, cfg = DEFAULT_CONFIG) {
  const root = parseHost(host, cfg);
  if (!root) return null;
  const tail = pathAndQuery === '/' ? '' : pathAndQuery || '';
  switch (root.kind) {
    case 'bzz':
      return `bzz://${root.ref}${tail}`;
    case 'ipfs':
      return `ipfs://${root.cid}${tail}`;
    case 'ipnsKey':
      return `ipns://${root.key}${tail}`;
    case 'ipnsName':
      return `ipns://${root.name}${tail}`;
    case 'ens':
      return `${root.name}${tail}`;
    default:
      return null;
  }
}

// ---------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------

/** Request host, minus any port. Honors `X-Forwarded-Host` from a TLS proxy. */
function hostOfRequest(req) {
  const raw = req.headers['x-forwarded-host'] || req.headers.host || '';
  const first = String(raw).split(',')[0].trim();
  // IPv6 literals are bracketed; virtual hosts never are, so a plain
  // rsplit on ':' is enough and keeps this dependency-free.
  return first.replace(/:\d+$/, '').toLowerCase();
}

function handle(req, res, cfg = DEFAULT_CONFIG) {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8', Allow: 'GET, HEAD' });
    res.end('Method not allowed\n');
    return;
  }
  const host = hostOfRequest(req);
  const target = redirectFor(host, req.url || '/', cfg);
  if (!target) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(
      'Not a Freedom virtual origin.\n\n' +
        'This host serves share links from the Freedom browser:\n' +
        `  https://<id>.{${NAMESPACES.join(',')}}.${cfg.baseDomain}/...\n`,
    );
    return;
  }
  // 301, not 302: the mapping is content-addressed and permanent, and a
  // cached redirect keeps this service off the hot path.
  res.writeHead(301, {
    Location: target,
    'Cache-Control': 'public, max-age=86400',
    'Content-Type': 'text/plain; charset=utf-8',
  });
  res.end(`Redirecting to ${target}\n`);
}

function createServer(cfg = DEFAULT_CONFIG) {
  return http.createServer((req, res) => handle(req, res, cfg));
}

if (require.main === module) {
  const cfg = DEFAULT_CONFIG;
  createServer(cfg).listen(cfg.port, () => {
    process.stdout.write(`redirector listening on :${cfg.port} for *.${cfg.baseDomain}\n`);
  });
}

module.exports = {
  configFromEnv,
  DEFAULT_CONFIG,
  base36ToBigInt,
  base36EncodeHexChunk,
  base36DecodeToHexChunk,
  base58Decode,
  multibase36,
  cidV0ToBase36,
  peerIdToBase36,
  escapeName,
  unescapeName,
  splitHost,
  parseHost,
  gatewayUrlFor,
  redirectFor,
  displayUrlFor,
  hostOfRequest,
  handle,
  createServer,
};
