# Freedom — Swarm Browser for Android

A native Android browser that can load both regular `https://` sites and Swarm content via `bzz://` URLs, built on top of an embedded [bee-lite](https://github.com/ethersphere/bee) node. Inspired by [Solar-Punk-Ltd/swarm-mobile-android](https://github.com/Solar-Punk-Ltd/swarm-mobile-android).

- **Internal / package name:** `freedom-mobile`
- **Display name:** Freedom

## Decisions

| # | Decision | Choice |
|---|---|---|
| 1 | Platform | Android only (for now) |
| 2 | Browser scope | Full browser — handles `https://` **and** `bzz://` |
| 3 | Node mode | Ultra-light only (read-only, no wallet / uploads) |
| 4 | UI stack | Jetpack Compose + Material 3; WebView embedded via `AndroidView { WebView(ctx) }` |
| 5 | Language | Kotlin for both `app/` and `swarmnode/` (port `SwarmNode.java` → Kotlin) |
| 6 | Binaries | Build & run upstream `swarm-mobile-android` first to validate toolchain and produce a known-good `bee-lite.aar`, then scaffold Freedom |

## Architecture

```
┌─────────────────────────────────────────────┐
│  Browser UI (Compose + Material 3)          │
│  address bar • tabs • WebView               │
├─────────────────────────────────────────────┤
│  URL Resolver                               │
│   • bzz://<hash>/<path>  → local gateway    │
│   • https://...           → passthrough     │
├─────────────────────────────────────────────┤
│  Local Gateway (http://127.0.0.1:<port>)    │
│   served by bee-lite's built-in HTTP API    │
├─────────────────────────────────────────────┤
│  SwarmNode.kt  (Kotlin wrapper)             │
│   lifecycle • status • gatewayUrl           │
├─────────────────────────────────────────────┤
│  bee-lite.aar  (the actual Swarm node)      │
└─────────────────────────────────────────────┘
```

## Repo layout (target)

```
freedom-mobile/
├── PLAN.md                       # this file
├── app/                          # Compose browser UI
│   └── src/main/java/.../
│       ├── MainActivity.kt
│       ├── browser/              # tabs, addressbar, webview host
│       ├── resolver/             # bzz:// → http://127.0.0.1 rewriting
│       ├── data/                 # Room: history, bookmarks
│       └── settings/
├── swarmnode/                    # bee-lite wrapper (Kotlin)
│   ├── libs/                     # bee-lite.aar + jar (dropped in after Phase 0)
│   └── src/main/java/.../SwarmNode.kt
├── build.gradle(.kts), settings.gradle(.kts), gradle/
└── README.md
```

## Phases

### Phase 0 — Validate upstream  ✅
Prove the toolchain and the node binary work before we write any of our own code.

- [x] Clone `Solar-Punk-Ltd/swarm-mobile-android` and `Solar-Punk-Ltd/bee-lite-java` into `upstream/`
- [x] Build `mobile.aar` ourselves from pristine upstream Go sources via `cd upstream/bee-lite-java && make build`. See [`README.md § Rebuilding mobile.aar`](./README.md#rebuilding-mobileaar) for the full recipe, provenance, and reproducibility caveat.
- [x] `./gradlew :app:assembleDebug`, install on emulator (Pixel AVD, API 36, arm64)
- [x] App reaches `NodeStatus.Running` with 130+ connected peers, wallet address populated
- [x] Probe the local gateway — `GET /health`, `/readiness`, `/status` all respond; `/bzz/<hash>` serves the HTTP surface we'll target
- [ ] _Deferred:_ actual Swarm content download — requires a currently-pinned hash, which we'll revisit once we can upload our own (Light mode, Phase 4+)

**Outcome:** toolchain works end-to-end — we can produce `mobile.aar` from pristine upstream Go sources and the resulting binary is reusable as-is. Two upstream bugs are in consumer-side Java (`SwarmNode.java`), not inside the AAR — documented under "Known issues & workarounds" and filed upstream as [swarm-mobile-android#22](https://github.com/Solar-Punk-Ltd/swarm-mobile-android/issues/22) (listener-replay race) and [#23](https://github.com/Solar-Punk-Ltd/swarm-mobile-android/issues/23) (emulator `/dnsaddr/` bootstrap). Both are already ported into Freedom's Kotlin. The upstream clones were deleted from `./upstream/` once the AAR was built and the issues filed.

### Phase 1 — Scaffold Freedom  ✅
- [x] New Android project at repo root (Gradle 8.13, AGP 8.13.2, Kotlin 2.1.10, Compose BOM 2025.01.01, min SDK 30, target SDK 36)
- [x] Two modules: `:app` (Kotlin + Compose + Material 3) and `:swarmnode` (Kotlin wrapper)
- [x] `mobile.aar` (150 MB, built in Phase 0 via `bee-lite-java`'s `make build`) copied into `swarmnode/libs/`, consumed via `flatDir` + `api(group="", name="mobile", ext="aar")`
- [x] Ported `SwarmNode.java` → `SwarmNode.kt`:
  - `start()` / `stop()` / `dispose()` — all non-blocking, node starts on a coroutine
  - `val state: StateFlow<NodeInfo>` with `NodeStatus = Stopped | Starting | Running | Error` — new collectors always receive the current value by construction, no listener-replay bug
  - `const val GATEWAY_URL = "http://127.0.0.1:1633"` on the companion — single source of truth for the bee-lite HTTP gateway; the port is hard-coded in the AAR's `pkg/api` and not exposed via `MobileNodeOptions`, so a compile-time constant is honest. `SwarmResolver` and `BrowserScreen` reference it directly.
  - Applies the emulator DHT workaround (leaf multiaddrs) in Kotlin
- [x] `NodeService` — foreground service owning the node, binds a `LocalBinder` exposing the same `StateFlow`; notification reflects live status + peer count
- [x] `MainActivity` — Compose UI with a status pill, peer counter, and wallet card; a nested `StateFlow<StateFlow<NodeInfo>>` swaps in the live flow when the service connects
- [x] `./gradlew :app:assembleDebug` builds clean; APK installs; node reaches `Running` on emulator in ~25s with data dir warm, ramps to 127 peers in <60s; `curl http://127.0.0.1:1633/health` → `{"status":"ok"}`, `/status` → `beeMode=ultra-light`

**Outcome:** we own the node lifecycle end-to-end in Kotlin on top of the validated `mobile.aar`. Next: add a browser shell on top.

### Phase 2 — Browser shell  ✅
- [x] Single `MainActivity` hosting a Compose UI
- [x] Address bar with input, back / forward / reload, progress bar
- [x] Multi-tab model — `TabsState` owns a `SnapshotStateList<BrowserState>`; `BrowserWebViewHost` holds one live `WebView` per tab id as siblings in a shared `FrameLayout` (hidden tabs are `View.GONE` so they keep scroll position, JS state, form contents across switches). Tab switcher is a `ModalBottomSheet` with new/close/switch.
- [x] `WebView` embedded via `AndroidView { FrameLayout(ctx) }` hosting the per-tab WebViews, JS + DOM storage on
- [x] History + bookmarks via Room (Room 2.8.4 via KSP 2.1.10-1.0.31). `BrowsingRepository` is the single choke-point; history records the *displayed* URL (`bzz://…`, `ens://…`, or `https://…`) on `onPageFinished`, skipping `about:` / `data:` / `javascript:` / `blob:`. Bookmarks are keyed by URL (unique index) so the star toggle is a trivial upsert / delete.
- [x] Foreground service to keep the node alive with a controllable notification (done in Phase 1)
- [x] Library sheet — two-tab bottom sheet (History / Bookmarks) reachable from the top chrome; tapping a row closes the sheet and submits the URL into the active tab.

**Outcome:** Freedom is now a real multi-tab browser shell. Tabs preserve state across switches, history and bookmarks survive app restarts, ENS and bzz URLs are recorded in their canonical form.

### Phase 3 — Swarm resolution  ✅ (first pass)
- [x] `SwarmResolver` — `bzz://<hash>[/path]` ↔ `http://127.0.0.1:1633/bzz/<hash>[/path]`
- [x] `BrowserState.loadUrl` rewrites on the way into the WebView (pendingUrl = loadable form)
- [x] `WebViewClient.shouldOverrideUrlLoading` intercepts in-page `bzz://` links and rewrites them
- [x] `onPageStarted` / `onPageFinished` map the URL back so the address bar always shows the canonical `bzz://` form
- [x] End-to-end verified on the emulator: `bzz://ab77201f6541a9ceafb98a46c643273cfa397a87798273dd17feb2aa366ce2e6` resolves via the embedded node and renders a Leaflet OSM map with the "Swarm" attribution
- [ ] "via Swarm" badge / lock icon in the address bar when the current origin is the local gateway
- [ ] Gate `bzz://` navigation on `NodeStatus.Running`; show splash / queue otherwise
- [ ] Error page for unreachable hashes / node failures (currently falls through to the WebView's default)

**Exit criteria:** entering `bzz://<known-hash>` loads Swarm content inside the browser. ✅

### Phase 3b — ENS resolution ✅ (first pass)
- [x] `Keccak256.kt` — pure-Kotlin legacy Keccak (0x01 padding), matches reference vectors (empty / "eth" / "abc" / `namehash(vitalik.eth)`)
- [x] `EnsResolver.kt` — `resolve(bytes,bytes)` call to the Universal Resolver (`0xeEeEEEeE14…EeEe`), hand-rolled ABI encode/decode, contenthash parsing (Swarm `0xe40101fa011b20`, IPFS `0xe30170`, IPNS `0xe50172` with pure-Kotlin Base58)
- [x] Retries across 5 public RPC endpoints with sticky-preference, 15-minute in-memory cache
- [x] `EnsInput.parse` — accepts `foo.eth`, `foo.box`, `ens://foo.eth/path`
- [x] `BrowserState` display override: any URL under the resolved Swarm hash is rendered as `ens://<name>[/path]` in the address bar
- [x] `BrowserScreen.submit` — detects ENS input, shows a progress spinner while resolving, surfaces `NotFound` / `Unsupported` / transport errors via snackbar
- [x] End-to-end verified on the emulator: a Swarm-hosted ENS name resolves to its `bzz://<hash>` via `ethereum.publicnode.com`, address bar stays on `ens://<name>/` while the bee-lite gateway serves the resolved content
- [ ] ENSIP-15 normalization (currently lowercase-ASCII only; emoji / non-ASCII labels may diverge from `ens_normalize`)
- [ ] CCIP-Read (OffchainLookup reverts currently surface as `NO_RESOLVER`; required for `.box` via 3DNS and a handful of offchain `.eth` names)
- [x] ~~IPFS / IPNS display-only handling~~ — deferred indefinitely. Freedom is a Swarm-first browser; IPFS content that resolves through ENS falls back to a snackbar ("not supported yet") and that's where it stays for now.

**Exit criteria:** entering `<name>.eth` in the address bar loads the corresponding Swarm content, address bar shows `ens://<name>/`. ✅

### Phase 3c — Homepage & chrome polish ✅
- [x] Default homepage is `ens://freedombrowser.eth` — resolved natively (no `eth.limo` HTTPS gateway), served from the embedded node
- [x] Initial load gated on `NodeStatus.Running` so the WebView doesn't race the Swarm gateway; pending URL shows in the address bar while the amber status dot signals bee-lite is booting
- [x] Address bar pill rewritten as a `BasicTextField` inside a fixed-height 40 dp `Box` so focus / unfocus / icon swaps can't resize it
- [x] ENS display override persists across reload, back, and forward (only `submit` with a non-ENS URL clears it)
- [x] WebView pre-painted with a dark background + `about:blank` at construction time; otherwise its pre-first-paint compositor state blanks out the entire Compose tree and the top chrome disappears until the user's first navigation completes

### Phase 4 — Polish
- [ ] Settings screen: node status, clear browsing data, about
- [ ] "Freedom" branding + launcher icon
- [ ] Offline / node-not-ready handling
- [ ] Debug + release signing configs
- [ ] README with build instructions

## Deferred (future milestones)

- **Light mode**: wallet onboarding, xDAI / xBZZ funding, postage stamps, uploads ("Save page to Swarm")
- **ENS / Swarm feeds**: human-readable names → hash resolution
- **Multi-platform**: iOS / desktop
- **Pinning**: choose which Swarm content to persist locally

## Known issues & workarounds

### Emulator DHT bootstrap (resolved)
Bee-lite's default bootnode is `/dnsaddr/mainnet.ethswarm.org`, which requires multi-step TXT-record resolution. Android emulator DNS does not handle `/dnsaddr/` chains reliably, so peer discovery fails entirely (0 peers after minutes).

**Fix:** configure `Bootnodes` with the 5 concrete leaf multiaddrs resolved on the host, pipe-delimited:

```
/ip4/159.223.6.181/tcp/1634/p2p/QmP9b7MxjyEfrJrch5jUThmuFaGzvUPpWEJewCpx5Ln6i8   (ams)
/ip4/135.181.84.53/tcp/1634/p2p/QmTxX73q8dDiVbmXU7GqMNwG3gWmjSFECuMoCsTW4xp6CK   (hel)
/ip4/139.84.229.70/tcp/1634/p2p/QmRa6rSrUWJ7s68MNmV94bo2KAa9pYcp6YbFLMHZ3r7n2M   (jhb)
/ip4/172.104.43.205/tcp/1634/p2p/QmeovveLJmgyfjiA9mJnvFTawHyisuJMCYicJffdWdxNmr  (sgp)
/ip4/170.64.184.25/tcp/1634/p2p/Qmeh2e7U2FWrSooyrjWjnNKGceJWbRxLLx8Ppy5CimzsGH   (syd)
```

With this, emulator reaches ~80+ peers within 60s.

Note: `StaticNodes` takes *overlay addresses* (64-hex swarm IDs), not multiaddrs — do not confuse the two fields.

Freedom's Kotlin `SwarmNode` should apply this config. Long-term: re-resolve the chain periodically on the host (or ship a larger hard-coded list).

### Listener registration race (resolved)
Upstream `SwarmNode.addListener()` just appended to a list without replaying state. The node finishes `start()` on a worker thread and fires `onNodeInfoChanged(Running, wallet=...)` before `MainActivity.onServiceConnected` has bound and registered as a listener, so the `Running` edge is lost. UI stays stuck on the initial `Started` placeholder with empty wallet, which in turn leaves the Download button disabled (it gates on `NodeStatus.Running`).

**Fix:** on subscription, immediately replay the current state:

```java
public void addListener(SwarmNodeListener listener) {
    listeners.add(listener);
    listener.onNodeInfoChanged(this.nodeInfo);
}
```

**Freedom port:** the Kotlin wrapper should expose state as a `StateFlow<NodeInfo>` (or equivalent hot observable) so new collectors always receive the current value by construction — no manual replay needed, no chance of missed edges.

## Open items

- Physical device vs emulator for primary testing
