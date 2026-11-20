# Parity gaps after desktop 0.8.8

*19 November 2026.* A fresh pass over what Android lacks, or does noticeably worse, compared with desktop 0.8.7, 0.8.8 and desktop's unreleased `main` ([#551](https://github.com/solardev-xyz/freedom-browser-android/issues/551)). Each gap is its own issue, labelled `parity`, in the same shape as before (What / Reference implementations / Done when / Depends on, with a size). Every gap issue this pass filed now has a merged PR; four larger items start with a decision and are still open (see below).

**Previous pass:** [30 September 2026, after v0.6.13](https://github.com/solardev-xyz/freedom-browser-android/blob/3d6611b5c013736ab548a7d9fe0dbe31fdfa4563/docs/parity-gaps.md) ([#315](https://github.com/solardev-xyz/freedom-browser-android/issues/315)), itself following the first post-parity pass ([#232](https://github.com/solardev-xyz/freedom-browser-android/issues/232)). Its gaps shipped in v0.6.14 to v0.6.28, except [#324](https://github.com/solardev-xyz/freedom-browser-android/issues/324) and [#326](https://github.com/solardev-xyz/freedom-browser-android/issues/326), which are carried over here.

## Sources

| Platform | Repository @ commit | Read |
|---|---|---|
| Desktop | [freedom-browser](https://github.com/solardev-xyz/freedom-browser) @ `e17c9d01` (main, 12 Nov 2026) | `CHANGELOG.md` 0.8.7 and 0.8.8 and the unreleased `changelog.d/` fragments, checked against `src/` |
| iOS | not re-read this pass | The iOS cells are carried over from the previous pass ([freedom-browser-ios](https://github.com/solardev-xyz/freedom-browser-ios) @ `ee1024a`, 29 Sep 2026). Rows new in this pass say "not checked" for iOS. |
| Android | this repo @ `a1dce478`, the [v0.6.31](https://github.com/solardev-xyz/freedom-browser-android/releases/tag/v0.6.31) release | Code, for every gap and every "already at parity" claim. The fixes for this pass's issues are on `main` up to `0db25fe1`, after v0.6.31, so they ship with the next release. |

## What changed since the last analysis

- **Android** went from v0.6.13 to v0.6.31. Nine of the last pass's eleven gaps shipped: TLS client certificates ([#316](https://github.com/solardev-xyz/freedom-browser-android/issues/316), [PR #333](https://github.com/solardev-xyz/freedom-browser-android/pull/333)), `ethereum:` links ([#317](https://github.com/solardev-xyz/freedom-browser-android/issues/317), [PR #331](https://github.com/solardev-xyz/freedom-browser-android/pull/331)), scriptlets and uBlock Origin filters ([#318](https://github.com/solardev-xyz/freedom-browser-android/issues/318), [PR #354](https://github.com/solardev-xyz/freedom-browser-android/pull/354)), Ledger over USB ([#319](https://github.com/solardev-xyz/freedom-browser-android/issues/319), [PR #350](https://github.com/solardev-xyz/freedom-browser-android/pull/350)), Close all and Close other tabs ([#320](https://github.com/solardev-xyz/freedom-browser-android/issues/320), [PR #332](https://github.com/solardev-xyz/freedom-browser-android/pull/332)), opening a bookmark or history entry in a new tab ([#321](https://github.com/solardev-xyz/freedom-browser-android/issues/321), [PR #330](https://github.com/solardev-xyz/freedom-browser-android/pull/330)), searching downloads and asking where to save ([#322](https://github.com/solardev-xyz/freedom-browser-android/issues/322), [PR #345](https://github.com/solardev-xyz/freedom-browser-android/pull/345)), single-account key export ([#323](https://github.com/solardev-xyz/freedom-browser-android/issues/323), [PR #334](https://github.com/solardev-xyz/freedom-browser-android/pull/334)) and open-source licences ([#325](https://github.com/solardev-xyz/freedom-browser-android/issues/325), [PR #344](https://github.com/solardev-xyz/freedom-browser-android/pull/344)). The GitHub to Radicle import ([#324](https://github.com/solardev-xyz/freedom-browser-android/issues/324)) and translations ([#326](https://github.com/solardev-xyz/freedom-browser-android/issues/326)) are still open.
- **Desktop** shipped 0.8.7 and 0.8.8 and has more unreleased on `main`. The big items are Freedom Agent, an AI assistant ([desktop #457](https://github.com/solardev-xyz/freedom-browser/pull/457)), and one-payment Swarm publishing ([desktop #459](https://github.com/solardev-xyz/freedom-browser/pull/459)). Around them came a wave of security hardening (Colibri's verified label, the vault lock, Swarm feed and Radicle read limits, dweb path escapes, Safe activation) and a set of smaller features: open without gateway, `swarm_readData`, page-signed chunks, ACT, the Swarm error page, browsing credit, the Myotis upgrade advisory, Radicle write prompts, Recently closed, tab shortcuts, and plain-word network sources.
- **iOS** wasn't re-read; its cells are as of 29 Sep.
- **This pass** filed seven security ports and thirteen features, plus two follow-ups: [#571](https://github.com/solardev-xyz/freedom-browser-android/issues/571), found in review of [PR #554](https://github.com/solardev-xyz/freedom-browser-android/pull/554), and the Colibri version bump [#572](https://github.com/solardev-xyz/freedom-browser-android/issues/572). All are merged. Four items start with a decision: Freedom Agent ([#550](https://github.com/solardev-xyz/freedom-browser-android/issues/550), now with a phase 1 plan in progress), one-payment publishing ([#549](https://github.com/solardev-xyz/freedom-browser-android/issues/549)), the GitHub to Radicle import ([#324](https://github.com/solardev-xyz/freedom-browser-android/issues/324)) and translations ([#326](https://github.com/solardev-xyz/freedom-browser-android/issues/326)).

## This pass's issues

### Security ports

| Issue | Gap | Desktop | Android PR | Size |
|---|---|---|---|---|
| [#529](https://github.com/solardev-xyz/freedom-browser-android/issues/529) | Colibri: stop labelling ENS answers verified when the contract code isn't proven | [desktop #1076](https://github.com/solardev-xyz/freedom-browser/issues/1076) | [PR #552](https://github.com/solardev-xyz/freedom-browser-android/pull/552) | S |
| [#530](https://github.com/solardev-xyz/freedom-browser-android/issues/530) | Colibri: don't mark `eth_call`, past-block reads or receipts as verified | [desktop #1046](https://github.com/solardev-xyz/freedom-browser/issues/1046), [desktop #1059](https://github.com/solardev-xyz/freedom-browser/issues/1059) | [PR #558](https://github.com/solardev-xyz/freedom-browser-android/pull/558) | S |
| [#531](https://github.com/solardev-xyz/freedom-browser-android/issues/531) | Vault lock: refuse a Swarm feed write that was queued before the lock | [desktop #932](https://github.com/solardev-xyz/freedom-browser/issues/932) | [PR #553](https://github.com/solardev-xyz/freedom-browser-android/pull/553) | S |
| [#532](https://github.com/solardev-xyz/freedom-browser-android/issues/532) | `window.swarm`: charge the feed read budget before downloading | [desktop #1051](https://github.com/solardev-xyz/freedom-browser/issues/1051) | [PR #559](https://github.com/solardev-xyz/freedom-browser-android/pull/559) | S |
| [#533](https://github.com/solardev-xyz/freedom-browser-android/issues/533) | Radicle reads: cancel on navigation, per-tab queue limit, 16 MiB cap | [desktop #947](https://github.com/solardev-xyz/freedom-browser/issues/947), [desktop #1050](https://github.com/solardev-xyz/freedom-browser/issues/1050), [desktop #1073](https://github.com/solardev-xyz/freedom-browser/issues/1073) | [PR #556](https://github.com/solardev-xyz/freedom-browser-android/pull/556) | S |
| [#534](https://github.com/solardev-xyz/freedom-browser-android/issues/534) | Dweb paths: refuse encoded dot segments before they reach the gateway | [desktop #814](https://github.com/solardev-xyz/freedom-browser/issues/814) | [PR #554](https://github.com/solardev-xyz/freedom-browser-android/pull/554) | S |
| [#535](https://github.com/solardev-xyz/freedom-browser-android/issues/535) | Safe: mark an account activated only from a verified answer | [desktop #1066](https://github.com/solardev-xyz/freedom-browser/issues/1066) | [PR #555](https://github.com/solardev-xyz/freedom-browser-android/pull/555) | S |

### Features

| Issue | Gap | Desktop | Android PR | Size |
|---|---|---|---|---|
| [#536](https://github.com/solardev-xyz/freedom-browser-android/issues/536) | Swarm error page: say why, fail fast on a missing page, retry with a countdown | [desktop #618](https://github.com/solardev-xyz/freedom-browser/issues/618) | [PR #557](https://github.com/solardev-xyz/freedom-browser-android/pull/557) | M |
| [#537](https://github.com/solardev-xyz/freedom-browser-android/issues/537) | Open without gateway: `eth.limo`, `eth.link` and `bzz.link` | [desktop #70](https://github.com/solardev-xyz/freedom-browser/issues/70) | [PR #567](https://github.com/solardev-xyz/freedom-browser-android/pull/567) | M |
| [#538](https://github.com/solardev-xyz/freedom-browser-android/issues/538) | `window.swarm`: `swarm_readData`, read a whole upload by reference | [desktop #722](https://github.com/solardev-xyz/freedom-browser/issues/722) | [PR #565](https://github.com/solardev-xyz/freedom-browser-android/pull/565) | S |
| [#539](https://github.com/solardev-xyz/freedom-browser-android/issues/539) | `window.swarm`: `publishSingleOwnerChunk` for site-signed chunks | [desktop #647](https://github.com/solardev-xyz/freedom-browser/issues/647) (unreleased) | [PR #570](https://github.com/solardev-xyz/freedom-browser-android/pull/570) | S |
| [#540](https://github.com/solardev-xyz/freedom-browser-android/issues/540) | `window.swarm`: `openAct`, open ACT-shared content with per-publisher consent | [desktop #648](https://github.com/solardev-xyz/freedom-browser/issues/648) (unreleased) | [PR #583](https://github.com/solardev-xyz/freedom-browser-android/pull/583) | M |
| [#541](https://github.com/solardev-xyz/freedom-browser-android/issues/541) | Nodes: show the Swarm node's ACT public key with a copy button | [desktop #1188](https://github.com/solardev-xyz/freedom-browser/issues/1188) (unreleased) | [PR #564](https://github.com/solardev-xyz/freedom-browser-android/pull/564) | S |
| [#542](https://github.com/solardev-xyz/freedom-browser-android/issues/542) | Myotis: warn when a network upgrade needs a newer Freedom | [desktop #586](https://github.com/solardev-xyz/freedom-browser/issues/586) | [PR #569](https://github.com/solardev-xyz/freedom-browser-android/pull/569) | S |
| [#543](https://github.com/solardev-xyz/freedom-browser-android/issues/543) | Browsing credit: recent spend and top-up in xDAI | [desktop #488](https://github.com/solardev-xyz/freedom-browser/issues/488) | [PR #566](https://github.com/solardev-xyz/freedom-browser-android/pull/566) | M |
| [#544](https://github.com/solardev-xyz/freedom-browser-android/issues/544) | Radicle: ask before each write a site publishes as you, with an optional daily always-allow | [desktop #940](https://github.com/solardev-xyz/freedom-browser/issues/940) | [PR #568](https://github.com/solardev-xyz/freedom-browser-android/pull/568) | M |
| [#545](https://github.com/solardev-xyz/freedom-browser-android/issues/545) | Home: feature the Swarm apps desktop ships as default bookmarks | [desktop #120](https://github.com/solardev-xyz/freedom-browser/issues/120) | [PR #562](https://github.com/solardev-xyz/freedom-browser-android/pull/562) | S |
| [#546](https://github.com/solardev-xyz/freedom-browser-android/issues/546) | Recently closed tabs: a list, not just reopen-last | [desktop #584](https://github.com/solardev-xyz/freedom-browser/issues/584) | [PR #560](https://github.com/solardev-xyz/freedom-browser-android/pull/560) | S |
| [#547](https://github.com/solardev-xyz/freedom-browser-android/issues/547) | Hardware keyboard: Ctrl+1 to Ctrl+9 for tabs, Ctrl+Enter to publish text | [desktop #717](https://github.com/solardev-xyz/freedom-browser/issues/717), [desktop #720](https://github.com/solardev-xyz/freedom-browser/issues/720) | [PR #561](https://github.com/solardev-xyz/freedom-browser-android/pull/561) | S |
| [#548](https://github.com/solardev-xyz/freedom-browser-android/issues/548) | Settings: name network sources in plain words, technical names under Advanced | [desktop #549](https://github.com/solardev-xyz/freedom-browser/pull/549) | [PR #563](https://github.com/solardev-xyz/freedom-browser-android/pull/563) | S |
| [#571](https://github.com/solardev-xyz/freedom-browser-android/issues/571) | Follow-up to [#534](https://github.com/solardev-xyz/freedom-browser-android/issues/534): `ipns://..` shows the refusal page, not "Content Unavailable" | none | [PR #580](https://github.com/solardev-xyz/freedom-browser-android/pull/580) | S |
| [#572](https://github.com/solardev-xyz/freedom-browser-android/issues/572) | Follow-up: Colibri `v3.0.0` to `v3.0.3`, the version desktop ships | 0.8.8 changelog | [PR #581](https://github.com/solardev-xyz/freedom-browser-android/pull/581) | S |

### Decide first (open)

| Issue | Gap | Desktop | Status | Size |
|---|---|---|---|---|
| [#550](https://github.com/solardev-xyz/freedom-browser-android/issues/550) | Freedom Agent, desktop's AI assistant (about 64k lines on desktop) | [desktop #457](https://github.com/solardev-xyz/freedom-browser/pull/457) | **In progress.** Decided 19 Nov: build phase 1, with name registration also as a standalone screen and dictation through the system recognizer first. Phase 1 issues (label `agent`): Settings > AI and providers [#573](https://github.com/solardev-xyz/freedom-browser-android/issues/573), the agent loop and chat [#574](https://github.com/solardev-xyz/freedom-browser-android/issues/574), browser tools with approvals [#575](https://github.com/solardev-xyz/freedom-browser-android/issues/575), ChatGPT and Meta Muse sign-in [#576](https://github.com/solardev-xyz/freedom-browser-android/issues/576), wallet requests and node status in agent tabs [#577](https://github.com/solardev-xyz/freedom-browser-android/issues/577), dictation [#578](https://github.com/solardev-xyz/freedom-browser-android/issues/578). Name registration: [#579](https://github.com/solardev-xyz/freedom-browser-android/issues/579). | XL |
| [#549](https://github.com/solardev-xyz/freedom-browser-android/issues/549) | One-payment Swarm publishing: pick a plan, pay once in xDAI | [desktop #459](https://github.com/solardev-xyz/freedom-browser/pull/459) | Open, needs a decision on the mobile flow | L |
| [#324](https://github.com/solardev-xyz/freedom-browser-android/issues/324) | Radicle: import a GitHub repository (desktop uses the system `git`) | ✅ | Open, from the previous pass | L |
| [#326](https://github.com/solardev-xyz/freedom-browser-android/issues/326) | Ship the first translations and turn on the Language setting | ❌ | Open, from the previous pass; Android polish, not parity | M |

## Feature-by-feature comparison

✅ has it · 🟡 has part of it (the cell says what's missing) · ❌ doesn't have it · — doesn't apply to that platform · not checked: that platform wasn't read for this row (iOS on rows new in this pass). The last column links the issue that brought the feature to Android and the PR that closed it, the open issue for a gap, or the code for features Android had before the parity plan. Rows this pass added or changed carry its issue and PR.

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
| Swarm error page says why, fails fast on a missing page, retries with a countdown and Stop | ✅ | ✅ | not checked | [#536](https://github.com/solardev-xyz/freedom-browser-android/issues/536), [PR #557](https://github.com/solardev-xyz/freedom-browser-android/pull/557) |
| Dweb paths that would leave the content root (raw or encoded `..`) are refused | ✅ | ✅ | not checked | [#534](https://github.com/solardev-xyz/freedom-browser-android/issues/534), [PR #554](https://github.com/solardev-xyz/freedom-browser-android/pull/554); [#571](https://github.com/solardev-xyz/freedom-browser-android/issues/571), [PR #580](https://github.com/solardev-xyz/freedom-browser-android/pull/580) |
| Web pages can't use the Swarm node's local API; `bzz://` pages are read-only | ✅ | ✅ | 🟡 POST becomes GET; node API not guarded | [#283](https://github.com/solardev-xyz/freedom-browser-android/issues/283), [#284](https://github.com/solardev-xyz/freedom-browser-android/issues/284) |
| Radicle reads: per-tab queue, cancelled on navigation and tab close, 16 MiB cap | ✅ | ✅ | not checked | [#533](https://github.com/solardev-xyz/freedom-browser-android/issues/533), [PR #556](https://github.com/solardev-xyz/freedom-browser-android/pull/556) |
| Swarm bootnodes over DNS TXT (DoH), with a shipped fallback | ✅ | ❌ | ✅ | [#74](https://github.com/solardev-xyz/freedom-browser-android/issues/74) |
| Import a GitHub repository into Radicle | ❌ | ✅ | ❌ | [#324](https://github.com/solardev-xyz/freedom-browser-android/issues/324) (open, decide first) |

### Name resolution and trust

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| ENS through the Universal Resolver | ✅ | ✅ | ✅ | [`EnsResolver.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/ens/EnsResolver.kt) |
| CCIP-Read (EIP-3668), `.box` | ✅ | ✅ | ✅ | [`EnsHttp.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/ens/EnsHttp.kt) |
| WNS `.wei`, GNS `.gwei` | ✅ | ✅ | ✅ | [#95](https://github.com/solardev-xyz/freedom-browser-android/issues/95) |
| Tezos Domains `.tez` | ✅ | ✅ | ✅ | [#103](https://github.com/solardev-xyz/freedom-browser-android/issues/103) |
| M-of-K RPC quorum, and "servers disagreed" / "unverified" interstitials | ✅ | ✅ | ✅ | [#96](https://github.com/solardev-xyz/freedom-browser-android/issues/96) |
| Colibri proofs for chain reads, labelled verified only where they hold: not for names, `eth_call`, reads at past blocks or a receipt for another hash | ✅ | ✅ | not checked (had Colibri name resolution on 29 Sep) | [#100](https://github.com/solardev-xyz/freedom-browser-android/issues/100); [#529](https://github.com/solardev-xyz/freedom-browser-android/issues/529), [PR #552](https://github.com/solardev-xyz/freedom-browser-android/pull/552); [#530](https://github.com/solardev-xyz/freedom-browser-android/issues/530), [PR #558](https://github.com/solardev-xyz/freedom-browser-android/pull/558); [#572](https://github.com/solardev-xyz/freedom-browser-android/issues/572), [PR #581](https://github.com/solardev-xyz/freedom-browser-android/pull/581) |
| Myotis-verified resolution | ✅ | ✅ | ✅ | [#101](https://github.com/solardev-xyz/freedom-browser-android/issues/101) |
| Trust shield, transport-aware display (`ipfs://vitalik.eth`) | ✅ | ✅ | ✅ | [#97](https://github.com/solardev-xyz/freedom-browser-android/issues/97) |
| Typed scheme is an assertion (`bzz://name.eth` refuses an IPFS contenthash) | ✅ | ✅ | 🟡 only inside the scheme handlers | [#97](https://github.com/solardev-xyz/freedom-browser-android/issues/97) |
| ENSIP-15 normalisation of non-ASCII names | ✅ | ✅ | ❌ ASCII only | [#98](https://github.com/solardev-xyz/freedom-browser-android/issues/98) |
| Re-verify a name on Back and Forward | ✅ | ✅ | ✅ | [#99](https://github.com/solardev-xyz/freedom-browser-android/issues/99) |
| Name-resolution and RPC-provider settings | ✅ | ✅ | ✅ | [#102](https://github.com/solardev-xyz/freedom-browser-android/issues/102) |
| Network sources named in plain words, technical names under Advanced | ✅ | ✅ | not checked | [#548](https://github.com/solardev-xyz/freedom-browser-android/issues/548), [PR #563](https://github.com/solardev-xyz/freedom-browser-android/pull/563) |
| Open without gateway: offer `name.eth` for an `eth.limo` / `eth.link` page and `bzz://` for `bzz.link`, optionally always | ✅ | ✅ | not checked | [#537](https://github.com/solardev-xyz/freedom-browser-android/issues/537), [PR #567](https://github.com/solardev-xyz/freedom-browser-android/pull/567) |
| Register and renew `.eth`, `.wei` and `.gwei` names | ❌ in progress | 🟡 only through Freedom Agent | not checked | [#579](https://github.com/solardev-xyz/freedom-browser-android/issues/579) (in progress) |

### Identity and vault

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| One BIP-39 phrase drives the wallet and every node identity | ✅ | ✅ | 🟡 Radicle identity isn't derived | [#77](https://github.com/solardev-xyz/freedom-browser-android/issues/77) |
| Onboarding: create or import a phrase | ✅ | ✅ | ✅ | [#75](https://github.com/solardev-xyz/freedom-browser-android/issues/75) |
| Encrypted vault, biometric unlock, auto-lock | ✅ | ✅ Touch ID on macOS | ✅ | [#76](https://github.com/solardev-xyz/freedom-browser-android/issues/76) |
| Locking the vault refuses a Swarm feed write queued before the lock | ✅ | ✅ | not checked | [#531](https://github.com/solardev-xyz/freedom-browser-android/issues/531), [PR #553](https://github.com/solardev-xyz/freedom-browser-android/pull/553) |
| Show and export the recovery phrase | ✅ | ✅ | ✅ | [#78](https://github.com/solardev-xyz/freedom-browser-android/issues/78) |
| Show one account's private key for export | ✅ | ✅ | ❌ | [#323](https://github.com/solardev-xyz/freedom-browser-android/issues/323), [PR #334](https://github.com/solardev-xyz/freedom-browser-android/pull/334) |
| End-to-end encrypted phrase backup (Google Block Store) | ✅ | ❌ | ❌ | [#231](https://github.com/solardev-xyz/freedom-browser-android/issues/231) |

### Wallet

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| Multiple accounts | ✅ | ✅ | ❌ | [#104](https://github.com/solardev-xyz/freedom-browser-android/issues/104) |
| Balances: native coin and built-in ERC-20 tokens | ✅ | ✅ | ✅ | [#104](https://github.com/solardev-xyz/freedom-browser-android/issues/104) |
| Values in euros or dollars (opt-in) | ✅ | ❌ | not checked | [#439](https://github.com/solardev-xyz/freedom-browser-android/issues/439), [PR #449](https://github.com/solardev-xyz/freedom-browser-android/pull/449) |
| Send with gas, nonce and review | ✅ | ✅ | ✅ | [#105](https://github.com/solardev-xyz/freedom-browser-android/issues/105) |
| Send to ENS, WNS, GNS and DNS names | ✅ | ✅ | 🟡 ENS only | [#277](https://github.com/solardev-xyz/freedom-browser-android/issues/277) |
| Receive: address and QR code | ✅ | ✅ | ✅ | [#106](https://github.com/solardev-xyz/freedom-browser-android/issues/106) |
| QR scanner for addresses and EIP-681 requests | ✅ | ❌ | 🟡 OpenLV pairing only | [#106](https://github.com/solardev-xyz/freedom-browser-android/issues/106) |
| EIP-681 `ethereum:` links on a page open Send | ✅ | ✅ | ❌ | [#317](https://github.com/solardev-xyz/freedom-browser-android/issues/317), [PR #331](https://github.com/solardev-xyz/freedom-browser-android/pull/331) |
| Chains: catalog, custom chains, chainlist.org search | ✅ | ✅ | ✅ | [#107](https://github.com/solardev-xyz/freedom-browser-android/issues/107) |
| Per-chain RPC providers and the chain-data router | ✅ | ✅ | ✅ | [#108](https://github.com/solardev-xyz/freedom-browser-android/issues/108) |
| Transaction history | ✅ | ✅ | ✅ | [#109](https://github.com/solardev-xyz/freedom-browser-android/issues/109) |
| Ledger over Bluetooth | ✅ | ❌ | ❌ | [#142](https://github.com/solardev-xyz/freedom-browser-android/issues/142) |
| Ledger over USB | ✅ | ✅ | ❌ | [#319](https://github.com/solardev-xyz/freedom-browser-android/issues/319), [PR #350](https://github.com/solardev-xyz/freedom-browser-android/pull/350) |
| Safe multisig accounts | ✅ | ✅ | ❌ | [#141](https://github.com/solardev-xyz/freedom-browser-android/issues/141) |
| A Safe counts as activated only from a verified code read | ✅ | ✅ | not checked | [#535](https://github.com/solardev-xyz/freedom-browser-android/issues/535), [PR #555](https://github.com/solardev-xyz/freedom-browser-android/pull/555) |
| Phone signing over OpenLV, signer side | ✅ | — requester side | ✅ | [#113](https://github.com/solardev-xyz/freedom-browser-android/issues/113) |

### dApp provider APIs and permissions

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| `window.ethereum` with EIP-6963 and approval sheets | ✅ | ✅ | ✅ | [#110](https://github.com/solardev-xyz/freedom-browser-android/issues/110) |
| Per-origin permissions and a connected-sites manager | ✅ | ✅ | 🟡 active tab's site only | [#111](https://github.com/solardev-xyz/freedom-browser-android/issues/111) |
| Auto-approve rules per origin, contract, function and chain | ✅ | ✅ | ✅ | [#112](https://github.com/solardev-xyz/freedom-browser-android/issues/112) |
| `window.swarm`: publishing, chunks, feeds | ✅ | ✅ | ✅ | [#120](https://github.com/solardev-xyz/freedom-browser-android/issues/120) |
| `window.swarm`: a feed read is charged to the read budget before it downloads | ✅ | ✅ | not checked | [#532](https://github.com/solardev-xyz/freedom-browser-android/issues/532), [PR #559](https://github.com/solardev-xyz/freedom-browser-android/pull/559) |
| `window.swarm`: `swarm_readData`, read a whole upload by reference | ✅ | ✅ | not checked | [#538](https://github.com/solardev-xyz/freedom-browser-android/issues/538), [PR #565](https://github.com/solardev-xyz/freedom-browser-android/pull/565) |
| `window.swarm`: `publishSingleOwnerChunk`, publish a chunk the site signed itself | ✅ every call asks, no always-allow | ✅ unreleased | not checked | [#539](https://github.com/solardev-xyz/freedom-browser-android/issues/539), [PR #570](https://github.com/solardev-xyz/freedom-browser-android/pull/570) |
| `window.swarm`: `openAct`, open ACT-shared content with per-publisher consent | ✅ | ✅ unreleased | not checked | [#540](https://github.com/solardev-xyz/freedom-browser-android/issues/540), [PR #583](https://github.com/solardev-xyz/freedom-browser-android/pull/583) |
| `window.swarm` messaging: PSS, GSOC, subscriptions | ✅ | ✅ | ✅ | [#121](https://github.com/solardev-xyz/freedom-browser-android/issues/121) |
| Swarm-hosted permission manifests | ✅ | ✅ | ✅ | [#122](https://github.com/solardev-xyz/freedom-browser-android/issues/122) |
| `window.radicle` | ✅ | ✅ | ✅ | [#124](https://github.com/solardev-xyz/freedom-browser-android/issues/124) |
| `window.radicle`: ask before each write, optional always-allow capped at 50 a day | ✅ | ✅ | not checked | [#544](https://github.com/solardev-xyz/freedom-browser-android/issues/544), [PR #568](https://github.com/solardev-xyz/freedom-browser-android/pull/568) |
| x402 payments, per-site allowances, payment history | ✅ | ✅ | ❌ | [#140](https://github.com/solardev-xyz/freedom-browser-android/issues/140) |
| Wallet and providers off in private tabs | ✅ | ✅ | ✅ | [#86](https://github.com/solardev-xyz/freedom-browser-android/issues/86) |

### Swarm publishing and nodes

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| Node mode: ultra-light ↔ light, with a publish setup checklist | ✅ | ✅ | ✅ | [#114](https://github.com/solardev-xyz/freedom-browser-android/issues/114) |
| One-payment publishing: pick a plan, pay once in xDAI, no setup checklist | ❌ five-step checklist | ✅ | not checked | [#549](https://github.com/solardev-xyz/freedom-browser-android/issues/549) (open, decide first) |
| Fund the node and buy a stamp in one transaction | ✅ | ❌ | 🟡 funds only | [#115](https://github.com/solardev-xyz/freedom-browser-android/issues/115) |
| Postage stamps: list, estimate, buy, extend, detail | ✅ | ✅ | ✅ | [#116](https://github.com/solardev-xyz/freedom-browser-android/issues/116) |
| Chequebook deposit | ✅ | ✅ | 🟡 automatic only | [#117](https://github.com/solardev-xyz/freedom-browser-android/issues/117) |
| Browsing credit: recent spend (24 hours, 7 days) and top-up in xDAI | ✅ | ✅ | not checked | [#543](https://github.com/solardev-xyz/freedom-browser-android/issues/543), [PR #566](https://github.com/solardev-xyz/freedom-browser-android/pull/566) |
| Publish page for files, folders and text, with history | ✅ | ✅ | 🟡 history only | [#118](https://github.com/solardev-xyz/freedom-browser-android/issues/118) |
| Publisher identities | ✅ | ✅ | 🟡 per-grant choice only | [#119](https://github.com/solardev-xyz/freedom-browser-android/issues/119) |
| The Swarm node's ACT public key, with Copy | ✅ | ✅ unreleased | not checked | [#541](https://github.com/solardev-xyz/freedom-browser-android/issues/541), [PR #564](https://github.com/solardev-xyz/freedom-browser-android/pull/564) |
| Swarm node reads Gnosis through the verified chain-data router | ✅ | ✅ | ✅ | [#273](https://github.com/solardev-xyz/freedom-browser-android/issues/273) |
| Myotis: start and stop Ethereum and Gnosis separately | ✅ | ✅ | 🟡 one switch for both | [#274](https://github.com/solardev-xyz/freedom-browser-android/issues/274) |
| Myotis: warn when a network upgrade needs a newer Freedom | ✅ | ✅ | not checked | [#542](https://github.com/solardev-xyz/freedom-browser-android/issues/542), [PR #569](https://github.com/solardev-xyz/freedom-browser-android/pull/569) |
| Node logs: view and share | ✅ | 🟡 log file only | 🟡 view only | [#276](https://github.com/solardev-xyz/freedom-browser-android/issues/276) |
| Live node status: peers, version | ✅ | ✅ | 🟡 peers only | [`NodeScreen.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/browser/NodeScreen.kt) |
| Per-node start and stop, and start on launch | ✅ | ✅ | 🟡 one Enable switch per node | [`NodeSettings.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/data/NodeSettings.kt) |
| Radicle node on/off and seeding | ✅ | ✅ | ✅ | [#124](https://github.com/solardev-xyz/freedom-browser-android/issues/124) |

### Browser core

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| Tabs and a tab switcher | ✅ | ✅ tab strip | ✅ | [`TabSwitcher.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/browser/TabSwitcher.kt) |
| Reopen closed tab, reorder tabs | ✅ | ✅ | 🟡 reopen only | [#90](https://github.com/solardev-xyz/freedom-browser-android/issues/90) |
| Recently closed list that reopens any entry | ✅ in the tab switcher | ✅ History menu | not checked | [#546](https://github.com/solardev-xyz/freedom-browser-android/issues/546), [PR #560](https://github.com/solardev-xyz/freedom-browser-android/pull/560) |
| Close all tabs, close other tabs | ✅ | 🟡 close others and close to the right, no close all | ❌ | [#320](https://github.com/solardev-xyz/freedom-browser-android/issues/320), [PR #332](https://github.com/solardev-xyz/freedom-browser-android/pull/332) |
| Audio indicator and per-tab mute | ✅ | ✅ | ❌ | [#91](https://github.com/solardev-xyz/freedom-browser-android/issues/91) |
| `window.open` and `target=_blank` open a new tab | ✅ | ✅ | ✅ | [#82](https://github.com/solardev-xyz/freedom-browser-android/issues/82) |
| Pop-up blocker with per-site allow | ✅ | ✅ | ❌ | [#261](https://github.com/solardev-xyz/freedom-browser-android/issues/261) |
| History: search, grouped by day | ✅ | ✅ | ✅ | [#263](https://github.com/solardev-xyz/freedom-browser-android/issues/263) |
| Bookmarks: rename, edit the address, reorder | ✅ | ✅ | 🟡 delete only | [#264](https://github.com/solardev-xyz/freedom-browser-android/issues/264) |
| Open a bookmark or history entry in a new tab | ✅ | ✅ | ❌ | [#321](https://github.com/solardev-xyz/freedom-browser-android/issues/321), [PR #330](https://github.com/solardev-xyz/freedom-browser-android/pull/330) |
| Home page with bookmarks, recent pages and Explore; first-run introduction | ✅ | 🟡 welcome page | 🟡 no introduction | [#278](https://github.com/solardev-xyz/freedom-browser-android/issues/278) |
| Explore features desktop's default Swarm apps (FreeMap, swarm.eth, Volume Manager and others) | ✅ | ✅ default bookmarks | not checked | [#545](https://github.com/solardev-xyz/freedom-browser-android/issues/545), [PR #562](https://github.com/solardev-xyz/freedom-browser-android/pull/562) |
| Address-bar autocomplete from history and bookmarks | ✅ | ✅ | ✅ | [`UrlSuggestion.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/data/UrlSuggestion.kt) |
| Search-engine suggestions in the address bar (opt-in) | ✅ | ❌ | not checked | [#443](https://github.com/solardev-xyz/freedom-browser-android/issues/443), [PR #454](https://github.com/solardev-xyz/freedom-browser-android/pull/454) |
| Search engine choice, including a custom template | ✅ | ✅ | ✅ | [#87](https://github.com/solardev-xyz/freedom-browser-android/issues/87) |
| Find in page | ✅ | ✅ | ✅ | [#83](https://github.com/solardev-xyz/freedom-browser-android/issues/83) |
| Page zoom | ✅ | ✅ | ❌ | [#88](https://github.com/solardev-xyz/freedom-browser-android/issues/88) |
| Print | ✅ | ✅ | ❌ | [#89](https://github.com/solardev-xyz/freedom-browser-android/issues/89) |
| Share the page | ✅ | ❌ | ✅ | [`UrlActions.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/browser/UrlActions.kt) |
| Page info sheet from the address-bar badge | ✅ | ❌ | not checked | [#442](https://github.com/solardev-xyz/freedom-browser-android/issues/442), [PR #450](https://github.com/solardev-xyz/freedom-browser-android/pull/450) |
| Add a page to the Home screen | ✅ | ❌ | not checked | [PR #401](https://github.com/solardev-xyz/freedom-browser-android/pull/401) |
| Context menus for links, images and selected text | ✅ | ✅ | 🟡 no copy-image; images outside links get the default menu | [#84](https://github.com/solardev-xyz/freedom-browser-android/issues/84) |
| Download manager with pause and resume | ✅ | ✅ | ✅ | [#79](https://github.com/solardev-xyz/freedom-browser-android/issues/79), [#265](https://github.com/solardev-xyz/freedom-browser-android/issues/265) |
| Downloads: search the list, ask where to save | ✅ | ✅ | ❌ | [#322](https://github.com/solardev-xyz/freedom-browser-android/issues/322), [PR #345](https://github.com/solardev-xyz/freedom-browser-android/pull/345) |
| File upload (`<input type=file>`) | ✅ | ✅ | ✅ | [#80](https://github.com/solardev-xyz/freedom-browser-android/issues/80) |
| Site permissions: camera, microphone, location, MIDI, with remember and revoke | ✅ | ✅ | 🟡 camera, microphone, motion | [#81](https://github.com/solardev-xyz/freedom-browser-android/issues/81), [#267](https://github.com/solardev-xyz/freedom-browser-android/issues/267) |
| Three dismissed prompts block the site for the session | ✅ | ✅ | ✅ | [PR #154](https://github.com/solardev-xyz/freedom-browser-android/pull/154) |
| See and revoke this site's permissions from the page | ✅ | ✅ | ❌ | [#266](https://github.com/solardev-xyz/freedom-browser-android/issues/266) |
| Hand off `mailto:`, `tel:`, `magnet:` and other app links with per-site consent | ✅ | ✅ | ✅ | [#85](https://github.com/solardev-xyz/freedom-browser-android/issues/85) |
| Passkeys (WebAuthn) in normal tabs | ✅ | ❌ | not checked | [#524](https://github.com/solardev-xyz/freedom-browser-android/issues/524), [PR #526](https://github.com/solardev-xyz/freedom-browser-android/pull/526) |
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
| Ctrl+1 to Ctrl+8 select a tab, Ctrl+9 the last; Ctrl+Enter publishes text | ✅ | ✅ | not checked | [#547](https://github.com/solardev-xyz/freedom-browser-android/issues/547), [PR #561](https://github.com/solardev-xyz/freedom-browser-android/pull/561) |
| Open links and shared text from other apps; default browser | ✅ | 🟡 Linux scheme handlers only | ❌ | [#268](https://github.com/solardev-xyz/freedom-browser-android/issues/268) |
| TLS client certificates: pick one when a site asks | ✅ | ✅ | ❌ | [#316](https://github.com/solardev-xyz/freedom-browser-android/issues/316), [PR #333](https://github.com/solardev-xyz/freedom-browser-android/pull/333) |
| AI assistant (Freedom Agent) | ❌ phase 1 in progress | ✅ off by default | not checked | [#550](https://github.com/solardev-xyz/freedom-browser-android/issues/550); phase 1 [#573](https://github.com/solardev-xyz/freedom-browser-android/issues/573) to [#578](https://github.com/solardev-xyz/freedom-browser-android/issues/578) (in progress) |

### Ad blocking

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| Network and cosmetic (element-hiding) blocking | ✅ | ✅ | ✅ | [#126](https://github.com/solardev-xyz/freedom-browser-android/issues/126) |
| Scriptlets and uBlock Origin filters (YouTube video ads) | ✅ | ✅ | ❌ | [#318](https://github.com/solardev-xyz/freedom-browser-android/issues/318), [PR #354](https://github.com/solardev-xyz/freedom-browser-android/pull/354) |
| Categories: ads, privacy, cookie notices, annoyances | ✅ | ✅ | ✅ | [#126](https://github.com/solardev-xyz/freedom-browser-android/issues/126) |
| Signed list updates over a Swarm feed | ✅ | ✅ | ✅ | [#127](https://github.com/solardev-xyz/freedom-browser-android/issues/127) |
| Per-site allowlist | ✅ | ✅ | ✅ | [#126](https://github.com/solardev-xyz/freedom-browser-android/issues/126) |

### Polish

| Feature | Android | Desktop | iOS | Android issue or code |
|---|---|---|---|---|
| TalkBack labels and roles, large-font layouts | ✅ | 🟡 few labels | 🟡 about 12 labels | [#279](https://github.com/solardev-xyz/freedom-browser-android/issues/279) |
| UI text in string resources, ready for translation | ✅ | ❌ | ❌ | [#280](https://github.com/solardev-xyz/freedom-browser-android/issues/280) |
| Shipped translations | ❌ | ❌ | ❌ | [#326](https://github.com/solardev-xyz/freedom-browser-android/issues/326) (open) |
| Open-source licences and notices shipped with the app | ✅ | ✅ `NOTICES` in the package | ❌ | [#325](https://github.com/solardev-xyz/freedom-browser-android/issues/325), [PR #344](https://github.com/solardev-xyz/freedom-browser-android/pull/344) |
| Empty states for History, Bookmarks, Downloads | ✅ | ✅ | ✅ | [`HistoryBookmarksScreens.kt`](https://github.com/solardev-xyz/freedom-browser-android/blob/main/app/src/main/java/baby/freedom/mobile/browser/HistoryBookmarksScreens.kt) |

## Checked and not filed

### Already at parity

Desktop changes this pass read and found Android already matching, from Android's code:

- **Background tabs and prompts.** A dApp in a background tab can't act as the visible site, and prompts name the requester ([desktop #764](https://github.com/solardev-xyz/freedom-browser/issues/764)); a background tab can't load a dweb page into the visible tab ([desktop #969](https://github.com/solardev-xyz/freedom-browser/issues/969)); a prompt's request can't be swapped ([desktop #807](https://github.com/solardev-xyz/freedom-browser/issues/807)); a new page during a connect prompt ([desktop #808](https://github.com/solardev-xyz/freedom-browser/issues/808)); the page's chain and `chainChanged` reach every tab ([desktop #935](https://github.com/solardev-xyz/freedom-browser/issues/935)).
- **RPC quorum** counts distinct endpoints and providers ([desktop #1092](https://github.com/solardev-xyz/freedom-browser/issues/1092), [desktop #1094](https://github.com/solardev-xyz/freedom-browser/issues/1094)); Android is stricter, counting by registrable domain. Endpoint quarantine ([desktop #811](https://github.com/solardev-xyz/freedom-browser/issues/811)) and a lone second wave ([desktop #813](https://github.com/solardev-xyz/freedom-browser/issues/813)) match.
- **Colibri** never proves locally ([desktop #1059](https://github.com/solardev-xyz/freedom-browser/issues/1059)), and its store is private ([desktop #1058](https://github.com/solardev-xyz/freedom-browser/issues/1058)); Android keeps nothing per contract on disk.
- **Ledger:** signature check, address from the device, typed-data limits ([desktop #1063](https://github.com/solardev-xyz/freedom-browser/issues/1063), [desktop #1064](https://github.com/solardev-xyz/freedom-browser/issues/1064), [desktop #1065](https://github.com/solardev-xyz/freedom-browser/issues/1065)).
- **Browser basics:** a plain Chrome User-Agent ([desktop #471](https://github.com/solardev-xyz/freedom-browser/issues/471), WebView's default), `.onion` fails closed after a Tor restart ([desktop #809](https://github.com/solardev-xyz/freedom-browser/issues/809)), `window.close` closes the tab ([desktop #580](https://github.com/solardev-xyz/freedom-browser/issues/580)), fees include the tip ([desktop #749](https://github.com/solardev-xyz/freedom-browser/issues/749)), a pending transaction doesn't break reads ([desktop #750](https://github.com/solardev-xyz/freedom-browser/issues/750)).
- **Also matching:** offchain ENS through `.onion` CCIP gateways over Tor ([desktop #359](https://github.com/solardev-xyz/freedom-browser/issues/359)), providers injected at document start ([desktop #683](https://github.com/solardev-xyz/freedom-browser/issues/683)), Swarm live streams ([desktop #727](https://github.com/solardev-xyz/freedom-browser/pull/727), with ant 0.5.70 or later), a dweb address is never sent to the search engine ([desktop #621](https://github.com/solardev-xyz/freedom-browser/issues/621)), Swarm cache size and clear ([desktop #579](https://github.com/solardev-xyz/freedom-browser/issues/579)), the pop-up blocker ([desktop #442](https://github.com/solardev-xyz/freedom-browser/issues/442)), TLS client certificates ([desktop #437](https://github.com/solardev-xyz/freedom-browser/issues/437)), uBlock scriptlets and YouTube ([desktop #410](https://github.com/solardev-xyz/freedom-browser/issues/410)), and no `window.radicle` in private tabs ([desktop #953](https://github.com/solardev-xyz/freedom-browser/issues/953)).

### Not applicable on Android

- **Vault password back-off** ([desktop #960](https://github.com/solardev-xyz/freedom-browser/issues/960)): Android's vault unlocks with biometrics or the screen lock, which the OS throttles.
- **Look-alike primary name on Send** ([desktop #756](https://github.com/solardev-xyz/freedom-browser/pull/756)): Android doesn't reverse-resolve the recipient.
- **DNS prefetch bypassing the proxy** (part of [desktop #457](https://github.com/solardev-xyz/freedom-browser/pull/457)): only `.onion` goes through a proxy on Android, and it fails closed.
- **Key file modes, keystore password, RPC key file** ([desktop #942](https://github.com/solardev-xyz/freedom-browser/issues/942), [desktop #944](https://github.com/solardev-xyz/freedom-browser/issues/944)): the app sandbox and the Android Keystore cover these.
- **Phone account check** ([desktop #1078](https://github.com/solardev-xyz/freedom-browser/issues/1078)): Android is the signer side of phone signing, not the requester.
- **File System Access save picker** ([desktop #671](https://github.com/solardev-xyz/freedom-browser/issues/671)): not in WebView.
- **Update progress** ([desktop #87](https://github.com/solardev-xyz/freedom-browser/issues/87)): there's no in-app updater. **Full-screen chrome** ([desktop #78](https://github.com/solardev-xyz/freedom-browser/issues/78)) is a desktop window feature.
- **Desktop-only by nature**, as in the previous pass: remappable shortcuts, profiles ([#271](https://github.com/solardev-xyz/freedom-browser-android/issues/271), decided against), multiple windows and developer tools.

The previous pass's list (clipboard reads, web notifications, the motion-sensor prompt, user-added tokens, pinned tabs, DRM, Electron's own hardening) still holds; see the [30 Sep version](https://github.com/solardev-xyz/freedom-browser-android/blob/3d6611b5c013736ab548a7d9fe0dbe31fdfa4563/docs/parity-gaps.md#checked-and-not-filed).

## Where Android is ahead

These are in the table above; collected here because neither the desktop nor the iOS team may know about them. Desktop was checked for each this pass; iOS only for those from the previous pass.

- **New in this list:** passkeys (WebAuthn) in normal tabs ([#524](https://github.com/solardev-xyz/freedom-browser-android/issues/524), [PR #526](https://github.com/solardev-xyz/freedom-browser-android/pull/526)), Add to Home screen ([PR #401](https://github.com/solardev-xyz/freedom-browser-android/pull/401)), wallet values in euros or dollars ([#439](https://github.com/solardev-xyz/freedom-browser-android/issues/439), [PR #449](https://github.com/solardev-xyz/freedom-browser-android/pull/449)), the page info sheet ([#442](https://github.com/solardev-xyz/freedom-browser-android/issues/442), [PR #450](https://github.com/solardev-xyz/freedom-browser-android/pull/450)), search-engine suggestions in the address bar ([#443](https://github.com/solardev-xyz/freedom-browser-android/issues/443), [PR #454](https://github.com/solardev-xyz/freedom-browser-android/pull/454)), and per-site desktop mode ([#180](https://github.com/solardev-xyz/freedom-browser-android/issues/180), [PR #186](https://github.com/solardev-xyz/freedom-browser-android/pull/186)).
- **From the previous pass, still ahead:** Ledger over Bluetooth ([#142](https://github.com/solardev-xyz/freedom-browser-android/issues/142)), encrypted phrase backup through Google Block Store ([#231](https://github.com/solardev-xyz/freedom-browser-android/issues/231)), funding the node and buying a stamp in one transaction ([#115](https://github.com/solardev-xyz/freedom-browser-android/issues/115), iOS funds only), the QR scanner for payments ([#106](https://github.com/solardev-xyz/freedom-browser-android/issues/106)), Swarm bootnodes over DoH ([#74](https://github.com/solardev-xyz/freedom-browser-android/issues/74)), theme-colour tint ([#92](https://github.com/solardev-xyz/freedom-browser-android/issues/92)), full TalkBack and large-font support ([#279](https://github.com/solardev-xyz/freedom-browser-android/issues/279)), UI text ready for translation ([#280](https://github.com/solardev-xyz/freedom-browser-android/issues/280)), the default-browser role and "Search with Freedom" from other apps ([#268](https://github.com/solardev-xyz/freedom-browser-android/issues/268)), and x402 and Safe, which iOS doesn't have.
