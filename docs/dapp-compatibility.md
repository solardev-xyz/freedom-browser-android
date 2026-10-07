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
| Back / Forward to an ENS site (or an ENS iframe) re-resolve the name and serve its current content, never the first visit's answer; a name that no longer resolves is refused in place (forward history kept); with the RPC unreachable the last answer is served | `VirtualOriginContractTest.backAndForwardReResolveAnEnsNameInsteadOfRestoringTheFirstAnswer`, `VirtualOriginContractTest.forwardToAnEnsNameThatNoLongerResolvesIsRefused`, `VirtualOriginContractTest.aRefusedBackKeepsTheForwardEntry`, `VirtualOriginContractTest.backWithTheRpcDownServesTheLastAnswer`, `VirtualOriginContractTest.anEnsIframeReChecksTheName`, `VirtualOriginContractTest.aNavigationThatNeverCommitsKeepsThePagesRoot`, `VirtualOriginContractTest.aNavigationStoppedDuringItsReCheckKeepsThePagesRoot` |
| Same-origin `fetch()` / XHR works | `VirtualOriginContractTest.sameOriginFetchWorks` |
| Cross-root reads succeed (CORS: `Access-Control-Allow-Origin: *`, preflights answered locally) | `VirtualOriginContractTest.crossRootFetchSucceedsUnderThePermissiveCorsPolicy` |
| A `fetch()` / subresource body may pause for 15 s+ (a node short on peer credit) without being aborted and fetched again: 30 s to the headers, then up to 120 s of silence per read, for a few requests at a time | `VirtualOriginContractTest.aSubresourceBodyThatPausesFor15sIsNotAborted` |
| However many dweb subresources stall, other requests (other tabs, plain https sites) aren't held up longer than before: only a few waits at a time go past 10 s | `VirtualOriginContractTest.manyStalledSubresourcesDontHoldUpOtherRequests` |
| A subresource 404 reaches the page at once, not after the retry backoff (transient 5xx are still retried; a main-frame 404 still is too) | `VirtualOriginContractTest.aSubresource404IsPassedThroughAtOnce` |
| Secure context (https origin — crypto.subtle, SW eligibility, etc.) | implied by every test running on `https://…` origins |
| Media `Range` requests get real `206` slices (seek without re-fetch) | `VirtualOriginContractTest.rangeRequestsGetA206Slice` |
| `bzz://` / `ipfs://` / `ipns://` **subresource** links inside pages load | `VirtualOriginContractTest.bzzSchemeImgSubresourceLoads` |
| Node not running → clean error, fast (no hanging load) | `VirtualOriginContractTest.nodeStoppedYieldsACleanSynthesized502` |
| Unknown/bad hash → content-not-found with a working retry target | `VirtualOriginContractTest.badHashYieldsContentNotFoundWithAWorkingRetryTarget` |
| Cookie tossing across roots is neutralized (pre-PSL sweep, kept as defense in depth) | `VirtualOriginContractTest.tossedDomainCookieFromRootAIsNotVisibleUnderRootBAfterSweep` |
| Service workers register, cache, and serve offline (where the WebView supports SW interception) | `ServiceWorkerContractTest.serviceWorkerRegistersCachesAndServesOffline` |

## The write path

Intercepted origins are **GET/HEAD-only** — `WebResourceRequest`
exposes no request body. Pages publish with **`window.swarm`**, which
asks the user, then signs and uploads natively with the user's own
stamps and returns the result.

No page writes through a Swarm node directly (#358, as on desktop's
`ant-api-guard.js`). Every request a page makes to the gateway port
with a method other than GET or HEAD — on any host: the embedded node,
a Bee node on the LAN (`http://192.168.1.20:1633`), any name — and every
such request to the external Swarm node set in Settings (on its own
host and port — `http` and `https` on the default ports 80/443 count as
one, since one proxy server block often listens on both — under its
path: a node at `https://me.example/bee` is only `/bee` and what's under
it, the rest of `me.example` being some other site) is answered `403` by the app and never reaches the node:

```js
await fetch('http://127.0.0.1:1633/bzz', { method: 'POST', body, headers }) // 403
```

That includes the dapp surface (`/bzz`, `/bytes`, `/chunks`, `/soc`,
`/feeds`, `/pss`, `/gsoc`) as well as `/pins`, `/tags`, `/connect`,
`/grantee`, `/stewardship` and everything else. Before, any site could
upload, write feeds or send pss under a postage batch the user paid for
(a `no-cors` POST needs no preflight), and pin, tag or dial peers on a
LAN node. A CORS preflight is judged as the request it asks for: the app
answers a read's preflight to the embedded gateway (`GET, HEAD` only)
and refuses a write's. Page-supplied `Swarm-Postage-Batch-Id` and
`Swarm-Act*` headers are dropped from requests the interceptor forwards
to the gateway — reads included: a page can't have the node decrypt ACT
content shared with it (`Swarm-Act`, `Swarm-Act-Publisher`,
`Swarm-Act-History-Address` on a GET through a virtual origin), since
that would use the node's own key without asking the user.

Reads keep working: dweb pages load their content through the virtual
origins, a page can still read `/bzz`, `/bytes`, `/chunks`, `/soc`,
`/feeds`, `/pss`, `/gsoc` with GET (the node sends no CORS headers on
its answers, #284, so a page on another origin can't read the reply of
a CORS `fetch`; see `docs/virtual-origins-hardening.md`), and the
`/health` and `/readiness` probes stay open. Every other read a page
makes to the gateway port on any host that could be the device (any
name, since a name can resolve to loopback) is answered `403` too
(#114, #283, `NodeApiGuard`): that's bee's node API, which in light mode
signs and sends transactions from the user's funded node account with
no prompt (`/stamps…`, `/chequebook…`, `/stake…`, `/wallet…`,
`/transactions…`) and otherwise reads what the node knows about the
user (`/addresses`, `/wallet`, `/stamps`, `/chequebook`, `/balances`,
`/settlements`, `/peers`, `/topology`, `/node`, `/pins`, `/tags`, …).
It's an allowlist, so an endpoint a later ant adds stays closed. Buying
stamps, funding the chequebook and the node's details go through the
app.

A Bee node on another machine isn't the embedded node, which binds
`127.0.0.1` only. Its reads stay open to pages when the URL names it by
a non-loopback IP address (`http://192.168.1.20:1633/wallet`), or by
the host of the external Swarm node set in Settings. Any other name on
port 1633 (`http://nas:1633`) is refused, since the app can't tell it
from one that resolves to the device; the refusal says to use the IP
address or set it as the external node. Writes stay refused on every
host (#358).

The interceptor can't see every such request: a redirect a CORS fetch
follows after an earlier cross-origin hop, or a navigation's redirect,
is followed inside Chromium, and other apps reach the port directly. So
the node itself also refuses to broadcast any transaction (`ant_jni.c`'s
chain transport): an on-chain write that gets past the interceptor
fails at the node instead. An upload a page sneaks past that way (a form
POST that a redirector answers with a 307 to `/bzz`) does reach the
node, as it does from another browser on the device; that's a limit of
the interceptor, not something it allows. Nor can it see a reverse
proxy's own path mapping: one whose location for the external node has
no trailing slash (`location /bee` proxied to the node's root) also
hands the node `/beehive/…`, which the app takes to be another app on
that origin. Nor does a WebSocket
handshake ever reach the interceptor, and ant checks no `Origin` on an
upgrade: a page can still push chunks through
`ws://127.0.0.1:1633/chunks/stream`, each with a postage stamp it signed
for a batch of its own (it can't sign for the user's batches, whose
owner key only the node holds). Closing that needs ant to refuse browser
upgrades on that route. And the node lets no page read its answers:
up to ant 0.5.48 its gateway answered `Origin: null` — which a fetch
carries after a cross-origin redirect — with
`Access-Control-Allow-Origin: null`, so a page could read `/wallet` or
`/addresses` through a redirector (#283). Since #284 the app starts the
gateway with no CORS origins at all (`SwarmNode.GATEWAY_CORS_ORIGINS`),
so the redirected request still reaches the node but its answer carries
no CORS header, and the page's `fetch` rejects.

That held for public sites too, not just loopback or LAN pages:
Private Network Access doesn't stop the redirect in the shipped
WebView. Checked on the x86_64 emulator (WebView 133.0.6943.137) from
`https://example.com` itself, with the probe run in the page over
DevTools: a direct `fetch('http://127.0.0.1:1633/wallet')` gets the
app's 403, from the top-level page and from a sandboxed iframe alike.
With ant-ffi 0.5.47,
`fetch('https://httpbin.org/redirect-to?url=http://127.0.0.1:1633/wallet')`
returned `200` with the wallet JSON from both; with ant-ffi 0.5.49 it
rejects with `TypeError: Failed to fetch` (no `Access-Control-Allow-Origin`
header), for `/wallet` and `/addresses` alike.

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
- **Service-worker-controlled ENS sites and the name re-check (#99)** —
  WebView reports a SW's fetches with no tab attached, so they can't use
  a tab's per-page ENS pins: a SW's lazy chunks come from the name's
  latest answer this session (another tab's re-check can move them to a
  newer root), and a navigation the SW answers from Cache Storage
  never reaches the interceptor, so it serves what the SW cached, with
  no lookup. Only navigations the SW forwards to the network are
  re-checked.

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
