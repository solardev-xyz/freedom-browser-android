# Freedom — Swarm Browser for Android

A native Android browser that loads both regular `https://` sites and decentralised content addressed via `bzz://` hashes or `ens://` names. Both embedded nodes — [ant](https://github.com/solardev-xyz/ant), a Swarm light-node in Rust serving a local bee-shaped HTTP gateway on `127.0.0.1:1633`, and the [freedom-ipfs](https://github.com/solardev-xyz/freedom-ipfs) reader serving `ipfs://` / `ipns://` on an ephemeral loopback port — ship as one combined Rust library, `libfreedom_mobile_ffi.so`, built from [freedom-mobile-ffi](https://github.com/solardev-xyz/freedom-mobile-ffi). The WebView sees ordinary `http://127.0.0.1:…` URLs.

- **Package:** `baby.freedom.mobile` · **Version:** 0.4.0
- **Inspired by:** [`Solar-Punk-Ltd/swarm-mobile-android`](https://github.com/Solar-Punk-Ltd/swarm-mobile-android)

## Install

Download the latest APK from [GitHub Releases](https://github.com/solardev-xyz/freedom-browser-android/releases): `arm64-v8a` for phones/tablets, `x86_64` for emulators, `universal` if unsure. Android will prompt to allow installs from your browser or file manager the first time ("install unknown apps"). Every release is signed with the project key, so newer releases install as updates over older ones; `SHA256SUMS` in each release verifies the download.

### Cutting a release (maintainers)

1. Bump `versionCode` + `versionName` in `app/build.gradle.kts` (and the version above).
2. Tag and push: `git tag v0.x.y && git push origin v0.x.y`.
3. [`release.yml`](.github/workflows/release.yml) builds `libfreedom_mobile_ffi.so` at the pinned `FFI_REF`, assembles signed per-ABI APKs (signing key lives in repo secrets), and publishes them with `SHA256SUMS`. When upgrading the embedded nodes, bump `FFI_REF` together with the vendored headers. The native libraries (`libfreedom_mobile_ffi.so`, `libc4.so`) come from its cache when nothing that shapes them changed since the last build (#309): about 8 min instead of about 40 (about 25 when they have to be built). The cache is filled from `main` (a push that bumps `FFI_REF`/`COLIBRI_REF` or touches a build script, plus a twice-weekly refresh), so a tag pushed right after such a bump builds them from source, one ABI per job. A dry run (Actions → release → Run workflow) builds everything without publishing.

## Requirements

| Component | Version | Notes |
|---|---|---|
| JDK | 17 | Matches `sourceCompatibility` / `targetCompatibility` / `jvmTarget` in the Gradle files. |
| Android SDK | API 36 | `compileSdk = 36`, `targetSdk = 36`, `minSdk = 30` (Android 11+). |
| Android Build Tools | 36.0.0 | Installed via `sdkmanager "build-tools;36.0.0"`. |
| Gradle | 8.13 | Pinned via the wrapper; no global install needed. |
| Kotlin | 2.1.10 | Managed by Gradle plugin. |
| Android Gradle Plugin | 8.13.2 | Managed by Gradle plugin. |

Building the embedded-node artifact (required — not checked in) additionally requires:

| Component | Version | Notes |
|---|---|---|
| Rust | 1.99 at `FFI_REF` v0.12.14, pinned by `rust-toolchain.toml` in freedom-mobile-ffi (rustup installs it; release.yml and ffi-ref.yml install whatever channel that file names) | Compiles `libfreedom_mobile_ffi.so` (ant + freedom-ipfs in one cdylib) — see [Building libfreedom_mobile_ffi.so](#building-libfreedom_mobile_ffiso). |
| cargo-ndk | 4.1.2 | `cargo install cargo-ndk --version 4.1.2 --locked` — the `CARGO_NDK_VERSION` release.yml pins (and keys its native-library cache on); used by freedom-mobile-ffi's `scripts/build-android.sh`. |
| Android NDK | r27+ | Installed via `sdkmanager "ndk;27.2.12479018"` or similar. Also builds the JNI shims in `swarmnode/src/main/cpp/`. |

### One-time environment setup (macOS with Homebrew)

```bash
brew install --cask temurin@17
brew install --cask android-commandlinetools
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

The repo ships an [`.envrc.example`](./.envrc.example) that points `JAVA_HOME`, `ANDROID_HOME`, and `PATH` at Homebrew-installed toolchains. Copy it to `.envrc` (which is gitignored) and adjust for your machine:

```bash
cp .envrc.example .envrc
source .envrc   # or use direnv for automatic activation
```

## Quick start

Fresh clone, from zero to a running app:

```bash
# 1. Activate the toolchain env (JDK 17 + Android SDK).
source .envrc   # if you haven't: cp .envrc.example .envrc && edit to taste

# 2. Build the combined embedded-node library (ant/Swarm + freedom-ipfs).
#    Produces target/android/jniLibs/{arm64-v8a,x86_64}/libfreedom_mobile_ffi.so,
#    which is gitignored here and must exist before Gradle can build the app.
#    Needs cargo-ndk and ANDROID_NDK_HOME; rustup picks the toolchain from
#    the repo's rust-toolchain.toml.
#    Use the FFI_REF pinned in release.yml; scripts/build-ffi.sh builds it
#    the way release.yml does (ant's `chain` feature, the embedded Radicle
#    node, the Tor client and fat LTO — see "Building
#    libfreedom_mobile_ffi.so" below). Chained with && so a failed step
#    stops before a stale or chain-less .so is copied.
git clone https://github.com/solardev-xyz/freedom-mobile-ffi.git /tmp/freedom-mobile-ffi &&
  git -C /tmp/freedom-mobile-ffi checkout v0.12.14 &&
  scripts/build-ffi.sh /tmp/freedom-mobile-ffi &&
  mkdir -p swarmnode/src/main/jniLibs &&
  cp -r /tmp/freedom-mobile-ffi/target/android/jniLibs/. swarmnode/src/main/jniLibs/

# 3. Optional: build libc4.so, the Colibri verifier behind proven name
#    resolution (#100), at the COLIBRI_REF pinned in release.yml. Without
#    it the app builds and runs, and names start at the RPC quorum.
#    See "Colibri" below.
git clone --branch v3.0.0 https://github.com/corpus-core/colibri-stateless.git /tmp/colibri-stateless &&
  scripts/build-colibri.sh /tmp/colibri-stateless

# 4. Build the debug APK.
./gradlew :app:assembleDebug

# 5. Install on a connected device or running emulator.
./gradlew :app:installDebug
# or, for a slim per-ABI APK on a physical arm64 device:
#   adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

The build produces three debug APKs — `app-arm64-v8a-debug.apk` (~115 MiB), `app-x86_64-debug.apk` (~121 MiB), and `app-universal-debug.apk` (~163 MiB, all ABIs). See [APK size](#apk-size) for what to ship.

## Running on an emulator

No emulator-specific configuration is needed (see [DHT bootstrap](#dht-bootstrap) below for why):

```bash
# Create a Pixel AVD with Android 16 (API 36), arm64 on Apple Silicon.
avdmanager create avd -n freedom -k "system-images;android-36;google_apis;arm64-v8a"
emulator -avd freedom &

./gradlew :app:installDebug
adb shell monkey -p baby.freedom.mobile 1
```

Expected behaviour on cold start:

- Address bar is blank and the home page renders from the app's assets; the status dot is amber (node starting).
- Within ~25 s the dot turns green; peer count ramps to ~80+ within 60 s.
- Tap the status dot to see peer count and gateway URL.

Verify the gateway from the host:

```bash
adb forward tcp:1633 tcp:1633
curl http://127.0.0.1:1633/health      # {"status":"ok","version":"..."}
curl http://127.0.0.1:1633/status      # beeMode=ultra-light, ...
```

### DHT bootstrap

Swarm's default bootnode is `/dnsaddr/mainnet.ethswarm.org`, which requires multi-step TXT-record resolution — something mobile DNS stacks routinely fumble (the old bee-lite integration needed hard-coded pre-resolved multiaddrs for exactly this reason). ant's dnsaddr resolver walks the whole TXT tree itself, over plain DNS. On Android it can't read the system resolver config, so every lookup goes to Cloudflare's `1.1.1.1:53`. That works on the emulator and most networks, but a network that blocks or hijacks outbound port 53 leaves a fresh install with zero peers. `ant_init` takes no bootnode list, so `BootnodeSeeder` (in `swarmnode`) covers that case through ant's peerstore instead. Before `ant_init`, if `<dataDir>/ant/peers.json` is missing or empty, it:

1. walks the same TXT tree over DNS-over-HTTPS (`https://1.1.1.1/dns-query`, an IP literal, so it needs no DNS; 3 s total, 2 s per query), as freedom-browser-ios did before it moved to ant;
2. otherwise falls back to the shipped `FALLBACK_BOOTNODES` (the plain-TCP leaves of every regional record, captured with `dig`);

and writes the result as ant's peerstore snapshot. ant warm-dials those entries before its own DNS bootstrap, and replaces them with real peers on its next flush. Once `peers.json` has entries (every launch after the first successful one), nothing is fetched and startup isn't delayed.

## Swarm content retrieval

A fresh Swarm node pulls chunks on demand through the DHT, and any individual chunk lookup can transiently fail (HTTP `404 {"address not found or incorrect"}`) even when the content is healthy and plenty of peers are connected. A typical Swarm-hosted site loads 10–30 sub-resources; a modest per-request failure rate compounds into broken CSS, missing images, and videos that don't load. Retries almost always succeed — the problem is strictly first contact with cold content.

Freedom handles this in two layers:

1. **Navigation-time probe.** `GatewayProbe` HEAD-polls `/bzz/<hash>` before the WebView loads, with escalating delays, a 5-minute budget, and a grace window for `ECONNREFUSED` during node startup. The tab's spinner stays active while the probe runs; on timeout or unreachable the tab routes to `assets/error/error.html` with a **Try Again** button that re-enters the probe.
2. **Native request interception.** Sub-resource fetches go through `WebViewClient.shouldInterceptRequest` in `BrowserWebView.kt`, which:
    - Retries transient answers from `/bzz/`, `/ipfs/`, `/ipns/` with bounded backoff (8 attempts, ~17 s of delays between them). A main-frame load retries `404` and `5xx`, with 10 s for the headers and for each body read (60 s for a media file). Every other request (`fetch()`, images, media, CSS, frames) is treated the way Freedom desktop's `bzz:` handler treats it: `5xx` retried (the gateway is only ever sent a GET or HEAD), `404` passed straight to the page, and, for a few requests at a time, 30 s for the headers (60 s for media) and up to 120 s of silence per body read, so a node short on peer credit can pause a segment without it being thrown away and fetched again (`GatewayFetchPolicy.kt`). Every such wait blocks a thread of Chromium's process-wide worker pool, which every request in every tab needs, so only a third of that pool (at least one request) may wait past the 10 s (60 s for media) the navigation also has; the rest time out there, so stalled dweb reads can't freeze other sites. A body the page drops before its end closes the gateway connection, so the node stops retrieving it.
    - Rewrites absolute-root paths like `/_next/static/…` back under the current `/bzz/<hash>/` root (Next.js-style sites reference sub-resources this way and would otherwise 404).
    - Synthesises proper `206 Partial Content` for `<video>` / `<audio>` `Range` requests by fetching the body once into a small in-process LRU and slicing it. (Load-bearing under bee, which returned the full body for every Range; ant serves real single-range 206s, but the local buffer still means seeking never re-fetches.)
    - Stamps every outgoing fetch with `Swarm-Chunk-Retrieval-Timeout: 30s`, `Swarm-Redundancy-Strategy: 3`, `Swarm-Redundancy-Fallback-Mode: true` — the same retrieval hints bee honors, parsed by ant for parity.

Unlike the Electron-based desktop port, Android WebView does not allow registering a custom `bzz:` scheme as a first-class origin (there is no `session.protocol.handle` equivalent). So the WebView loads the gateway URL directly (`http://127.0.0.1:1633/bzz/<hash>/…`) and `BrowserState.currentBzzRoot` tracks the active root so the interceptor can rewrite absolute-root paths at request time.

## Swarm node mode and publish setup

The Swarm node runs **ultra-light** by default: it browses, with no chain at all. The node page's **Light mode** switch (#114, stored as `swarm_node_mode` with iOS's `BeeNodeMode` values) restarts it in **light** mode, where ant's gateway reports `beeMode: light` and reads Gnosis — `/wallet`, `/stamps`, `/chequebook`, `/chainstate` — through the chain-data router (#273), like the wallet: the shim's chain transport (`ant_jni.c`) hands every read ant makes to `AntChainBridge` in `:node`, which asks the router on Gnosis over your own Gnosis RPCs (Settings → Wallet & chains → Gnosis) and the public ones, with the same tier order and trust labels (`adb logcat -s ChainData AntChain` shows which tier answered). A read no source answers reaches ant as an error, never an empty result and never a fallback to one RPC; ant's `eth_getLogs` scans get their errors ranked as desktop's bridge ranks them, so a query-size refusal halves the scan's window and a throttle or a lagging RPC doesn't. Broadcasts are unchanged: `SpendGuard` admits only the spend you confirmed, and it goes out on your own Gnosis RPC if you added one, else the chain's first public RPC. ant needs no funds to switch (unlike bee, which deploys a chequebook at startup), so switching is free and reversible. The setting lives in the UI process; `MainActivity` relays it with the Gnosis RPCs to `:node` (`INodeService.setSwarmMode`) on every bind and change, and `:node` restarts the node once when its identity (#77) or mode differs from what it booted as (`SwarmBootIdentity`). A fresh `:node` reads the stored setting itself for its first boot.

**Set up publishing** on the node page opens the checklist (`PublishSetup.kt`, after desktop's `publish-setup.js` and iOS's `PublishSetupView`): use the wallet's identity, switch to light mode, fund the node's address with xDAI (balance read through the chain-data router), chequebook deployed, a usable postage stamp. The chequebook and stamp steps show their state from the light node's gateway; the stamp step opens the stamp pages below. One-transaction funding (#115) and the chequebook deposit (#117) are separate issues.

**Postage stamps** (#116, `Stamps.kt` / `StampsScreen.kt`, after desktop's `stamp-manager.js` and iOS's `StampsView`): the node page's **Postage stamps** lists the light node's batches from `GET /stamps` (capacity from bee's effective-size table, used %, time left and expiry), with a detail page per batch, a buy page (size × duration, priced by `ant_storage_quote`) and an extend page (`ant_storage_topup_quote`). Buying and extending run ant's xDAI-funded calls in `:node` (`INodeService.stampCall` → `ant_storage_buy_xdai` / `ant_storage_topup_xdai`): ant swaps the xDAI it needs for xBZZ itself, and a first buy also deploys and funds the chequebook. Every spend goes through a confirmation whose buttons ignore taps for its first 500 ms.

**Paid downloads** (browsing credit, after desktop's #490/#492): the Chequebook page shows the chequebook's **spendable credit** — bee's `availableBalance` from `GET /chequebook/balance`, the on-chain balance less the cheques peers haven't cashed — next to the on-chain balance, and whether downloads are **Paying peers** or on the **Free tier**, and why (switched off, no chequebook, credit used up, settlement not set up yet, payment record lost), from ant's `ant_swap_status` (`stampCall("swapStatus")`). **Pay peers from the chequebook** is bee's node-wide swap-enable, for downloads and uploads alike: stored as `swarm_swap_enabled` (on by default), relayed by `MainActivity` to `:node` (`INodeService.setSwapEnabled`) on every bind and change, and set on the node with `ant_set_swap_enabled` live and after every `ant_init` (ant doesn't persist it; `SwarmNode` applies it before the gateway's chain init, so a node switched off never pays, and again once the node is up). A fresh `:node` reads the stored setting itself for its first boot. When ant's `/chequebook/balance` reports `chequeLedgerLost` (its outbound cheque ledger was lost, so it pays no cheques), the page says so and offers a confirmation that calls `ant_confirm_cheque_liability` for the chequebook the node runs (`stampCall("confirmLiability")`; nothing is sent on chain).

**Finding stamps you already own** (#118): the stamps page's **Find stamps you already own** runs ant's `ant_storage_discover` (`INodeService.stampCall("discover")`): it scans the account's xBZZ transfers on Gnosis and registers every batch it still owns and that is still paid for with the node, so stamps bought on another device or before a reinstall can be published with. It sends nothing: the shim puts the broadcast gate back first, and no permit is open, so the chequebook deploy ant may try while it registers a batch is refused (a chequebook the account already has is adopted).

**Publish** (#118, `Publish.kt` / `PublishScreen.kt`, after desktop's `publish-service.js` / `publish-history.js` and iOS's `SwarmPublishHistoryView`): the node page's **Publish** (in light mode) publishes a file, a folder or some text. Each goes to the embedded node's gateway as `POST /bzz` with `Swarm-Pin: true`; a folder as a tar collection (`Swarm-Collection: true`), its top-level `index.html` as the index document, so `bzz://<reference>` opens it as a website. The stamp is picked like desktop's `selectBestBatch`: of the usable batches with room for the upload (whole 4 KiB chunks per file plus one of manifest, × 1.5), the one that lasts longest. A confirmation names what goes out, the stamp and that it's public and permanent. What was picked is staged in `cache/publish/` (capped at ant's 64 MiB upload limit, tar headers included) and deleted once sent; a leftover from a run that died mid-upload goes at the next start. The picker's read grant is held only until the publish is done with it. One publish runs at a time, in the UI process, and carries on when the page is left; the gateway answers once every chunk is pushed. The history (`files/publish/history.json`, newest first, at most 500) keeps each publish's name, kind, size, stamp, link or error, with Open (a new tab), Copy link, Share and Remove, and Clear all; a publish cut short by the app's end reads back as failed.

The node still never broadcasts anything else. `ant_jni.c`'s chain transport hands every `eth_send*` to `SpendGuard` (swarmnode), which refuses it unless a permit for the one spend the user confirmed is open, and then admits only that spend's own transactions, each kind once, decoded and checked field by field: the swap (to ant's swap helper, for the node, at most the xDAI the confirmation showed), the approval (exactly `amount × 2^depth` to the postage contract), `createBatch` with the confirmed owner/amount/depth/bucket depth/immutability or `topUp` of the confirmed batch, and on a buy the chequebook deploy (issuer = the node) and its ≤ 0.001 xBZZ deposit. A gateway request that races an app spend can at worst use up one of its slots, which makes the app's spend fail rather than spend twice. ant tops up only its *connected* batch (the first it registered), so only that one can be extended here.

A light node's gateway would sign and send transactions (buy/top up/dilute stamps, chequebook deposit) for any caller: it has no auth, and 127.0.0.1:1633 is reachable by every app on the device and by pages in any browser. So the node never broadcasts at all: `ant_jni.c` installs an `ant_set_chain_transport` callback before every gateway start that answers `eth_send*` with a JSON-RPC error (reads fall through to the RPC URL), whoever asked and however the request reached the gateway (a redirect, another app). On top of that, the request interceptor answers any page request outside the dapp surface (`/bzz`, `/bytes`, `/chunks`, `/soc`, `/feeds`, pss/gsoc, and the liveness probes `/health` and `/readiness`) with a clear 403 before it reaches the node, when it goes to the gateway port on a host that may be this device (chain writes on any host), for tabs, private tabs, workers and service workers alike (`NodeApiGuard.kt`, #114, #283). That covers those writes and the node's private reads too (`/wallet`, `/addresses`, `/stamps`, `/chequebook`, `/peers`, …), which up to ant 0.5.48 the gateway would hand to any page with an opaque (`null`) origin; since #284 the empty CORS allow-list is what keeps a page from reading them, and the interceptor is a readable 403 on top. Uploads stay open (see `docs/dapp-compatibility.md`). A redirect a fetch follows inside Chromium never reaches the interceptor, so such a request still reaches the node, from public https sites too (Private Network Access doesn't stop it in the shipped WebView); but since #284 the app starts the gateway with no CORS origins (`SwarmNode.GATEWAY_CORS_ORIGINS`), so the answer carries no `Access-Control-Allow-Origin` and the page's `fetch` can't read it (up to ant 0.5.48 the gateway answered `Origin: null` with `Access-Control-Allow-Origin: null`, which let a redirected page read `/wallet` and `/addresses`, #283). A Bee node on another machine is left alone when a page names it by a non-loopback IP address or it's the external Swarm node set in Settings; any other name on port 1633 is refused, since it may resolve to the device. The app's own spending flows (#115–#117) will have to lift the transport's refusal for the one transaction the user confirmed.

## Radicle: `rad://` and `window.radicle`

With Settings → Nodes & networks → **Radicle node** on (it runs in `:node` alongside the Swarm node, #73), repositories the node has in storage can be browsed and dApps can act on them — the Android port of desktop's `rad:` protocol and `window.radicle` provider (`docs/radicle-provider-api.md`, spec 0.2) and iOS's `RadSchemeHandler` / `RadicleBridge` (#124).

- **Browsing** (`RadUrl.kt`, `RadApi.kt`, `assets/rad/`). `rad://<rid>/…` (or the URN form `rad:<rid>/…`) loads from `https://rad.freedom.baby/<rid>/…`, which `shouldInterceptRequest` answers; the address bar, history and bookmarks show the `rad://` form. One host for every repository: the RID is case-sensitive base58, so it rides in the path, and the page is the browser's own viewer (code at the default branch head or a pinned commit, README, issues, patches, commits), not the repository's code. Everything the repository supplies is inserted as text under a CSP that runs only the viewer's script. A repository opens from the Radicle page's seeded list too.
- **Read API.** The viewer reads `https://rad.freedom.baby/_/api/<rid>/<endpoint>` — the same per-repository endpoints and JSON as desktop's `rad:` URLs (root, `tree`, `blob`, `readme`, `commits`, `stats`, `remotes`, `issues`, `patches`), GET/HEAD only, public repositories only. Unlike desktop's `rad:` URLs it is not open to other sites: only the viewer's own same-origin requests (told apart by their `Referer`) are answered, and any other page gets one fixed 403 before the node is asked, so a site can't probe which repositories the device holds. At most four calls into the node run at once. Calls cross into `:node` through `INodeService.radicleCall`, which answers over a pipe (an issue list can outgrow a binder transaction) and can reach only the reads and COB writes in `RadicleNode.BROWSER_CALLS`.
- **`window.radicle`** (`RadicleProvider.kt`, `RadicleProviderBridge.kt`, `RadiclePrompt.kt`). All 14 methods, three tiers: `getCapabilities` needs nothing; `requestAccess` asks once per origin to connect (node status, the seeded list, `sync`, and asking to `seed` / `unseed`, each of which prompts per repository); `getIdentity` and the four COB writes ask once more for the signing tier. Only a normal tab's top-level https (or loopback http) document gets it; its origin is the platform's, never the page's. Parameters are checked with desktop's limits before any prompt, writes are limited to 10 a minute per origin, and prompts are guarded like the site-permission prompt (tap delay, one at a time, taken down with the document). A refused prompt blocks that tab's pages from prompting again until the user navigates it. Grants live in their own DataStore and can be dropped from the Radicle page's **Connected sites**. Seeding goes through the node page's own one-at-a-time seed path, so a first fetch that fails takes its policy back.
- **Identity** (#328). With a wallet on the phone, the node runs as the Radicle key derived from its recovery phrase: SLIP-0010 Ed25519 at `m/44'/73404'/0'/0'/0'`, desktop's path, so the same phrase gives the same `did:key` as desktop. The key is sealed next to the Swarm and IPFS keys (`NodeIdentityStore`, version 2), and `:node` hands it to libradicle from memory at each boot (`start_with_key`). Nothing secret is written to `files/radicle`. The node's own key stays in `files/radicle/keys`, so **Remove wallet** brings that identity back. Storage and seeding policies are shared, so seeded repositories stay seeded across the switch. What was published before stays signed by the identity that wrote it. A switch restarts a running node with a notice, and takes every site's `window.radicle` signing grant back to the connection tier, so a site asks again before it learns or writes as the new identity.

## Ad and tracker blocking

Settings → **Ad blocking** switches the filter-list categories: as on desktop and iOS, **ads** (EasyList, plus uBlock Origin's own filters, as on desktop) and **trackers** (EasyPrivacy) on by default, **cookie notices** (Fanboy's Cookiemonster) and **other annoyances** (Fanboy's Annoyances) opt-in; and, Android only so far, **ads on German-language sites** (EasyList Germany, #405), on by default when German is among the phone's (or the app's) languages or the phone's region is DE/AT/CH/LI/LU, and offered to everyone else. EasyList Germany is bundled only (the update channel doesn't publish it), refreshed by `vendor-lists.py` with each release. The page menu's **Block ads here** switch allowlists the current site (and its subdomains) and reloads it; Settings lists the allowlist, and adds and removes sites. A private tab's allowlisting lasts for the private session only.

- **Lists** ship in `app/src/main/assets/adblock/`, unmodified; refresh them with `python3 infra/adblock/vendor-lists.py` before a release. uBlock Origin's filters (`ublock-filters.txt`: its `filters.txt` and Quick fixes, built at one uAssets commit with `!#if` blocks evaluated and `!#include`s spliced in, as desktop does) and the scriptlet code (`resources.json`, pinned by sha256) are bundled only — the update channel doesn't carry them — so they refresh with each release (`--ublock-only` refreshes just those). They are the floor: an update replaces a category's list only while its own header date (`! Last modified:`, else `! Version:`) is no older than the bundled one's, so an APK vendored after the feed's latest manifest keeps its fresher lists. Freedom's own fixes for sites upstream doesn't handle yet (`freedom-filters.txt`, #391) compile under *Block ads* next to uBlock's filters, with the same trust; they are maintained by hand in the repo — neither the script nor the update channel touches them.
- **Updates over Swarm** (#127; `AdblockManifest.kt`, `AdblockUpdates.kt`), the same channel desktop and iOS read. [freedom-adblock-service](https://github.com/solardev-xyz/freedom-adblock-service) publishes a signed manifest to a Swarm feed; the app reads that feed by its pinned owner and topic (`freedom/adblock/lists/v1`) through the Swarm gateway in use (the embedded node's, or an external endpoint), 45 s after start and then every 6 hours while it runs, or when you tap **Check for list updates** (**Keep filter lists up to date** turns the schedule off). Nothing in the payload is trusted until the manifest's EIP-191 `sig` — over its canonical JSON (keys sorted, compact, as the publisher's `canonicalManifestForSigning`) — recovers to the pinned signer address; the manifest's version must be above the applied one (the same version is taken again only to fetch a category switched on since); each list of an enabled category is then downloaded from `/bytes/<ref>` (or reused from the applied update if unchanged), capped at the size the manifest gives and checked against its signed sha256. Only when every list passes are they staged to `files/adblock/updated.next/` and swapped in for `files/adblock/updated/`, and the engine is rebuilt in the background — pages use the new lists from their next load, without a restart. Any failure keeps the lists in use; the swap is finished at the next read if the app dies half-way through it; a list on disk that no longer matches its hash is replaced by the bundled one at the next build. Settings shows which update is in use and how the last check went. For a test publisher, build with `-Pfreedom.adblockFeedOwner=0x… -Pfreedom.adblockSigner=0x…` (compile-time only; desktop's and iOS's `FREEDOM_ADBLOCK_*` overrides).
- **Engine** (`AdblockEngine.kt`): Adblock Plus syntax compiled on the device off the main thread at startup (~0.5 s for the default lists in a release build). Requests that arrive before the first build lands — a tab restored after the process was killed, or a page opened from another app on a cold start — wait for it, for at most 5 s from startup, and go through unfiltered only past that; a frame's first element-hiding CSS is held back the same way. The engine is built from the applied update's lists where they are intact and newer than the bundled ones (the bundled list otherwise), and rebuilt when the categories change or an update lands; a rebuild never makes requests wait: the previous engine answers until the new one is swapped in. `||host^` rules sit in a hash set, other patterns in a token index, so a lookup costs microseconds.
- **Requests** are blocked in `shouldInterceptRequest` with an empty `403` — never a main-frame navigation, the local node gateways, or a virtual dweb origin. A blocked request a `$redirect=` / `$redirect-rule=` filter names a stand-in for (#405) gets that stand-in instead, as a `200`: uBlock Origin's no-op resources from the bundled `resources.json` (`noop.js`, `1x1.gif`, a silent MP3, the `googletagservices_gpt.js` / `google-ima.js` shims, …), so a page that checks its ad script loaded carries on rather than breaking or putting up its blocker wall. A `$redirect=` filter blocks and names its stand-in, a `$redirect-rule=` one only names it for what other filters block; the highest `:priority` wins; `@@…$redirect-rule` / `@@…$redirect=name` lift the stand-in, not the block. A cross-origin `fetch` may read a stand-in (it echoes the request's `Origin`); `click2load.html`, an extension page, isn't served. The redirect directives are looked up only for a request already blocked. Service-worker fetches aren't filtered (they reach no tab's interceptor).
- **Dweb pages** (`bzz://`, `ipfs://`, `ens://`, …): their own resources come from the page's virtual origin and are never filtered, but third-party http(s) resources they pull in are. The page menu has no **Block ads here** switch there (the address isn't a host to allowlist), so a dweb page broken by a list rule can only be fixed by turning that category off in Settings.
- **The page menu's switch** is on only where filtering really applies: with every category off, while the lists are still compiling, or on a page a list exempts with `@@…$document`, it shows off, can't be tapped, and says why beneath the label. The site's own allowlisting can always be lifted from it.
- **Allowlist changes** apply at once and are written to Settings in the order they were made, one at a time.
- **Which page a request belongs to** (`AdblockPage.kt`): a request is judged against the tab's committed page. A navigation the WebView fetches from the network doesn't commit until Chromium says so, and the new page's own head/preload requests often arrive first; they are told apart by their `Referer` naming the destination (its URL, or its bare origin when that differs from the page on screen and from every frame the page on screen has loaded, so a page's embed from the site it links to stays judged against the page). A destination that sends no referrer (`Referrer-Policy: no-referrer`, or `same-origin` when arriving from another site), a same-origin navigation sending only its origin, or one to a site the page on screen embeds has those early requests judged against the page on screen until it commits. So an allowlisted site reached by a link from a non-allowlisted one can occasionally lose an early third-party head script, and a site reached from an allowlisted one can occasionally let one through; a reload settles it. Likewise, a Back (or Forward) that the back/forward cache restores to a page on the *same* origin can briefly attribute the frames the outgoing page embedded to the restored page, until a reload.
- **Cosmetic filtering** (`AdblockCosmetic.kt`): a document-start script asks for its frame's element-hiding CSS over a message channel and reports the class and id names it sees, so generic rules are sent only for names a page actually uses. The CSS is applied as constructed stylesheets (not subject to the page's CSP), and put back if the page replaces `document.adoptedStyleSheets` (with its next DOM change, or within 2 s). It doesn't reach elements inside a shadow root, nor `about:blank` / `srcdoc` iframes (the script runs in http(s) frames only). Rules scoped to uBlock *entities* (`spiegel.*##…`, `~example.*`) apply under any public suffix, as scriptlets do. Extended selectors CSS can express run as CSS (#405): `:has()` natively, Adblock Plus's `#?#…:-abp-has(…)` as `:has(…)`, uBlock's `:style(…)` as the rule's declarations (never ones that can load something — `url(`, `image-set(`, `@import` — or leave the rule), and `:remove()` as hiding. Procedural selectors that need a script walking the DOM (`:has-text()`, `:upward()`, `:matches-css()`, `:xpath()`, `:-abp-contains()`, `:remove-attr()`, …, about 560 rules, nearly all uBlock's), HTML filters (`##^`) and `$csp` / `$removeparam` / `$replace` filters are not supported, and filters using them are skipped.
- **Scriptlets** (#318, `AdblockScriptlets.kt`): `example.com##+js(…)` rules run uBlock Origin's scriptlets in the page before any of its own scripts — in the main frame and in same- and cross-origin frames — which is what blocks YouTube's video ads (`json-prune`, `set-constant`, `trusted-replace-fetch-response`, … on `www.youtube.com` / `m.youtube.com`). Only a vetted set runs (no `<script>`/`<style>` elements, no `eval`, no network requests of their own, no page global of their own beyond `window.onerror`, which `get-exception-token` wraps for `json-prune*`/`abort-*` as in uBlock — see `ScriptletCatalog`; the one exception is uBlock's own: `prevent-window-open` with a delay answers a blocked popup with a hidden decoy frame at the popup's URL, a request the network filter still sees, or with `blank` opens and then closes an `about:blank` window); the `trusted-*` ones only from uBlock's own list, as in uBlock; uBlock *entities* (`example.*`), `~` domains and `#@#+js(…)` exceptions are honoured; generic scriptlets (no host) aren't run. Each host's calls go in as a document-start script scoped to that host's origins, registered per tab for the hosts the tab loads documents from, before each document can arrive (a WebView copies every registered document-start script into every frame, so registering all ≈10k rules everywhere would cost each frame about a megabyte). The script skips its scriptlets when the tab's top-level page (read from `location.ancestorOrigins`) is allowlisted, so a frame on an allowlisted page runs none either; private tabs get the same, with their session allowlist. A frame's redirect hop reaches neither `shouldInterceptRequest` nor `shouldOverrideUrlLoading`, so each host is registered together with its `www.`/bare-domain twin (`youtube.com/embed/…` → `www.youtube.com`), and a host whose document turns up unannounced (the `Referer` of a request the document itself made — a script, `fetch`, frame, media; not an image or stylesheet, told apart by `Accept` since WebView's interceptor never gets `Sec-Fetch-Dest`, nor a stylesheet's font, told apart by its `Origin` naming another host than its `Referer` — a cross-origin stylesheet sends only its bare origin — or by a `Referer` that is a `.css` file) is registered for its next load. A document request registers its host and the twin together and never pushes its own host out of the tab's budget (24 hosts, 400k characters of script) to make room for the twin; past that, the budget drops entries one at a time, least recently needed first, so a host can stay while its twin goes (the host's next document request adds the twin back), and a host registered from a `Referer` comes alone. A frame that a service worker serves, or one redirected to a host other than its twin, from a host the tab hasn't loaded a document from before, gets its scriptlets from its next load on; so does a page reached by a 307/308 redirect of a form POST to another host, a hop WebView reports to neither callback.
- **What can't run** is counted: Settings shows, under each enabled category, how many of its lists' rules are in use (and how many of those are scriptlets) and how many can't run here (procedural and HTML filters, unsupported options, unvetted or generic scriptlets, and element-hiding and network rules whose only sites are uBlock entities like `example.*` — dropped, never applied to every other site).

Filter list data is © the list authors: the EasyList family dual-licensed GPLv3+ / CC BY-SA 3.0+ and redistributed under CC BY-SA; uBlock Origin's filters and scriptlets GPL-3.0, shipped as separate data files with the licence text (see `app/src/main/assets/adblock/README.md`). Freedom's own filter list (`freedom-filters.txt`) is GPL-3.0-only, as the uBlock rules it extends — the one part of Freedom's own code with a licence decided so far — and is listed among the filter lists on the Open-source licences screen.

## Tor (`.onion` sites)

`.onion` sites open through an embedded [Arti](https://gitlab.torproject.org/tpo/core/arti) Tor client (#143), or an external one such as Orbot (#275), as on desktop: **onion-only** (clearnet sites, the node gateways and the dweb protocols never touch Tor), **fail closed** (with Tor off or stopped, an onion site is refused, never resolved or dialled directly — except as a WebRTC server name a page chooses itself, see *Routing*) and **opt-in** (off by default).

- **Settings → Privacy & security → Tor**: *Tor for .onion sites* turns the integration on; *Start Tor at launch* starts the client at launch. Otherwise start and stop it with the switch on the **Tor** page (menu → *Nodes & networks* → Tor), which also shows the status (bootstrap progress, then *Connected*), the Arti version and the SOCKS port; while Tor is off in Settings the page offers *Turn on in Settings*, which opens this Tor card, and Back returns to the Tor page.
- **Client**: Arti 0.46 in `libfreedom_mobile_ffi.so` (freedom-mobile-ffi's opt-in `tor` feature, `freedom_tor_*`, header `swarmnode/src/main/cpp/freedom_tor.h`), wrapped by `baby.freedom.swarm.TorNode`. It runs in its own `:tor` process (`TorService`, bound only while Tor runs, so a fault in it can't take the browser down) behind a SOCKS5 listener on a free loopback port. The listener only connects to `.onion` names: an IP literal or a clearnet name is refused, so a misrouted request can never leave through a Tor exit. State and directory cache are kept in `files/tor/`.
- **Routing** (`TorRouting.kt`): the WebView's proxy override (androidx.webkit `ProxyController`) in reverse-bypass mode, so only `*.onion` uses the proxy. It is in place from launch, pointing at the Tor port while the client listens and at port 1 (nothing listens there, so the connection is refused) otherwise. Chromium's SOCKS5 sends the hostname to Tor, so an onion page or resource is never looked up in DNS. WebRTC is the exception: Chromium resolves an `RTCPeerConnection`'s STUN/TURN server names itself, past both the proxy override and the interceptor, so a page that lists a `.onion` name in its own `iceServers` sends that name to system DNS. Only the page's own chosen string leaks that way, nothing of the user's browsing, and no WebView API can gate it. On top of that, the request interceptor answers an onion page with *Tor is off* / *Tor isn't running* in place while no port is routed. Downloads, *Save image* and the `freedom-manifest.json` lookup on an onion external Swarm endpoint send an onion URL through the same port, or refuse it without one; the manifest lookup, like a page's onion request, first waits (within its own 30 s deadline) for a pending external-proxy check. An onion RPC endpoint is refused. A typed bare `….onion` opens over `http://`.
- **Needs** a WebView with `PROXY_OVERRIDE_REVERSE_BYPASS` (Chromium 105+). On an older one the switch stays off and onion sites are refused.
- **External Tor client** (#275): *Settings → Privacy & security → Tor → Tor client* picks the embedded Arti (default) or an **External SOCKS proxy**, a Tor client already running on the device such as [Orbot](https://orbot.app) (`127.0.0.1:9050`, prefilled). Only a loopback literal is accepted (`127.x.x.x`, `localhost`, stored as `127.0.0.1`, or `[::1]`), since the proxy sees every onion hostname; `TorProxy.kt` parses it. The proxy isn't trusted on sight: *Test* in the dialog, and the Tor page's switch, run a probe — a SOCKS5 greeting; a canary `CONNECT` to a well-formed v3 onion name with a wrong checksum, which Tor refuses locally at once while a proxy that answers "connected" before dialling (shadowsocks, v2ray, clash) doesn't, so no real onion name is sent to such a proxy; then a `CONNECT` by hostname to the Tor Project's onion service (DuckDuckGo's as a fallback, nothing sent). `.onion` is routed to it only once the canary is refused and a real onion connects, which takes a Tor client. While the app is in front the full probe runs again every 20 s (and at once when a routed onion page fails to load); if the proxy is gone or no longer passes, onion is refused again at once — except that Tor which still refuses the canary but just didn't reach the probe onions gets one more check 5 s later before it's refused. An unconfirmed proxy is checked again 5 s after nothing listened, every 20 s after Tor that answers but couldn't reach an onion (within 10 min of its last pass), and otherwise after a back-off from 10 s doubling up to 5 min. While the app is in the background nothing checks, so onion isn't routed to the proxy meanwhile; an onion request then, or on return, waits for the first check after the app is back — as long as that check can take at its own deadlines (the canary, then up to 45 s per probe onion: about 2 min at worst, `TorRouting.HOLD_MS`) — and loads if it passes; one refused while that check is still running (it took longer than that, or more than 8 were already waiting) gets *Checking the Tor proxy*, which asks again by itself every 5 s and so loads once the proxy passes; one refused while the app is in the background, when nothing checks (it went back before the check finished), gets *Tor proxy not checked yet* instead, which doesn't refresh: tap *Try again* once you're back in Freedom. While Tor runs with it, refused onion sites get *Tor proxy isn't reachable* (or *Tor can't reach onion sites*) in place; with Tor stopped on the Tor page (or not started at launch) nothing checks the proxy, and they get *Tor isn't running*, as with Arti stopped; `:tor` isn't started. With Orbot installed and nothing answering, the Tor card offers **Start Orbot** (Orbot's `org.torproject.android.intent.action.START` broadcast, which Orbot honours only with its *Allow background starts* option, and not before it has been opened once) and **Open Orbot**. Clearnet requests never go to the external proxy either: the proxy override still names only `*.onion`.
- **Not isolated per tab**: every tab, private ones included, shares the one Tor client. Each onion service gets its own circuits, but two tabs on the same onion service may share one.

## Project layout

```
freedom-browser-android/
├── app/                          # Android application (Compose + Material 3)
│   └── src/main/java/baby/freedom/mobile/
│       ├── MainActivity.kt       # hosts the Compose tree, binds NodeService
│       ├── IncomingLinkActivity.kt # links, shares, searches from other apps → MainActivity
│       ├── browser/              # tabs, address bar, WebView, resolver
│       ├── ens/                  # Keccak256, ENS contenthash, Universal Resolver
│       └── node/NodeService.kt   # foreground service owning the Swarm node
├── lint-checks/                  # HardcodedUiText lint check (docs/localisation.md)
├── swarmnode/                    # Kotlin wrapper around the embedded nodes
│   ├── src/main/jniLibs/         # libfreedom_mobile_ffi.so per ABI — combined ant + freedom-ipfs (gitignored)
│   ├── src/main/cpp/             # vendored ant.h + freedom_ipfs.h, JNI shims over both C APIs
│   └── src/main/java/baby/freedom/swarm/
│       ├── SwarmNode.kt          # ant lifecycle + StateFlow<NodeInfo>
│       ├── TorNode.kt            # Arti (Tor) lifecycle + StateFlow<TorInfo>
│       ├── AntNative.kt          # raw JNI surface over the ant C API
│       ├── IpfsNode.kt           # freedom-ipfs lifecycle + StateFlow<IpfsInfo>
│       ├── FreedomIpfsNative.kt  # raw JNI surface over the freedom-ipfs C API
│       ├── NodeInfo.kt           # status, peers, error
│       └── NodeStatus.kt         # Stopped | Starting | Running | Error
├── TODO.md                       # todo + deferred work
├── build.gradle.kts              # plugin versions
├── settings.gradle.kts           # module wiring
└── .envrc.example                # JAVA_HOME / ANDROID_HOME pointers for macOS
```

Three Gradle modules:
- `:app` — the Android application.
- `:swarmnode` — a self-contained Android library wrapping both embedded nodes (`libfreedom_mobile_ffi.so` + the JNI shims), depended on by `:app`. Designed to be publishable on its own.
- `:lint-checks` — the app's own lint checks (plain JVM): `HardcodedUiText`, which keeps user-visible string literals out of the code (see [docs/localisation.md](docs/localisation.md#the-lint-check)).

## Common tasks

```bash
./gradlew :app:assembleDebug           # debug APK
./gradlew :app:installDebug            # install on device/emulator
./gradlew :app:assembleRelease         # release APK (unsigned)
./gradlew :swarmnode:assembleRelease   # build the swarmnode .aar only
./gradlew :app:lintDebug               # localisation lint (HardcodedUiText, translations)
./gradlew :lint-checks:test            # the lint check's own tests

./gradlew clean                        # remove every module's build/
./gradlew --stop                       # kill background Gradle daemons
```

Reading the current APK's metadata:

```bash
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging app/build/outputs/apk/debug/app-arm64-v8a-debug.apk | head
# package: name='baby.freedom.mobile' versionCode='5' versionName='0.3.0'
```

## Building `libfreedom_mobile_ffi.so`

`libfreedom_mobile_ffi.so` is both embedded nodes in one Rust cdylib — the ant Swarm light-node plus the freedom-ipfs reader, compiled per ABI from [`solardev-xyz/freedom-mobile-ffi`](https://github.com/solardev-xyz/freedom-mobile-ffi). Combining them in a single compilation graph dedupes everything the two dependency trees share (std, tokio, hyper/axum, libp2p, ring, SQLite, …), which is ~7 MiB per ABI versus shipping two separate `.so`s. It's **not checked in**; every fresh clone builds it once:

```bash
# 0. Run from the root of this repo.
FREEDOM_ANDROID="$PWD"

# 1. Clone freedom-mobile-ffi at the ref release.yml pins as FFI_REF,
#    somewhere outside this repo.
git clone https://github.com/solardev-xyz/freedom-mobile-ffi.git /tmp/freedom-mobile-ffi &&
  git -C /tmp/freedom-mobile-ffi checkout v0.12.14

# 2. Cross-compile both ABIs. Needs cargo-ndk + ANDROID_NDK_HOME; rustup
#    installs the pinned toolchain + targets from rust-toolchain.toml.
#    scripts/build-ffi.sh is the one build recipe, shared with release.yml
#    (which keys its cache of the built library on it): it runs
#    scripts/enable-ffi-chain.sh, which puts ant's `chain` feature back
#    into freedom-mobile-ffi's --no-default-features build and fails if
#    that script's cargo call has changed shape; enable-ffi-radicle.sh,
#    which extends it to `chain,radicle`, the embedded Radicle node;
#    enable-ffi-tor.sh, to `chain,radicle,tor`, the Arti client for .onion
#    (see below); and enable-ffi-fat-lto.sh, which switches the
#    release-android profile from thin to fat LTO (see "Library size"
#    below). Then it runs the checkout's own scripts/build-android.sh,
#    which verifies both C ABIs are exported and stages the matching
#    headers under target/android/headers/, and checks both ABIs came out.
#    Pass an ABI (arm64-v8a or x86_64) as a second argument to build only
#    that one, as release.yml's per-ABI jobs do.
"$FREEDOM_ANDROID/scripts/build-ffi.sh" /tmp/freedom-mobile-ffi

# 3. Copy the results into Freedom.
mkdir -p swarmnode/src/main/jniLibs
cp -r /tmp/freedom-mobile-ffi/target/android/jniLibs/. swarmnode/src/main/jniLibs/
```

The Kotlin side talks to it through the hand-written JNI shims in `swarmnode/src/main/cpp/` (built into `libfreedom_jni.so` by the module's CMake step): `ant_jni.c` wraps the ant C API (`ant_init`, `ant_start_gateway` — the bee-shaped HTTP gateway on `127.0.0.1:1633`, started with an empty CORS allow-list through `ant_set_gateway_cors` so no page on another origin can read its answers (#284) —, `ant_peer_count`, `ant_shutdown`) and `freedom_ipfs_jni.c` wraps the freedom-ipfs loopback-gateway surface. Both shims call `freedom_mobile_init_logging()` (header `freedom_mobile.h`, freedom-mobile-ffi's own export) before starting their node: the `tracing` subscriber is process-wide and the first node to claim it wins, so without it ant's log subscriber would keep freedom-ipfs's progress recorder out and `freedom_ipfs_node_progress_snapshot_json` would stay empty (#156). When upgrading, refresh the vendored `swarmnode/src/main/cpp/{ant.h,freedom_ipfs.h,freedom_mobile.h}` from the build's `target/android/headers/` along with the `.so`s, and bump the pinned (ant, freedom-ipfs) tags in freedom-mobile-ffi's `Cargo.toml` — the same aggregator also feeds the iOS xcframework, so both platforms move versions together.

Since freedom-mobile-ffi v0.12 the library also links the Myotis Ethereum light client (`myotis_*` exports; not optional upstream). The app drives it through `swarmnode/src/main/cpp/myotis_jni.c` (header `myotis_engine.h`, vendored from the myotis tag freedom-mobile-ffi pins — v0.1.12, engine ABI 32; refresh it with `FFI_REF`) from its own `:myotis` process, off by default and switched on from its own page under *Nodes & networks* (#72, #416) — Ethereum and Gnosis each with their own switch and start-at-launch choice (#274), so a chain that's off costs no battery or data, and names resolve without it through Colibri or the RPCs, as with the light client off. When a chain's embedded trust anchor is older than the engine's weak-subjectivity bound (Gnosis: 3 sync-committee periods, ~34 h; mainnet: 13, ~15 days) the chain parks in `STALE_ANCHOR`, and the node recovers it from a fresh finalized checkpoint agreed by an external quorum of checkpoint-sync authorities (mainnet 2 of 3 seats from 7, Gnosis 2 of 3), bootstrapped into a new sync-state generation via `myotis_create_with_checkpoint` (#195; `MyotisCheckpointQuorum.kt`, `MyotisGenerationStore.kt`). Unlike desktop and iOS it doesn't corroborate the quorum with a Colibri proof (the app's Colibri verifier serves name resolution only, #100); it never accepts a stale anchor or raises the bound. The library is built with ant's `chain` feature so the gateway's `/wallet`, `/stamps`, `/chequebook` and `/chainstate` read Gnosis while the Swarm node runs in light mode (see [Swarm node mode and publish setup](#swarm-node-mode-and-publish-setup)); in ultra-light mode, the default, there's no chain traffic and those endpoints answer bee's zero-stubs.

The `radicle` feature adds the embedded, publish-capable Radicle node (libradicle-uniffi with `no-spawn`, #73; about +7 MiB per ABI). Unlike ant and freedom-ipfs it has no hand-written C shim: Kotlin calls it through [UniFFI](https://mozilla.github.io/uniffi-rs/) bindings, committed as `swarmnode/src/main/java/uniffi/libradicle_uniffi/libradicle_uniffi.kt` and loaded through JNA, and wrapped by `baby.freedom.swarm.RadicleNode`. The generated code checks each function's checksum against the library at load, so whenever the `.so` changes (an `FFI_REF` bump), regenerate them from the same build and commit the result:

```bash
scripts/generate-radicle-bindings.sh /tmp/freedom-mobile-ffi           # rewrite the committed file
scripts/generate-radicle-bindings.sh /tmp/freedom-mobile-ffi --check   # fail if stale (release.yml does the same with --emit + cmp)
```

The `tor` feature adds the Arti Tor client for `.onion` sites (#143; see [Tor](#tor-onion-sites)): freedom-mobile-ffi's own `freedom_tor_*` C surface, driven through `swarmnode/src/main/cpp/tor_jni.c` (header `freedom_tor.h`, vendored from `include/` at `FFI_REF`; refresh it with the `.so`). It adds about 7 MiB per ABI to the library. release.yml checks all five `freedom_tor_*` exports after the build, since `libfreedom_jni.so` links against them. A library built without `tor` fails that link.

### Library size

`libfreedom_mobile_ffi.so` is nearly all of the APK (#230). freedom-mobile-ffi's `release-android` profile already strips symbols, aborts on panic and builds one codegen unit; `scripts/enable-ffi-fat-lto.sh` swaps its thin LTO for fat, about 10% smaller per ABI at the same speed, for a longer link. `opt-level` stays 3: `"s"` would take off another third, but it makes AES-CTR (Tor) about 8× slower, ChaCha20-Poly1305 (libp2p) 2.5× and SHA-256 (IPFS) 2.8×. The APK stores native libraries compressed (`jniLibs.useLegacyPackaging` in `app/build.gradle.kts`), which roughly halves the download and costs about 19 MB more once installed, since the installer extracts them; and it leaves out the 32-bit and MIPS copies of third-party native libraries, which can't run the app anyway.

## Colibri: proven name resolution

ENS, WNS and GNS names are first resolved through corpus.core's [Colibri](https://github.com/corpus-core/colibri-stateless) stateless verifier (#100): a remote prover (`mainnet1.colibri-proof.tech`, then `mainnet.colibri-proof.tech`) builds a proof of the Universal Resolver's (or NameNFT registry's) answer, and the app checks it on the device against Ethereum's sync committee — the shield's top tier, *Proven name* (a seal, where the quorum's *Verified name* is a shield). Settings match desktop's and iOS's: ZK sync-committee proofs, privacy mode *basic* (the call's storage reads go to the name-resolution RPC endpoints and are checked against the proven state root), and a proof for `latest` older than 60 s is refused. When no proof arrives within 6 s — prover down, a revert that proves nothing, not in this build, or *Settings → Name resolution → Colibri proofs* off — the lookup goes on to the RPC quorum as before; a call still running carries on in the background (up to 60 s) so a first-run bootstrap isn't wasted.

`libc4.so` is built from the tag release.yml pins as `COLIBRI_REF` by `scripts/build-colibri.sh` (NDK r27, arm64-v8a + x86_64, 16 KB pages, ~2.3 MiB per ABI) into `swarmnode/src/main/jniLibs/` (gitignored). The C core does no I/O: `swarmnode/src/main/cpp/colibri_jni.c` (built into its own `libfreedom_colibri.so`, header `colibri.h` vendored from the same tag) bridges its request state machine, and `EnsColibri.kt` runs each HTTP request it asks for. The verifier's state lives in `files/colibri/` and is wiped when the library version changes. When bumping `COLIBRI_REF`, refresh `colibri.h` from the tag's `src/api/colibri.h`. The chain-data router's `COLIBRI` tier (#108) and Myotis's checkpoint corroboration don't use it yet.

## APK size

The combined node library is ~41 MiB (arm64) / ~47 MiB (x86_64) with Radicle and Tor and dominates the APK — everything else (dex, resources, the JNI shim) is under 6 MiB in a release build.

`app/build.gradle.kts` already enables per-ABI splits (`arm64-v8a` + `x86_64`) alongside a universal fallback, so every build produces:

| APK | Release | Debug | Use |
|---|---|---|---|
| `app-arm64-v8a-*.apk` | ~50 MiB | ~115 MiB | Physical arm64 devices, Apple Silicon emulators |
| `app-x86_64-*.apk` | ~56 MiB | ~121 MiB | x86_64 Android emulators |
| `app-universal-*.apk` | ~98 MiB | ~163 MiB | Fallback / `:installDebug` default |

The Tor client (#143) accounts for +6.7 MiB of the arm64 release APK and +7.8 MiB of the x86_64 one (52,170,224 vs 45,103,285 and 58,625,049 vs 50,460,382 bytes, measured against the same tree built without the `tor` feature).

(For context: shipping ant and freedom-ipfs as two separate `.so`s cost ~11 MiB more per ABI in duplicated Rust std/tokio/libp2p/SQLite; the gomobile-era APKs were 157–456 MiB.)

For distribution, Android App Bundles ship just the one ABI the device needs via Play Store's dynamic delivery:

```bash
./gradlew :app:bundleRelease
```

## Troubleshooting

**Gradle can't find `JAVA_HOME`.** Run `source .envrc` or set `JAVA_HOME` to a JDK 17 install. The wrapper requires it; there's no fallback.

**`./gradlew` downloads Gradle every invocation.** Your `GRADLE_USER_HOME` is set to an ephemeral path or you're offline. Point it at a persistent directory (default: `~/.gradle`).

**Node never reaches `Running` on emulator.** Check `adb logcat -s SwarmNode` for errors. If the node runs but gathers no peers, the network may be blocking outbound TCP dials or UDP DNS to `1.1.1.1` (ant's bootstrap fallback). Try on a different network or a physical device.

**`UnsatisfiedLinkError` after a minified release build.** Make sure `swarmnode/consumer-rules.pro` is being honoured — it keeps the native method names on `baby.freedom.swarm.AntNative` and `baby.freedom.swarm.FreedomIpfsNative`, which the JNI shims resolve by exact symbol; R8 renames them without it.

**Names never get the *Proven name* seal.** `adb logcat -s EnsResolver ColibriNative` says why: "Colibri not in this build" means `libc4.so` wasn't in `swarmnode/src/main/jniLibs/<abi>/` when the APK was built — see [Colibri](#colibri-proven-name-resolution).

**`UnsatisfiedLinkError` mentioning `libfreedom_mobile_ffi.so` or `libfreedom_jni.so`.** The prebuilt combined library for that ABI is missing from `swarmnode/src/main/jniLibs/` — see [Building libfreedom_mobile_ffi.so](#building-libfreedom_mobile_ffiso).

## Further reading

- [`TODO.md`](./TODO.md) — what's still open and deferred.
- [Swarm docs](https://docs.ethswarm.org/) — the Swarm network itself.
- [`ant`](https://github.com/solardev-xyz/ant) — the embedded Swarm light-node (Rust).
- [`freedom-ipfs`](https://github.com/solardev-xyz/freedom-ipfs) — the embedded IPFS reader (Rust).
- [`freedom-mobile-ffi`](https://github.com/solardev-xyz/freedom-mobile-ffi) — the aggregator that builds both into one library per platform (Android `.so`, iOS xcframework).

## License

TBD. Not yet decided — except for `app/src/main/assets/adblock/freedom-filters.txt`, Freedom's own filter rules, which are GPL-3.0-only like the uBlock filters they extend.

Third-party licences: Settings → About Freedom → **Open-source licences** lists every component the APK ships (Gradle dependencies, the Rust crates in `libfreedom_mobile_ffi.so`, what Colibri links into `libc4.so`, the OpenLV bundle and the filter lists) with its licence text. The list is built from [`app/licences/`](app/licences/README.md), and the build fails when it's incomplete or stale; when bumping `FFI_REF` or `COLIBRI_REF`, see that README.
