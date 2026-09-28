# Bundled filter lists

Compiled on the device by `AdblockEngine.kt` (#126); one file per category
in Settings → Ad blocking. Refresh with `python3 infra/adblock/vendor-lists.py`
— the files are kept byte-for-byte as published upstream.

| File | List | Source | Category |
|---|---|---|---|
| `easylist.txt` | EasyList | https://easylist.to/easylist/easylist.txt | Block ads |
| `easyprivacy.txt` | EasyPrivacy | https://easylist.to/easylist/easyprivacy.txt | Block trackers |
| `fanboy-cookiemonster.txt` | Fanboy's Cookiemonster (EasyList Cookie List) | https://secure.fanboy.co.nz/fanboy-cookiemonster.txt | Block cookie notices |
| `fanboy-annoyance.txt` | Fanboy's Annoyance List | https://secure.fanboy.co.nz/fanboy-annoyance.txt | Block other annoyances |

The filter lists are © The EasyList authors (https://easylist.to/), dual-licensed
under the GNU General Public License v3 or later and the Creative Commons
Attribution-ShareAlike 3.0 Unported licence (or later). Freedom redistributes them,
unmodified, under CC BY-SA 3.0: https://creativecommons.org/licenses/by-sa/3.0/
