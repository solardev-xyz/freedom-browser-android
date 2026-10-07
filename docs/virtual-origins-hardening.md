# Virtual-origin hardening: service workers, cookies, CORS

Companion to `docs/virtual-origins.md` (issue #5 scope).

## Service workers

`ServiceWorkerInterception` wires
`ServiceWorkerControllerCompat.setServiceWorkerClient` to the **same**
`interceptVirtualRequest` function the per-WebView client uses — one
shared resolver/fetch path, no fork. The SW client is process-global,
which is fine: virtual-origin traffic is identified by host pattern,
not by tab.

Feature gate: installed only when
`WebViewFeature.isFeatureSupported(SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)`
holds. **Degradation decision:** on WebViews without the feature we
install nothing and service workers on virtual origins are simply
unsupported (documented in the dapp compatibility contract) — we do not
script-inject a shim hiding `navigator.serviceWorker`, because a
partial emulation is harder for dapps to reason about than an honest
missing feature; offline-first apps keep their standard no-SW fallback.

## Cookies

The interceptor (the only network path for virtual origins) strips
`Cookie`/`Set-Cookie` in both directions — that shipped with the core
switch. The remaining channel is `document.cookie` writes, plus the
structural problem that all virtual origins share one registrable
domain until the PSL entry (issue #6) propagates into users' WebView:
a malicious root could set `Domain=.bzz.freedom.baby` cookies visible
to every other dweb site (cookie tossing).

`CookieHygiene` expires everything `CookieManager` reports under the
virtual suffixes (domain-scoped cookies — the tossing vector) and
under the exact origin being navigated to. It runs on every navigation
to a virtual origin plus every 60 s, off the UI thread. **It stays on
permanently as defense in depth even after the PSL entry lands.**

"Clear browsing data" already covers the new origins:
`removeAllCookies` / `WebStorage.deleteAllData` are origin-agnostic.

## CORS

### Between virtual origins (reads)

Policy: **`Access-Control-Allow-Origin: *`**, not reflect-origin.
Rationale: dweb content is public, and credentials never ride along on
this path (cookies are stripped in both directions), so reflecting the
requesting origin grants nothing `*` doesn't — it would only add a
per-response branch to get wrong. The interceptor answers `OPTIONS`
preflights between virtual origins locally (204, permissive methods,
requested headers echoed).

### To the node API (writes)

`WebResourceRequest` carries no request body, so intercepted origins
are GET/HEAD-only. Pages don't write through a Swarm node directly
(#358): `NodeApiGuard` answers `403` to every page request with a
method other than GET/HEAD to the gateway port on any host, and to the
external Swarm node set in Settings; pages publish with `window.swarm`,
which asks the user (see `docs/dapp-compatibility.md`, *The write
path*).

Status:

- **Preflights**: a CORS preflight is judged as the request it asks
  for. A read's preflight to an embedded gateway is answered by the
  interceptor (`GET, HEAD` only); a Swarm write's is refused with the
  write, and the app answers no other write's preflight (it goes to the
  node, whose own CORS policy decides).
- **Actual responses**: the node itself must send
  `Access-Control-Allow-Origin` for the browser to let the page read
  the result, and it doesn't: since #284 the app starts ant's gateway
  with an empty CORS allow-list (`SwarmNode.GATEWAY_CORS_ORIGINS`,
  handed to `ant_set_gateway_cors` before every `ant_start_gateway`,
  ant ≥ 0.5.49 / freedom-mobile-ffi v0.12.5), so it sends no CORS
  headers at all. ant matches exact origins only (plus `*` and `null`),
  and there's one virtual origin per content root, so no entry but `*`
  could cover `https://<label>.bzz.freedom.baby` pages — and `*` would
  let every page read the node's private API. So browser-`fetch` writes
  from dweb pages stay CORS-blocked on the response leg, as they were
  under the old pinned `null` (which never matched them either); the
  write itself reaches the node. Reads through virtual origins are
  unaffected: the interceptor fetches them natively and stamps its own
  `Access-Control-Allow-Origin: *`. A future way to allow the virtual
  suffixes would need ant to match origin suffixes. Same check applies
  to the freedom-ipfs API.
- **`Origin: null` was a leak, not just a mismatch** (#283, fixed by
  #284): up to ant 0.5.48 the FFI gateway pinned `CorsConfig::new(["null"])`,
  which let any opaque-origin context read the node's private API
  (`/wallet`, `/addresses`, `/stamps`, …) — and a CORS fetch carries
  `Origin: null` after any cross-origin redirect, so an ordinary page
  could too, through a redirector, from a public https site as well
  (Private Network Access doesn't block it in WebView 133).
  `NodeApiGuard` refuses every page read outside the dapp surface to
  the gateway port on a host that may be this device (a loopback or
  unspecified literal, `localhost`, or any name other than the external
  Swarm node set in Settings; writes on every host, #358), but WebView
  never asks the interceptor about such a redirect hop, and other
  browsers don't pass through it at all. With the empty allow-list the
  redirected fetch still reaches the node, but its answer carries no
  CORS header and the page's `fetch` rejects (checked in
  `docs/dapp-compatibility.md`). The app's own error page, a `file://`
  document whose origin is `null`, probes `/health` with a `no-cors`
  fetch for the same reason.
