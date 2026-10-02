# Bundled filter lists

Compiled on the device by `AdblockEngine.kt` (#126); one file per category
in Settings → Ad blocking, plus uBlock Origin's own filters under *Block ads*
(#318) and Freedom's own fixes (#391). Refresh with `python3 infra/adblock/vendor-lists.py` — the EasyList
files are kept byte-for-byte as published upstream (the Swarm update channel,
#127, refreshes them in between); `--ublock-only` refreshes just the uBlock
files below, which the update channel doesn't carry.

| File | List | Source | Category |
|---|---|---|---|
| `easylist.txt` | EasyList | https://easylist.to/easylist/easylist.txt | Block ads |
| `ublock-filters.txt` | uBlock filters (+ Quick fixes) | https://github.com/uBlockOrigin/uAssets (`filters/filters.txt`, `filters/quick-fixes.txt`, at the commit named in the file's header) | Block ads |
| `freedom-filters.txt` | Freedom filters | This repository — maintained by hand, not vendored (see below) | Block ads |
| `easyprivacy.txt` | EasyPrivacy | https://easylist.to/easylist/easyprivacy.txt | Block trackers |
| `fanboy-cookiemonster.txt` | Fanboy's Cookiemonster (EasyList Cookie List) | https://secure.fanboy.co.nz/fanboy-cookiemonster.txt | Block cookie notices |
| `fanboy-annoyance.txt` | Fanboy's Annoyance List | https://secure.fanboy.co.nz/fanboy-annoyance.txt | Block other annoyances |
| `resources.json` | uBlock Origin's scriptlets (the code `+js(…)` rules call), via @ghostery/adblocker v2.18.2 | https://raw.githubusercontent.com/ghostery/adblocker/v2.18.2/packages/adblocker/assets/ublock-origin/resources.json (sha256 `e14b498f…2348f5`, pinned in the script) | — |
| `COPYING.GPL-3.0.txt` | The GNU General Public License v3 | https://www.gnu.org/licenses/gpl-3.0.txt | — |

The EasyList-family lists are © The EasyList authors (https://easylist.to/),
dual-licensed under the GNU General Public License v3 or later and the Creative
Commons Attribution-ShareAlike 3.0 Unported licence (or later). Freedom
redistributes them, unmodified, under CC BY-SA 3.0:
https://creativecommons.org/licenses/by-sa/3.0/

`ublock-filters.txt` and `resources.json` are © Raymond Hill and the uBlock
Origin contributors, licensed under the GNU General Public License v3
(`COPYING.GPL-3.0.txt`, next to them). They are data files the app reads at run
time, shipped as separate files, as desktop does. `ublock-filters.txt` is a
modified work in the GPL's sense — its `!#if` blocks are evaluated for a
Chromium mobile browser, its `!#include` files spliced in and the two lists
concatenated; the rules themselves are unchanged — and says so in its header,
with the exact uAssets commit it was built from. `resources.json` is kept
byte-for-byte as published; its scriptlet bodies are minified, and their
source is uBlock Origin's `src/js/resources` at
https://github.com/gorhill/uBlock/tree/de31aee0fcd69dc89cde558f1a0638c1aa77b75e/src/js/resources
(tag 1.72.3rc4, source-identical to release 1.73.0; see desktop's
`scripts/fetch-adblock-lists.js` for how that revision was identified).

`freedom-filters.txt` is Freedom's own list: fixes for sites the upstream
lists don't handle yet (#391), each rule with a comment naming its issue,
to be dropped once upstream carries a fix. It compiles under *Block ads*
with the same trust as uBlock's filters (it may call the `trusted-*`
scriptlets). `vendor-lists.py` never writes or removes it and the Swarm
update channel (#127) doesn't carry it — like the uBlock files it is read
from the APK's assets only — so edit it here, by hand. Written by the
Freedom Browser authors and licensed under the GNU General Public License
v3 only (GPL-3.0-only, `COPYING.GPL-3.0.txt`), as the uBlock rules it extends — unlike the
rest of Freedom's own code, whose licence isn't decided yet — so
`app/licences/bundled.json` lists it as a GPL-3.0-only component (shown on the
Open-source licences screen), not under `own`.
