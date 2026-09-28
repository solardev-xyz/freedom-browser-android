#!/usr/bin/env python3
"""Refresh the ad-blocking filter lists the app ships with (#126).

    python3 infra/adblock/vendor-lists.py

Downloads the EasyList-family lists the desktop and iOS browsers block
with (the catalog of freedom-adblock-service/sources.json) and writes
them, unmodified, to app/src/main/assets/adblock/ — one file per
category the Settings screen toggles. The app compiles them on the
device at startup (AdblockEngine.kt); there is no update channel on
Android yet, so the lists refresh with each release that re-runs this.

Filter list data is (c) the respective list authors, dual-licensed
GPLv3+ / CC BY-SA 3.0+; the app redistributes it under CC BY-SA with the
attribution in app/src/main/assets/adblock/README.md.
"""

from pathlib import Path
from urllib.request import Request, urlopen

OUT = Path(__file__).resolve().parents[2] / "app/src/main/assets/adblock"

# file name -> source URL. The names are what Adblock.kt's categories load.
LISTS = {
    "easylist.txt": "https://easylist.to/easylist/easylist.txt",
    "easyprivacy.txt": "https://easylist.to/easylist/easyprivacy.txt",
    "fanboy-cookiemonster.txt": "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt",
    "fanboy-annoyance.txt": "https://secure.fanboy.co.nz/fanboy-annoyance.txt",
}


def fetch(url: str) -> str:
    req = Request(url, headers={"User-Agent": "Freedom-Adblock-Fetcher"})
    with urlopen(req, timeout=120) as resp:
        text = resp.read().decode("utf-8")
    # A server that answers 200 with something else must not silently
    # replace a list (and so turn blocking off for that category).
    if "[Adblock" not in text.splitlines()[0]:
        raise SystemExit(f"{url} does not look like an ABP filter list")
    return text


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, url in LISTS.items():
        text = fetch(url)
        (OUT / name).write_text(text, encoding="utf-8")
        version = next((l for l in text.splitlines() if l.startswith("! Version:")), "! Version: ?")
        print(f"{name}: {len(text):,} bytes, {version[2:]}")


if __name__ == "__main__":
    main()
