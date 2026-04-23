## Status

### Shipped

Multi-tab browser shell (Compose + Material 3, one live `WebView` per tab as sibling views so hidden tabs keep scroll, JS, and form state), back/forward/reload, progress bar, address bar with history + bookmark autocomplete, ModalBottomSheet tab switcher.

`bzz://<hash>[/path]` and `ens://<name>[/path]` resolve via the embedded bee-lite node. `SwarmResolver` rewrites on the way into the WebView and `onPageStarted` / `onPageFinished` map it back so the address bar stays on the canonical form. ENS uses a pure-Kotlin Keccak256 + a hand-rolled ABI call to the Universal Resolver, with 5-endpoint RPC retries and a 15-minute cache; Swarm (`0xe40101fa011b20`), IPFS (`0xe30170`), and IPNS (`0xe50172`) codecs are parsed, Swarm is served natively and IPFS/IPNS fall back to a snackbar.

Local homepage shipped inside the APK at `app/src/main/assets/home/home.html` — loads without waiting for the node to bootstrap, and renders even when offline. Default tab opens it via a `file://` URL; the address bar hides the asset path for a clean empty-state.

History + bookmarks in Room 2.8.4 via KSP; `BrowsingRepository` is the single write choke-point, records the *displayed* URL (`bzz://…`, `ens://…`, or `https://…`) on `onPageFinished`, and skips `about:` / `data:` / `javascript:` / `blob:`.

"via Swarm" origin badge: whenever the loaded page's display URL starts with `bzz://` or `ens://` (both cases mean the bytes came from the embedded bee-lite gateway), the address pill grows a 16 dp Swarm-orange hex mark on the left. Drawable ported from freedom-browser's desktop `.icon-swarm` SVG.

Node lifecycle in a `NodeService` foreground service with a live-updating notification (status + peer count). `SwarmNode` exposes state as a `StateFlow<NodeInfo>`, so new collectors always receive the current value — no listener-replay race. A full-screen node page (reached from the top-chrome status dot) shows peers, wallet, mode, gateway URL, and a persistent run-node toggle backed by DataStore.

Build and distribution: Gradle 8.13, AGP 8.13.2, Kotlin 2.1.10, Compose BOM 2025.01.01, min SDK 30, target SDK 36. Per-ABI APK splits (`arm64-v8a`, `x86_64`, universal). Release build signs with the debug keystore so local artifacts install without Play Store signing. Launcher icons present at all densities with an adaptive foreground.

### Not yet done

- Gate `bzz://` navigation on `NodeStatus.Running` — currently fires unconditionally and relies on the WebView falling back.
- Error page for unreachable hashes or node failures; `BrowserWebView` has no `onReceivedError` / `onReceivedHttpError` override.
- ENSIP-15 normalization (currently lowercase-ASCII only).
- CCIP-Read / OffchainLookup (required for `.box` via 3DNS and some offchain `.eth` names).
- Unified Settings screen — pulls together node page, browsing-data reset (currently only history-trash from the library sheet), and an About page.
- Offline / node-not-ready handling beyond the status dot.
- Real release keystore to replace the debug-keystore stand-in in `app/build.gradle.kts`.
- End-to-end Swarm content fetch test against a hash we pinned ourselves (blocked on Light-mode uploads).

## Deferred

- **Light mode**: wallet onboarding, xDAI / xBZZ funding, postage stamps, uploads ("Save page to Swarm")
- **ENS / Swarm feeds**: human-readable names → hash resolution
- **Multi-platform**: iOS / desktop
- **Pinning**: choose which Swarm content to persist locally
