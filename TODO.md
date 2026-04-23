## Todo

- Gate `bzz://` navigation on `NodeStatus.Running` — currently fires unconditionally and relies on the WebView falling back.
- Error page for unreachable hashes or node failures; `BrowserWebView` has no `onReceivedError` / `onReceivedHttpError` override.
- ENSIP-15 normalization (currently lowercase-ASCII only).
- CCIP-Read / OffchainLookup (required for `.box` via 3DNS and some offchain `.eth` names).
- Offline / node-not-ready handling beyond the status dot.
- Real release keystore to replace the debug-keystore stand-in in `app/build.gradle.kts`.
- End-to-end Swarm content fetch test against a hash we pinned ourselves (blocked on Light-mode uploads).

## Deferred

- **Light mode**: wallet onboarding, xDAI / xBZZ funding, postage stamps, uploads ("Save page to Swarm")
- **ENS / Swarm feeds**: human-readable names → hash resolution
- **Multi-platform**: iOS / desktop
- **Pinning**: choose which Swarm content to persist locally
