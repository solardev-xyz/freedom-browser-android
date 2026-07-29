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
are GET/HEAD-only; the sanctioned write path is calling
`http://127.0.0.1:1633` (ant) / the freedom-ipfs API directly from
page JS. Mixed content is fine — Chromium treats `http://127.0.0.1` as
potentially trustworthy.

Status:

- **Preflights**: answered by the interceptor (bodyless `OPTIONS` to a
  local gateway origin never needs to reach the node), so the
  preflight leg works today regardless of node configuration.
- **Actual responses**: the node itself must send
  `Access-Control-Allow-Origin` for the browser to let the page read
  the result. ant's FFI gateway currently pins
  `CorsConfig::new(["null"])` (`ant/crates/ant-ffi/src/gateway.rs`) —
  correct for desktop Electron's opaque origins, but virtual-origin
  pages send `Origin: https://<label>.bzz.freedom.baby`, which `null`
  does not match. **Follow-up (upstream, ant repo / freedom-mobile-ffi
  release):** extend the FFI gateway config to allow the virtual
  suffixes (`*.bzz.freedom.baby` etc., or `*` — the API is
  loopback-only) and bump the pinned FFI version in `release.yml`.
  Same check applies to the freedom-ipfs API. Until then, reads work
  everywhere; browser-`fetch` writes from dweb pages will be
  CORS-blocked on the response leg.
