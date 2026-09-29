# Parity gaps after the parity plan

The parity plan ([#128](https://github.com/solardev-xyz/freedom-browser-android/issues/128)–[#137](https://github.com/solardev-xyz/freedom-browser-android/issues/137), from the [26 Sep 2026 feature inventory](https://vibing.at/freedom-features/)) is done. This is a fresh pass over what Android still lacks, or does noticeably worse, compared with desktop and iOS ([#232](https://github.com/solardev-xyz/freedom-browser-android/issues/232)). It also covers polish that no platform has yet but that a phone browser needs. Each gap is its own issue, labelled `parity` and in the same shape as the parity issues (What / Reference implementations / Done when / Depends on, with a size).

## Sources

| Platform | Repository @ commit | Read |
|---|---|---|
| Desktop | [freedom-browser](https://github.com/solardev-xyz/freedom-browser) @ `32f8bbe` (main, 29 Sep 2026) | `docs/features.md`, Settings sections, the hamburger and page menus, `CHANGELOG.md` 0.8.5–0.8.6 and the unreleased `changelog.d/` fragments, the commits since the inventory's `2983dc6` |
| iOS | [freedom-browser-ios](https://github.com/solardev-xyz/freedom-browser-ios) @ `ee1024a` (main, 29 Sep 2026) | Every view, settings page and sheet under `Freedom/Freedom/`, `Info.plist`, the project file, `git log` |
| Android | this repo @ `bf02f51`, and the [v0.6.10](https://github.com/solardev-xyz/freedom-browser-android/releases/tag/v0.6.10) release | Code, plus the release APK on an x86_64 emulator for anything user-visible (error pages, the home page, TalkBack labels via `uiautomator dump`, 200% font scale, a renderer kill, a node-API fixture) |

## Gaps filed

### Browser

| Issue | Gap | Desktop | iOS | Size |
|---|---|---|---|---|
| [#259](https://github.com/solardev-xyz/freedom-browser-android/issues/259) | Friendly error pages for failed https loads and certificate errors (today: WebView's stock page, and a certificate error leaves the previous page on screen) | yes | partial | M |
| [#260](https://github.com/solardev-xyz/freedom-browser-android/issues/260) | Keep the app alive when a tab's renderer process dies (today the whole app is killed and the tabs are lost) | yes | n/a | S |
| [#261](https://github.com/solardev-xyz/freedom-browser-android/issues/261) | Pop-up blocker: show what was blocked, open it, allow per site | yes | no | M |
| [#262](https://github.com/solardev-xyz/freedom-browser-android/issues/262) | Hard reload that bypasses the cache | yes | no | S |
| [#263](https://github.com/solardev-xyz/freedom-browser-android/issues/263) | History: search, and grouping by day | yes | yes | S |
| [#264](https://github.com/solardev-xyz/freedom-browser-android/issues/264) | Bookmarks: rename, edit the address, reorder | yes | no | M |
| [#265](https://github.com/solardev-xyz/freedom-browser-android/issues/265) | Downloads: pause and resume | yes | yes | M |
| [#266](https://github.com/solardev-xyz/freedom-browser-android/issues/266) | See and revoke this site's permissions from the page | yes | no | M |
| [#267](https://github.com/solardev-xyz/freedom-browser-android/issues/267) | Site permissions: MIDI, and a decision on protected media (DRM) | yes (MIDI) | no | S |
| [#268](https://github.com/solardev-xyz/freedom-browser-android/issues/268) | Open links, shared text and web searches from other apps; default browser | no | no | M |
| [#269](https://github.com/solardev-xyz/freedom-browser-android/issues/269) | Appearance setting: Light, Dark or System | yes | no | S |
| [#270](https://github.com/solardev-xyz/freedom-browser-android/issues/270) | Hardware keyboard shortcuts (tablets, Chromebooks, DeX) | yes | no | S |
| [#271](https://github.com/solardev-xyz/freedom-browser-android/issues/271) | Decide on browser profiles | yes | no | L |
| [#272](https://github.com/solardev-xyz/freedom-browser-android/issues/272) | Tell the user when a newer release is out | yes | App Store | S |

### Nodes and protocols

| Issue | Gap | Desktop | iOS | Size |
|---|---|---|---|---|
| [#273](https://github.com/solardev-xyz/freedom-browser-android/issues/273) | Swarm node reads Gnosis through the chain-data router, not one unverified RPC | yes | yes | M |
| [#274](https://github.com/solardev-xyz/freedom-browser-android/issues/274) | Myotis: start and stop Ethereum and Gnosis separately | yes | no | S |
| [#275](https://github.com/solardev-xyz/freedom-browser-android/issues/275) | Tor through an external SOCKS proxy (Orbot) | yes | no | S |
| [#276](https://github.com/solardev-xyz/freedom-browser-android/issues/276) | Node logs: view and share | log file | yes | S |
| [#283](https://github.com/solardev-xyz/freedom-browser-android/issues/283) | Keep web pages from reading the Swarm node's local API (today a sandboxed iframe, origin `null`, reads `/wallet`, `/stamps`, `/addresses` and `/chequebook/balance`; only writes are refused. Seen from a private-address page only; a public https origin, where Private Network Access may block it, is not checked yet) | yes | not checked | S |

### Wallet

| Issue | Gap | Desktop | iOS | Size |
|---|---|---|---|---|
| [#277](https://github.com/solardev-xyz/freedom-browser-android/issues/277) | Send to ENS, WNS, GNS and DNS names | yes | yes (ENS) | M |

### Polish: onboarding, accessibility, localisation

| Issue | Gap | Desktop | iOS | Size |
|---|---|---|---|---|
| [#278](https://github.com/solardev-xyz/freedom-browser-android/issues/278) | First-run introduction, and an Explore entry into the dweb on the home page | welcome page | Explore only | M |
| [#279](https://github.com/solardev-xyz/freedom-browser-android/issues/279) | TalkBack labels and roles, and a large-font pass (the address field has no accessible label; the tab button reads "1") | not checked | minimal | M |
| [#280](https://github.com/solardev-xyz/freedom-browser-android/issues/280) | Localisation groundwork: UI strings into resources | no | no | L |

What the Desktop and iOS columns mean:

- **yes**: that platform has the feature. **yes (X)**: it has only part X of it (MIDI of #267, ENS of #277).
- **no**: it doesn't either; the row is then Android polish or a mobile expectation, and the issue says so.
- **partial**: it has part of it; the issue's *Reference implementations* says which part (for [#259](https://github.com/solardev-xyz/freedom-browser-android/issues/259), iOS has error pages for dweb loads only).
- **minimal**: it has the feature in name only (iOS has about 13 accessibility labels in the whole app).
- **n/a**: the gap doesn't apply to that platform, so there's nothing to compare ([#260](https://github.com/solardev-xyz/freedom-browser-android/issues/260)'s renderer-crash handling on iOS).
- **not checked**: not audited in this pass, so it says nothing about whether that platform has it. For [#283](https://github.com/solardev-xyz/freedom-browser-android/issues/283), iOS's node API wasn't probed from a web page; for [#279](https://github.com/solardev-xyz/freedom-browser-android/issues/279), desktop's screen-reader and large-font support wasn't tested (TalkBack itself is Android-only).
- Anything else names that platform's nearest equivalent: **App Store** (updates come through the store, so there's nothing to build), **log file** (desktop writes node logs to a file but has no viewer), **welcome page** (desktop's home page explains the dweb, with no separate first-run flow), **Explore only** (an Explore entry with no first-run introduction).

## Checked and not filed

- **Home page contents.** Android's home already shows Bookmarks and Recent (`HomeScreen.kt`), as iOS's does. Only the Explore entry is missing, and that is folded into [#278](https://github.com/solardev-xyz/freedom-browser-android/issues/278).
- **Empty states.** History, Bookmarks and Downloads have them (`EmptyState` in `HistoryBookmarksScreens.kt`, `DownloadsScreen.kt`).
- **Font scale.** At 200%, the main menu and the home page lay out without clipping; the one truncation found (the address placeholder) is in [#279](https://github.com/solardev-xyz/freedom-browser-android/issues/279).
- **Already at parity or ahead.** Private tabs, find in page, zoom, print, desktop site, context menus, search engines, site permissions and app links, downloads, file upload, reopen and reorder tabs, audio mute, ad blocking with Swarm updates, verified names (quorum, Colibri, Myotis, `.wei`/`.gwei`/`.tez`, re-verify on Back/Forward), `web3://`, `rad://` and `window.radicle`, Tor, the wallet (accounts, send, receive, QR, chains, history, Ledger over BLE, Safe, x402, auto-approve, OpenLV signer), publishing (stamps, chequebook, funder, publish page, `window.swarm` with messaging and manifests), Settings search, and theme-colour tint.
- **Desktop-only by nature** (section 11 of the inventory): multiple windows, remappable shortcuts, tabs in the title bar, developer tools and View Page Source, the bookmarks bar, multi-instance profile launching, the Electron auto-updater and its fuses, and the requester half of phone signing.
