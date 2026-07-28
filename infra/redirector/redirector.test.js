#!/usr/bin/env node
'use strict';

/**
 * `node redirector.test.js` — no framework, no dependencies.
 *
 * Every assertion is driven by `test-vectors.json`, the shared
 * cross-implementation vector file. The Kotlin side mirrors the same
 * file (VirtualOriginVectorsTest.kt), so a change that only lands on
 * one side turns red on the other.
 */

const assert = require('node:assert/strict');
const http = require('node:http');
const path = require('node:path');
const fs = require('node:fs');

const R = require('./redirector.js');

const vectors = JSON.parse(fs.readFileSync(path.join(__dirname, 'test-vectors.json'), 'utf8'));

// The vector file pins the gateways its `redirect` fields were written
// against, so the expectations don't move when a deployment overrides
// them via the environment.
const cfg = { baseDomain: vectors.baseDomain, ...vectors.gateways };

let passed = 0;
let failed = 0;

function test(name, fn) {
  try {
    fn();
    passed++;
  } catch (err) {
    failed++;
    process.stdout.write(`FAIL  ${name}\n      ${err.message.split('\n').join('\n      ')}\n`);
  }
}

// ---------------------------------------------------------------------
// Base36 label encoding (Swarm refs)
// ---------------------------------------------------------------------

for (const v of vectors.bzz) {
  test(`bzz: ${v.name} — labels encode`, () => {
    const chunks = v.ref.match(/.{64}/g);
    assert.deepEqual(chunks.map(R.base36EncodeHexChunk), v.labels);
    assert.ok(v.labels.every((l) => l.length <= 63), 'label exceeds the 63-char DNS limit');
  });

  test(`bzz: ${v.name} — host decodes back to the ref`, () => {
    assert.deepEqual(R.parseHost(v.host, cfg), { kind: 'bzz', ref: v.ref });
  });

  test(`bzz: ${v.name} — redirect + display`, () => {
    assert.equal(R.redirectFor(v.host, v.path, cfg), v.redirect);
    assert.equal(R.displayUrlFor(v.host, v.path, cfg), v.display);
  });
}

test('bzz: base36 round-trips 500 random refs', () => {
  const crypto = require('node:crypto');
  for (let i = 0; i < 500; i++) {
    const ref = crypto.randomBytes(32).toString('hex');
    const label = R.base36EncodeHexChunk(ref);
    assert.ok(label.length <= 63, `label too long: ${label}`);
    assert.equal(R.base36DecodeToHexChunk(label), ref);
  }
});

// ---------------------------------------------------------------------
// IPFS CIDs
// ---------------------------------------------------------------------

for (const v of vectors.ipfs) {
  if (v.cidV0) {
    test(`ipfs: ${v.name} — CIDv0 → base36 CIDv1`, () => {
      assert.equal(R.cidV0ToBase36(v.cidV0), v.cid);
    });
  }
  test(`ipfs: ${v.name} — host decodes, redirects, displays`, () => {
    assert.deepEqual(R.parseHost(v.host, cfg), { kind: 'ipfs', cid: v.cid });
    assert.equal(R.redirectFor(v.host, v.path, cfg), v.redirect);
    assert.equal(R.displayUrlFor(v.host, v.path, cfg), v.display);
  });
}

// ---------------------------------------------------------------------
// IPNS
// ---------------------------------------------------------------------

for (const v of vectors.ipnsKeys) {
  test(`ipns key: ${v.name} — PeerID → base36 libp2p-key`, () => {
    assert.equal(R.peerIdToBase36(v.peerId), v.key);
  });
  test(`ipns key: ${v.name} — host decodes, redirects, displays`, () => {
    assert.deepEqual(R.parseHost(v.host, cfg), { kind: 'ipnsKey', key: v.key });
    assert.equal(R.redirectFor(v.host, v.path, cfg), v.redirect);
    assert.equal(R.displayUrlFor(v.host, v.path, cfg), v.display);
  });
}

for (const v of vectors.ipnsNames) {
  test(`ipns name: ${v.name} — escape/unescape`, () => {
    assert.equal(R.escapeName(v.dnslink), v.label);
    assert.equal(R.unescapeName(v.label), v.dnslink);
  });
  test(`ipns name: ${v.name} — host decodes, redirects, displays`, () => {
    assert.deepEqual(R.parseHost(v.host, cfg), { kind: 'ipnsName', name: v.dnslink });
    assert.equal(R.redirectFor(v.host, v.path, cfg), v.redirect);
    assert.equal(R.displayUrlFor(v.host, v.path, cfg), v.display);
  });
}

// ---------------------------------------------------------------------
// ENS
// ---------------------------------------------------------------------

for (const v of vectors.ens) {
  test(`ens: ${v.name} — escape/unescape`, () => {
    assert.equal(R.escapeName(v.ensName), v.label);
    assert.equal(R.unescapeName(v.label), v.ensName);
  });
  test(`ens: ${v.name} — host decodes, redirects, displays`, () => {
    assert.deepEqual(R.parseHost(v.host, cfg), { kind: 'ens', name: v.ensName });
    assert.equal(R.redirectFor(v.host, v.path, cfg), v.redirect);
    assert.equal(R.displayUrlFor(v.host, v.path, cfg), v.display);
  });
}

// ---------------------------------------------------------------------
// Name escaping edge cases
// ---------------------------------------------------------------------

for (const v of vectors.escaping) {
  test(`escaping: ${v.name} ↔ ${v.label}`, () => {
    assert.equal(R.escapeName(v.name), v.label);
    assert.equal(R.unescapeName(v.label), v.name);
  });
}

// ---------------------------------------------------------------------
// Rejections
// ---------------------------------------------------------------------

for (const v of vectors.rejects) {
  test(`reject: ${v.host} — ${v.why}`, () => {
    assert.equal(R.parseHost(v.host, cfg), null);
    assert.equal(R.redirectFor(v.host, '/', cfg), null);
  });
}

// ---------------------------------------------------------------------
// Hostnames are case-folded in the wild — decode must not care
// ---------------------------------------------------------------------

test('host matching is case-insensitive', () => {
  const v = vectors.bzz[0];
  assert.equal(R.redirectFor(v.host.toUpperCase(), v.path, cfg), v.redirect);
});

// ---------------------------------------------------------------------
// The actual HTTP surface
// ---------------------------------------------------------------------

function request(server, options) {
  const { port } = server.address();
  return new Promise((resolve, reject) => {
    const req = http.request({ port, host: '127.0.0.1', ...options }, (res) => {
      res.resume();
      res.on('end', () => resolve(res));
    });
    req.on('error', reject);
    req.end();
  });
}

async function httpTests() {
  const server = R.createServer(cfg);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const v = vectors.bzz[0];

    let res = await request(server, { path: v.path, headers: { Host: v.host } });
    test('http: virtual host 301s to the gateway', () => {
      assert.equal(res.statusCode, 301);
      assert.equal(res.headers.location, v.redirect);
    });

    // Behind a TLS terminator the Host header is the proxy's; the
    // original arrives in X-Forwarded-Host.
    res = await request(server, {
      path: v.path,
      headers: { Host: 'proxy.internal:8080', 'X-Forwarded-Host': `${v.host}:443` },
    });
    test('http: honors X-Forwarded-Host (and strips the port)', () => {
      assert.equal(res.statusCode, 301);
      assert.equal(res.headers.location, v.redirect);
    });

    res = await request(server, { path: '/', headers: { Host: 'example.com' } });
    test('http: unknown host 404s', () => assert.equal(res.statusCode, 404));

    res = await request(server, { method: 'POST', path: '/', headers: { Host: v.host } });
    test('http: non-GET/HEAD 405s', () => assert.equal(res.statusCode, 405));
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

httpTests().then(() => {
  process.stdout.write(`\n${passed} passed, ${failed} failed\n`);
  process.exit(failed === 0 ? 0 : 1);
});
