# Dapp compatibility contract — Freedom Browser (Android)

The Android analog of the IPFS subdomain-gateway spec: what a dweb site
(Swarm, IPFS, IPNS, ENS) can rely on when it runs inside Freedom
Browser for Android. Every guarantee cites the instrumented test that
proves it (`app/src/androidTest/...`); the suite and this document ship
together and must stay in sync — **a guarantee without a test doesn't
belong here, and vice versa**.

Desktop freedom-browser gets the same model from its privileged
Electron schemes; content built against this contract is portable both
ways.

## Canonical origin form

Each content root is served from its own https origin:

```
https://<label(s)>.bzz.freedom.baby    Swarm reference
https://<label>.ipfs.freedom.baby      IPFS CID
https://<label>.ipns.freedom.baby      IPNS key / DNSLink name
https://<label>.ens.freedom.baby       ENS name (name-derived origin)
```

These hostnames never touch DNS or TLS inside the app — the request
interceptor answers first. Label encoding (source of truth:
`VirtualOrigin.kt`; mirrored by the shared vectors in
`infra/redirector/test-vectors.json`):

- **Swarm refs**: base36 (multibase leading-zero convention) of each
  64-hex chunk; 128-hex encrypted refs use two dot-separated labels,
  most-significant first.
- **IPFS**: lowercase CIDv1 verbatim; CIDv0 (`Qm…`) and base58 CIDv1
  are converted to base36 CIDv1 (hostnames are case-folded — raw
  CIDv0 would corrupt).
- **IPNS**: base58 PeerIDs become base36 libp2p-key CIDv1; DNSLink
  names are dot-escaped.
- **ENS / DNSLink escaping**: `-` → `--`, then `.` → `-`
  (`foo-bar.eth` → `foo--bar-eth`).

## Guaranteed

| Guarantee | Proven by |
|---|---|
| Relative subresource URLs resolve under the content root | `VirtualOriginContractTest.relativeAndAbsoluteRootSubresourcesResolveOnAVirtualOrigin` |
| Absolute-root URLs (`/_next/static/…`-style) resolve under the content root — no rewrite heuristics involved | same test |
| Per-root storage isolation (localStorage/IndexedDB invisible across roots) | `VirtualOriginContractTest.storageWrittenUnderRootAIsInvisibleUnderRootB` |
| ENS sites keep storage across contenthash updates (origin derives from the *name*) | `VirtualOriginContractTest.ensSiteKeepsStorageAcrossAContenthashUpdate` |
| Same-origin `fetch()` / XHR works | `VirtualOriginContractTest.sameOriginFetchWorks` |
| Cross-root reads succeed (CORS: `Access-Control-Allow-Origin: *`, preflights answered locally) | `VirtualOriginContractTest.crossRootFetchSucceedsUnderThePermissiveCorsPolicy` |
| Secure context (https origin — crypto.subtle, SW eligibility, etc.) | implied by every test running on `https://…` origins |
| Media `Range` requests get real `206` slices (seek without re-fetch) | `VirtualOriginContractTest.rangeRequestsGetA206Slice` |
| `bzz://` / `ipfs://` / `ipns://` **subresource** links inside pages load | `VirtualOriginContractTest.bzzSchemeImgSubresourceLoads` |
| Node not running → clean error, fast (no hanging load) | `VirtualOriginContractTest.nodeStoppedYieldsACleanSynthesized502` |
| Unknown/bad hash → content-not-found with a working retry target | `VirtualOriginContractTest.badHashYieldsContentNotFoundWithAWorkingRetryTarget` |
| Cookie tossing across roots is neutralized (pre-PSL sweep, kept as defense in depth) | `VirtualOriginContractTest.tossedDomainCookieFromRootAIsNotVisibleUnderRootBAfterSweep` |
| Service workers register, cache, and serve offline (where the WebView supports SW interception) | `ServiceWorkerContractTest.serviceWorkerRegistersCachesAndServesOffline` |

## The write path

Intercepted origins are **GET/HEAD-only** — `WebResourceRequest`
exposes no request body. For uploads/POSTs, talk to the node API
origin directly:

```js
await fetch('http://127.0.0.1:1633/bzz', { method: 'POST', body, headers })
```

This is sanctioned and works from https pages: Chromium treats
`http://127.0.0.1` as potentially trustworthy (no mixed-content
block). CORS preflights to the node origin are answered by the app;
response-side CORS headers are the node's job (status tracked in
`docs/virtual-origins-hardening.md`).

## Explicitly unsupported

- **Literal `fetch("bzz://…")` from page JS** — Chromium rejects
  CORS-mode fetches to non-http(s) schemes before interception can
  run. Use relative URLs or the virtual-origin https form. (Scheme
  URLs in *markup* — `<img src="bzz://…">` — do work, see above.)
- **`location.protocol` for transport detection** — pages see
  `https:`, never `bzz:`. Recommended alternative: match
  `location.hostname` against
  `/\.(bzz|ipfs|ipns|ens)\.freedom\.baby$/` to detect the namespace,
  or simply build transport-agnostic sites (relative URLs everywhere).
- **Service workers on WebViews lacking
  `SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST`** — nothing is installed
  and SW fetches would bypass the content resolver; ship a no-SW
  fallback path (the suite feature-gates the same way).

## Shared links and App Links

`window.location.href` on a virtual origin is a real URL under a
domain Freedom controls. A copied/shared link:

- opens in **any** browser via the public redirector (301 to a public
  gateway — same label decoding, see `infra/redirector/`);
- opens **in-app at the right content** on devices with Freedom
  installed, via Android App Links (`autoVerify` intent filters for
  the four suffixes + `assetlinks.json` on the base domains — a
  universal-link capability desktop's `bzz://` URLs can't offer).

## Fixtures & how the suite runs

The committed test dapp (`app/src/androidTest/assets/testdapp/`) is
served by a loopback fixture gateway bound to the embedded node's own
address (`127.0.0.1:1633`), so the production interceptor path is
exercised with no external network and no p2p — the suite is hermetic
and CI-runnable. Uploading the same dapp to the real embedded node
(Swarm + IPFS) and loading it end-to-end in the `freedom` AVD is the
manual acceptance pass for releases.
