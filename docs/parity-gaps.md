# Parity gaps after v0.6.13

*30 September 2026.* A fresh pass over what Android lacks, or does noticeably worse, compared with desktop and iOS ([#315](https://github.com/solardev-xyz/freedom-browser-android/issues/315)). It follows the first post-parity pass ([#232](https://github.com/solardev-xyz/freedom-browser-android/issues/232)), whose gaps are now all closed. Each new gap is its own issue, labelled `parity`, in the same shape as before (What / Reference implementations / Done when / Depends on, with a size).

## Sources

| Platform | Repository @ commit | Read |
|---|---|---|
| Desktop | [freedom-browser](https://github.com/solardev-xyz/freedom-browser) @ `39ad0247` (main, 30 Sep 2026) | `docs/features.md`, every Settings section, the hamburger, page, tab and wallet-sidebar menus, `CHANGELOG.md` 0.8.6 and the unreleased `changelog.d/` fragments, checked against `src/` |
| iOS | [freedom-browser-ios](https://github.com/solardev-xyz/freedom-browser-ios) @ `ee1024a` (main, 29 Sep 2026) | Every view, settings page and sheet under `Freedom/Freedom/`, `Info.plist`, `git log` since the inventory's `1fd4500` |
| Android | this repo @ `8f1434e7`, and the [v0.6.13](https://github.com/solardev-xyz/freedom-browser-android/releases/tag/v0.6.13) release | Code, plus the release APK on an x86_64 emulator for the two gaps that could be shown there (`ethereum:` links, clipboard reads) |

The row list starts from the [26 Sep 2026 feature inventory](https://vibing.at/freedom-features/). Every cell was re-read from code at the commits above, so the desktop and iOS columns differ from the inventory wherever those platforms have added features since it was written.

## What changed since the last analysis

- **Android** went from v0.6.10 to v0.6.13, and every gap the last pass filed is closed. [#259](https://github.com/solardev-xyz/freedom-browser-android/issues/259)–[#270](https://github.com/solardev-xyz/freedom-browser-android/issues/270), [#272](https://github.com/solardev-xyz/freedom-browser-android/issues/272)–[#280](https://github.com/solardev-xyz/freedom-browser-android/issues/280) and [#283](https://github.com/solardev-xyz/freedom-browser-android/issues/283) (with its follow-up [#284](https://github.com/solardev-xyz/freedom-browser-android/issues/284)) shipped. [#271](https://github.com/solardev-xyz/freedom-browser-android/issues/271) (browser profiles) was closed as not planned: the maintainers decided against profiles for now.
- **Desktop** has four new commits since the last pass's `32f8bbe`, all about linking Arti on macOS and the release smoke test; nothing user-facing. This pass reads its unreleased `changelog.d/` fragments and wallet sidebar more closely than the last one did, and that turned up gaps the last pass missed: scriptlets and uBlock Origin filters ([#318](https://github.com/solardev-xyz/freedom-browser-android/issues/318)), TLS client certificates ([#316](https://github.com/solardev-xyz/freedom-browser-android/issues/316)), `ethereum:` links ([#317](https://github.com/solardev-xyz/freedom-browser-android/issues/317)), single-account key export ([#323](https://github.com/solardev-xyz/freedom-browser-android/issues/323)) and the GitHub → Radicle bridge ([#324](https://github.com/solardev-xyz/freedom-browser-android/issues/324)).
- **iOS** is unchanged (`ee1024a` both times). Its column still differs a lot from the 26 Sep inventory, because it added private tabs, find in page, downloads, context menus, site permissions, app links, manifests, `.tez` and transaction history between the two.
- **Also new**: gaps in browser basics that have been there all along, compared with desktop (closing other tabs, opening a bookmark in a new tab, searching downloads), and polish (licences, translations). Ledger over USB was deferred when [#142](https://github.com/solardev-xyz/freedom-browser-android/issues/142) shipped Bluetooth and is now filed.

## New gap issues

| Issue | Gap | Desktop | iOS | Size |
|---|---|---|---|---|
| [#316](https://github.com/solardev-xyz/freedom-browser-android/issues/316) | TLS client certificates: let the user pick one when a site asks (today WebView cancels every request) | ✅ | ❌ | S |
| [#317](https://github.com/solardev-xyz/freedom-browser-android/issues/317) | Open EIP-681 `ethereum:` links in the Send page (today WebView's stock *ERR_UNKNOWN_URL_SCHEME* page) | ✅ | ❌ | S |
| [#318](https://github.com/solardev-xyz/freedom-browser-android/issues/318) | Ad blocking: scriptlets and uBlock Origin filters, which block YouTube video ads | ✅ | ❌ | L |
| [#319](https://github.com/solardev-xyz/freedom-browser-android/issues/319) | Ledger over USB-C (OTG), for the Nano S Plus and other devices without Bluetooth | ✅ | ❌ | M |
| [#320](https://github.com/solardev-xyz/freedom-browser-android/issues/320) | Tab switcher: Close all tabs and Close other tabs | 🟡 Close Other Tabs and Close Tabs to the Right, no Close all | ❌ | S |
| [#321](https://github.com/solardev-xyz/freedom-browser-android/issues/321) | Bookmarks and history: open an entry in a new or private tab | ✅ | ❌ | S |
| [#322](https://github.com/solardev-xyz/freedom-browser-android/issues/322) | Downloads: search the list, and optionally ask where to save each file | ✅ | ❌ | M |
| [#323](https://github.com/solardev-xyz/freedom-browser-android/issues/323) | Wallet: show one account's private key for export | ✅ | ❌ | S |
| [#324](https://github.com/solardev-xyz/freedom-browser-android/issues/324) | Radicle: import a GitHub repository (decide first; desktop uses the system `git`) | ✅ | ❌ | L |
| [#325](https://github.com/solardev-xyz/freedom-browser-android/issues/325) | About: open-source licences for every bundled component | ✅ (`NOTICES` in the package) | ❌ | S |
| [#326](https://github.com/solardev-xyz/freedom-browser-android/issues/326) | Ship the first translations and turn on the Language setting | ❌ | ❌ | M |

[#326](https://github.com/solardev-xyz/freedom-browser-android/issues/326) is Android polish, not parity: no platform ships a translation, and Android is the only one with its UI text in resources. [#324](https://github.com/solardev-xyz/freedom-browser-android/issues/324) starts with a decision, as [#271](https://github.com/solardev-xyz/freedom-browser-android/issues/271) did.

## Feature-by-feature comparison

✅ has it · 🟡 has part of it (the cell says what's missing) · ❌ doesn't have it · — doesn't apply to that platform. The last column links the issue that brought the feature to Android, the new issue for a gap, or the code for features Android had before the parity plan.

### Protocols and content

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| `bzz://`, 64- and 128-hex (encrypted) references | ✅ | ✅ | ✅ | [`VirtualOrigin.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/browser/VirtualOrigin.kt) |
| `ipfs://`, `ipns://` | ✅ | ✅ | ✅ | [`IpfsGateway.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/browser/IpfsGateway.kt) |
| `web3://` contract-hosted apps | ✅ | ✅ | ✅ | [#123](https://github.com/solardev-xyz/freedom-browser-android/issues/123) |
| `rad://` repository browser | ✅ | ✅ | ✅ | [#124](https://github.com/solardev-xyz/freedom-browser-android/issues/124) |
| `.onion` through an embedded Tor (Arti) | ✅ | ✅ | ❌ | [#143](https://github.com/solardev-xyz/freedom-browser-android/issues/143) |
| Tor through an external SOCKS proxy (Orbot) | ✅ | ✅ | ❌ | [#275](https://github.com/solardev-xyz/freedom-browser-android/issues/275) |
| External Swarm endpoint and IPFS gateway | ✅ | ✅ | ❌ | [#125](https://github.com/solardev-xyz/freedom-browser-android/issues/125) |
| IPFS load-progress indicator | ✅ | ✅ | ✅ | [#94](https://github.com/solardev-xyz/freedom-browser-android/issues/94) |
| Friendly error pages, including failed https loads and certificate errors | ✅ | 🟡 no certificate page | 🟡 dweb loads only | [#259](https://github.com/solardev-xyz/freedom-browser-android/issues/259) |
| Web pages can't use the Swarm node's local API; `bzz://` pages are read-only | ✅ | ✅ | 🟡 POST becomes GET; node API not guarded | [#283](https://github.com/solardev-xyz/freedom-browser-android/issues/283), [#284](https://github.com/solardev-xyz/freedom-browser-android/issues/284) |
| Swarm bootnodes over DNS TXT (DoH), with a shipped fallback | ✅ | ❌ | ✅ | [#74](https://github.com/solardev-xyz/freedom-browser-android/issues/74) |
| Import a GitHub repository into Radicle | ❌ | ✅ | ❌ | **new:** [#324](https://github.com/solardev-xyz/freedom-browser-android/issues/324) |

### Name resolution and trust

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| ENS through the Universal Resolver | ✅ | ✅ | ✅ | [`EnsResolver.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/ens/EnsResolver.kt) |
| CCIP-Read (EIP-3668), `.box` | ✅ | ✅ | ✅ | [`EnsHttp.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/ens/EnsHttp.kt) |
| WNS `.wei`, GNS `.gwei` | ✅ | ✅ | ✅ | [#95](https://github.com/solardev-xyz/freedom-browser-android/issues/95) |
| Tezos Domains `.tez` | ✅ | ✅ | ✅ | [#103](https://github.com/solardev-xyz/freedom-browser-android/issues/103) |
| M-of-K RPC quorum, and "servers disagreed" / "unverified" interstitials | ✅ | ✅ | ✅ | [#96](https://github.com/solardev-xyz/freedom-browser-android/issues/96) |
| Colibri-verified resolution | ✅ | ✅ | ✅ | [#100](https://github.com/solardev-xyz/freedom-browser-android/issues/100) |
| Myotis-verified resolution | ✅ | ✅ | ✅ | [#101](https://github.com/solardev-xyz/freedom-browser-android/issues/101) |
| Trust shield, transport-aware display (`ipfs://vitalik.eth`) | ✅ | ✅ | ✅ | [#97](https://github.com/solardev-xyz/freedom-browser-android/issues/97) |
| Typed scheme is an assertion (`bzz://name.eth` refuses an IPFS contenthash) | ✅ | ✅ | 🟡 only inside the scheme handlers | [#97](https://github.com/solardev-xyz/freedom-browser-android/issues/97) |
| ENSIP-15 normalisation of non-ASCII names | ✅ | ✅ | ❌ ASCII only | [#98](https://github.com/solardev-xyz/freedom-browser-android/issues/98) |
| Re-verify a name on Back and Forward | ✅ | ✅ | ✅ | [#99](https://github.com/solardev-xyz/freedom-browser-android/issues/99) |
| Name-resolution and RPC-provider settings | ✅ | ✅ | ✅ | [#102](https://github.com/solardev-xyz/freedom-browser-android/issues/102) |

### Identity and vault

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| One BIP-39 phrase drives the wallet and every node identity | ✅ | ✅ | 🟡 Radicle identity isn't derived | [#77](https://github.com/solardev-xyz/freedom-browser-android/issues/77) |
| Onboarding: create or import a phrase | ✅ | ✅ | ✅ | [#75](https://github.com/solardev-xyz/freedom-browser-android/issues/75) |
| Encrypted vault, biometric unlock, auto-lock | ✅ | ✅ Touch ID on macOS | ✅ | [#76](https://github.com/solardev-xyz/freedom-browser-android/issues/76) |
| Show and export the recovery phrase | ✅ | ✅ | ✅ | [#78](https://github.com/solardev-xyz/freedom-browser-android/issues/78) |
| Show one account's private key for export | ❌ | ✅ | ❌ | **new:** [#323](https://github.com/solardev-xyz/freedom-browser-android/issues/323) |
| End-to-end encrypted phrase backup (Google Block Store) | ✅ | ❌ | ❌ | [#231](https://github.com/solardev-xyz/freedom-browser-android/issues/231) |

### Wallet

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| Multiple accounts | ✅ | ✅ | ❌ | [#104](https://github.com/solardev-xyz/freedom-browser-android/issues/104) |
| Balances: native coin and built-in ERC-20 tokens | ✅ | ✅ | ✅ | [#104](https://github.com/solardev-xyz/freedom-browser-android/issues/104) |
| Send with gas, nonce and review | ✅ | ✅ | ✅ | [#105](https://github.com/solardev-xyz/freedom-browser-android/issues/105) |
| Send to ENS, WNS, GNS and DNS names | ✅ | ✅ | 🟡 ENS only | [#277](https://github.com/solardev-xyz/freedom-browser-android/issues/277) |
| Receive: address and QR code | ✅ | ✅ | ✅ | [#106](https://github.com/solardev-xyz/freedom-browser-android/issues/106) |
| QR scanner for addresses and EIP-681 requests | ✅ | ❌ | 🟡 OpenLV pairing only | [#106](https://github.com/solardev-xyz/freedom-browser-android/issues/106) |
| EIP-681 `ethereum:` links on a page open Send | ❌ stock error page | ✅ | ❌ | **new:** [#317](https://github.com/solardev-xyz/freedom-browser-android/issues/317) |
| Chains: catalog, custom chains, chainlist.org search | ✅ | ✅ | ✅ | [#107](https://github.com/solardev-xyz/freedom-browser-android/issues/107) |
| Per-chain RPC providers and the chain-data router | ✅ | ✅ | ✅ | [#108](https://github.com/solardev-xyz/freedom-browser-android/issues/108) |
| Transaction history | ✅ | ✅ | ✅ | [#109](https://github.com/solardev-xyz/freedom-browser-android/issues/109) |
| Ledger over Bluetooth | ✅ | ❌ | ❌ | [#142](https://github.com/solardev-xyz/freedom-browser-android/issues/142) |
| Ledger over USB | ❌ | ✅ | ❌ | **new:** [#319](https://github.com/solardev-xyz/freedom-browser-android/issues/319) |
| Safe multisig accounts | ✅ | ✅ | ❌ | [#141](https://github.com/solardev-xyz/freedom-browser-android/issues/141) |
| Phone signing over OpenLV, signer side | ✅ | — requester side | ✅ | [#113](https://github.com/solardev-xyz/freedom-browser-android/issues/113) |

### dApp provider APIs and permissions

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| `window.ethereum` with EIP-6963 and approval sheets | ✅ | ✅ | ✅ | [#110](https://github.com/solardev-xyz/freedom-browser-android/issues/110) |
| Per-origin permissions and a connected-sites manager | ✅ | ✅ | 🟡 active tab's site only | [#111](https://github.com/solardev-xyz/freedom-browser-android/issues/111) |
| Auto-approve rules per origin, contract, function and chain | ✅ | ✅ | ✅ | [#112](https://github.com/solardev-xyz/freedom-browser-android/issues/112) |
| `window.swarm`: publishing, chunks, feeds | ✅ | ✅ | ✅ | [#120](https://github.com/solardev-xyz/freedom-browser-android/issues/120) |
| `window.swarm` messaging: PSS, GSOC, subscriptions | ✅ | ✅ | ✅ | [#121](https://github.com/solardev-xyz/freedom-browser-android/issues/121) |
| Swarm-hosted permission manifests | ✅ | ✅ | ✅ | [#122](https://github.com/solardev-xyz/freedom-browser-android/issues/122) |
| `window.radicle` | ✅ | ✅ | ✅ | [#124](https://github.com/solardev-xyz/freedom-browser-android/issues/124) |
| x402 payments, per-site allowances, payment history | ✅ | ✅ | ❌ | [#140](https://github.com/solardev-xyz/freedom-browser-android/issues/140) |
| Wallet and providers off in private tabs | ✅ | ✅ | ✅ | [#86](https://github.com/solardev-xyz/freedom-browser-android/issues/86) |

### Swarm publishing and nodes

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| Node mode: ultra-light ↔ light, with a publish setup checklist | ✅ | ✅ | ✅ | [#114](https://github.com/solardev-xyz/freedom-browser-android/issues/114) |
| Fund the node and buy a stamp in one transaction | ✅ | ❌ | 🟡 funds only | [#115](https://github.com/solardev-xyz/freedom-browser-android/issues/115) |
| Postage stamps: list, estimate, buy, extend, detail | ✅ | ✅ | ✅ | [#116](https://github.com/solardev-xyz/freedom-browser-android/issues/116) |
| Chequebook deposit | ✅ | ✅ | 🟡 automatic only | [#117](https://github.com/solardev-xyz/freedom-browser-android/issues/117) |
| Publish page for files, folders and text, with history | ✅ | ✅ | 🟡 history only | [#118](https://github.com/solardev-xyz/freedom-browser-android/issues/118) |
| Publisher identities | ✅ | ✅ | 🟡 per-grant choice only | [#119](https://github.com/solardev-xyz/freedom-browser-android/issues/119) |
| Swarm node reads Gnosis through the verified chain-data router | ✅ | ✅ | ✅ | [#273](https://github.com/solardev-xyz/freedom-browser-android/issues/273) |
| Myotis: start and stop Ethereum and Gnosis separately | ✅ | ✅ | 🟡 one switch for both | [#274](https://github.com/solardev-xyz/freedom-browser-android/issues/274) |
| Node logs: view and share | ✅ | 🟡 log file only | 🟡 view only | [#276](https://github.com/solardev-xyz/freedom-browser-android/issues/276) |
| Live node status: peers, version | ✅ | ✅ | 🟡 peers only | [`NodeScreen.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/browser/NodeScreen.kt) |
| Per-node start and stop, and start on launch | ✅ | ✅ | 🟡 one Enable switch per node | [`NodeSettings.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/data/NodeSettings.kt) |
| Radicle node on/off and seeding | ✅ | ✅ | ✅ | [#124](https://github.com/solardev-xyz/freedom-browser-android/issues/124) |

### Browser core

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| Tabs and a tab switcher | ✅ | ✅ tab strip | ✅ | [`TabSwitcher.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/browser/TabSwitcher.kt) |
| Reopen closed tab, reorder tabs | ✅ | ✅ | 🟡 reopen only | [#90](https://github.com/solardev-xyz/freedom-browser-android/issues/90) |
| Close all tabs, close other tabs | ❌ | 🟡 close others and close to the right, no close all | ❌ | **new:** [#320](https://github.com/solardev-xyz/freedom-browser-android/issues/320) |
| Audio indicator and per-tab mute | ✅ | ✅ | ❌ | [#91](https://github.com/solardev-xyz/freedom-browser-android/issues/91) |
| `window.open` and `target=_blank` open a new tab | ✅ | ✅ | ✅ | [#82](https://github.com/solardev-xyz/freedom-browser-android/issues/82) |
| Pop-up blocker with per-site allow | ✅ | ✅ | ❌ | [#261](https://github.com/solardev-xyz/freedom-browser-android/issues/261) |
| History: search, grouped by day | ✅ | ✅ | ✅ | [#263](https://github.com/solardev-xyz/freedom-browser-android/issues/263) |
| Bookmarks: rename, edit the address, reorder | ✅ | ✅ | 🟡 delete only | [#264](https://github.com/solardev-xyz/freedom-browser-android/issues/264) |
| Open a bookmark or history entry in a new tab | ❌ | ✅ | ❌ | **new:** [#321](https://github.com/solardev-xyz/freedom-browser-android/issues/321) |
| Home page with bookmarks, recent pages and Explore; first-run introduction | ✅ | 🟡 welcome page | 🟡 no introduction | [#278](https://github.com/solardev-xyz/freedom-browser-android/issues/278) |
| Address-bar autocomplete from history and bookmarks | ✅ | ✅ | ✅ | [`UrlSuggestion.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/data/UrlSuggestion.kt) |
| Search engine choice, including a custom template | ✅ | ✅ | ✅ | [#87](https://github.com/solardev-xyz/freedom-browser-android/issues/87) |
| Find in page | ✅ | ✅ | ✅ | [#83](https://github.com/solardev-xyz/freedom-browser-android/issues/83) |
| Page zoom | ✅ | ✅ | ❌ | [#88](https://github.com/solardev-xyz/freedom-browser-android/issues/88) |
| Print | ✅ | ✅ | ❌ | [#89](https://github.com/solardev-xyz/freedom-browser-android/issues/89) |
| Share the page | ✅ | ❌ | ✅ | [`UrlActions.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/browser/UrlActions.kt) |
| Context menus for links, images and selected text | ✅ | ✅ | 🟡 no copy-image; images outside links get the default menu | [#84](https://github.com/solardev-xyz/freedom-browser-android/issues/84) |
| Download manager with pause and resume | ✅ | ✅ | ✅ | [#79](https://github.com/solardev-xyz/freedom-browser-android/issues/79), [#265](https://github.com/solardev-xyz/freedom-browser-android/issues/265) |
| Downloads: search the list, ask where to save | ❌ | ✅ | ❌ | **new:** [#322](https://github.com/solardev-xyz/freedom-browser-android/issues/322) |
| File upload (`<input type=file>`) | ✅ | ✅ | ✅ | [#80](https://github.com/solardev-xyz/freedom-browser-android/issues/80) |
| Site permissions: camera, microphone, location, MIDI, with remember and revoke | ✅ | ✅ | 🟡 camera, microphone, motion | [#81](https://github.com/solardev-xyz/freedom-browser-android/issues/81), [#267](https://github.com/solardev-xyz/freedom-browser-android/issues/267) |
| Three dismissed prompts block the site for the session | ✅ | ✅ | ✅ | [PR #154](https://github.com/solardev-xyz/freedom-browser-android/pull/154) |
| See and revoke this site's permissions from the page | ✅ | ✅ | ❌ | [#266](https://github.com/solardev-xyz/freedom-browser-android/issues/266) |
| Hand off `mailto:`, `tel:`, `magnet:` and other app links with per-site consent | ✅ | ✅ | ✅ | [#85](https://github.com/solardev-xyz/freedom-browser-android/issues/85) |
| Private tabs | ✅ | ✅ windows | ✅ | [#86](https://github.com/solardev-xyz/freedom-browser-android/issues/86) |
| Browser profiles | ❌ decided against | ✅ | ❌ | [#271](https://github.com/solardev-xyz/freedom-browser-android/issues/271) |
| Appearance: Light, Dark or System | ✅ | ✅ | 🟡 system only | [#269](https://github.com/solardev-xyz/freedom-browser-android/issues/269) |
| Chrome tinted with the page's theme colour | ✅ | ❌ | ✅ | [#92](https://github.com/solardev-xyz/freedom-browser-android/issues/92) |
| Reserve space for a site's own bottom navigation | ✅ | — | ✅ | [#66](https://github.com/solardev-xyz/freedom-browser-android/issues/66) |
| Compact chrome on scroll, pull to refresh | ✅ | — | ✅ | [`ScrollReveal.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/browser/ScrollReveal.kt) |
| Settings search | ✅ | ✅ | ❌ | [#93](https://github.com/solardev-xyz/freedom-browser-android/issues/93) |
| Tell the user about a newer release | ✅ notice | ✅ auto-update | — App Store | [#272](https://github.com/solardev-xyz/freedom-browser-android/issues/272) |
| Hard reload that bypasses the cache | ✅ | ✅ | ❌ | [#262](https://github.com/solardev-xyz/freedom-browser-android/issues/262) |
| Survive a renderer crash | ✅ | 🟡 logged only | ❌ | [#260](https://github.com/solardev-xyz/freedom-browser-android/issues/260) |
| Hardware keyboard shortcuts | ✅ | ✅ remappable | ❌ | [#270](https://github.com/solardev-xyz/freedom-browser-android/issues/270) |
| Open links and shared text from other apps; default browser | ✅ | 🟡 Linux scheme handlers only | ❌ | [#268](https://github.com/solardev-xyz/freedom-browser-android/issues/268) |
| TLS client certificates: pick one when a site asks | ❌ always cancelled | ✅ | ❌ | **new:** [#316](https://github.com/solardev-xyz/freedom-browser-android/issues/316) |

### Ad blocking

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| Network and cosmetic (element-hiding) blocking | ✅ | ✅ | ✅ | [#126](https://github.com/solardev-xyz/freedom-browser-android/issues/126) |
| Scriptlets and uBlock Origin filters (YouTube video ads) | ❌ | ✅ | ❌ | **new:** [#318](https://github.com/solardev-xyz/freedom-browser-android/issues/318) |
| Categories: ads, privacy, cookie notices, annoyances | ✅ | ✅ | ✅ | [#126](https://github.com/solardev-xyz/freedom-browser-android/issues/126) |
| Signed list updates over a Swarm feed | ✅ | ✅ | ✅ | [#127](https://github.com/solardev-xyz/freedom-browser-android/issues/127) |
| Per-site allowlist | ✅ | ✅ | ✅ | [#126](https://github.com/solardev-xyz/freedom-browser-android/issues/126) |

### Polish

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| TalkBack labels and roles, large-font layouts | ✅ | 🟡 few labels | 🟡 about 12 labels | [#279](https://github.com/solardev-xyz/freedom-browser-android/issues/279) |
| UI text in string resources, ready for translation | ✅ | ❌ | ❌ | [#280](https://github.com/solardev-xyz/freedom-browser-android/issues/280) |
| Shipped translations | ❌ | ❌ | ❌ | **new:** [#326](https://github.com/solardev-xyz/freedom-browser-android/issues/326) |
| Open-source licences and notices shipped with the app | ❌ | ✅ `NOTICES` in the package | ❌ | **new:** [#325](https://github.com/solardev-xyz/freedom-browser-android/issues/325) |
| Empty states for History, Bookmarks, Downloads | ✅ | ✅ | ✅ | [`HistoryBookmarksScreens.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/browser/HistoryBookmarksScreens.kt) |

## Checked and not filed

- **Clipboard read permission.** Desktop prompts for `clipboard-read`. Android WebView refuses `navigator.clipboard.readText()` with `NotAllowedError`, even after a tap, and reports the permission as `denied`, without asking the app (`onPermissionRequest` isn't called; checked on v0.6.13 in the emulator). There's no hook to build a prompt on. Pasting through the keyboard works as usual.
- **Web notifications.** Desktop has them; WebView has no Notifications API at all (`SitePermissions.kt` says so). iOS doesn't have them either.
- **Motion-sensor prompt.** iOS asks because WebKit gates `DeviceMotionEvent` behind `requestPermission()`. Android WebView delivers motion events without a permission, so there's nothing to ask.
- **User-added ERC-20 tokens.** Desktop has `tokens:add-token` in its main process, but no screen calls it; iOS has none. No platform lets a user add a token, so it's not a parity gap.
- **In-app transaction detail and explorer.** All three show a transaction's detail and link out to the chain's block explorer.
- **Pinned tabs.** Desktop pins tabs to its tab strip; a phone switcher has no strip. Left out of [#320](https://github.com/solardev-xyz/freedom-browser-android/issues/320).
- **Profiles and DRM.** Decided in [#271](https://github.com/solardev-xyz/freedom-browser-android/issues/271) (no profiles for now) and [#267](https://github.com/solardev-xyz/freedom-browser-android/issues/267) (protected media stays denied, with a notice).
- **Desktop's new security fragments** ([#420](https://github.com/solardev-xyz/freedom-browser/pull/420), [#431](https://github.com/solardev-xyz/freedom-browser/issues/431)–[#439](https://github.com/solardev-xyz/freedom-browser/issues/439)) harden Electron's own architecture: IPC sender checks, fuses, internal HTML pages, the renderer–main signing split and code-signing entitlements. Android's internal pages are Compose, not HTML, and its signing sheets are native. The two that do carry over are filed ([#316](https://github.com/solardev-xyz/freedom-browser-android/issues/316)) or already done (node API, [#283](https://github.com/solardev-xyz/freedom-browser-android/issues/283)).
- **Desktop-only by nature**: multiple windows, remappable shortcuts, tabs in the title bar, developer tools and View Page Source, the bookmarks bar, multi-instance profile launching, the Electron auto-updater, the "external nodes detected" prompt, the link-hover URL preview, and the requester half of phone signing.

## Where Android is ahead

These are in the table above; collected here because neither the desktop nor the iOS team may know about them: Ledger over Bluetooth ([#142](https://github.com/solardev-xyz/freedom-browser-android/issues/142)), encrypted phrase backup through Google Block Store ([#231](https://github.com/solardev-xyz/freedom-browser-android/issues/231)), funding the node and buying a stamp in one transaction ([#115](https://github.com/solardev-xyz/freedom-browser-android/issues/115), iOS funds only), the QR scanner for payments ([#106](https://github.com/solardev-xyz/freedom-browser-android/issues/106), desktop has none), Swarm bootnodes over DoH ([#74](https://github.com/solardev-xyz/freedom-browser-android/issues/74), desktop has none), theme-colour tint ([#92](https://github.com/solardev-xyz/freedom-browser-android/issues/92), desktop has none), full TalkBack and large-font support ([#279](https://github.com/solardev-xyz/freedom-browser-android/issues/279)), UI text ready for translation ([#280](https://github.com/solardev-xyz/freedom-browser-android/issues/280)), the default-browser role and "Search with Freedom" from other apps ([#268](https://github.com/solardev-xyz/freedom-browser-android/issues/268)), and x402 and Safe, which iOS doesn't have.
