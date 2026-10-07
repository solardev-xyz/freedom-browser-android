#!/usr/bin/env python3
"""Refresh the ad-blocking filter lists the app ships with (#126, #318).

    python3 infra/adblock/vendor-lists.py

Downloads the EasyList-family lists the desktop and iOS browsers block
with (the catalog of freedom-adblock-service/sources.json) and writes
them, unmodified, to app/src/main/assets/adblock/ — one file per
category the Settings screen toggles. The app compiles them on the
device at startup (AdblockEngine.kt); the Swarm update channel (#127)
refreshes them in between.

It also builds uBlock Origin's own filters (#318), the list that carries
the YouTube ad-pruning scriptlets (`youtube.com##+js(json-prune, …)`),
exactly as desktop's scripts/fetch-adblock-lists.js does, and fetches the
scriptlet code those rules call (uBlock Origin's scriptlets, as the
pinned @ghostery/adblocker `resources.json` desktop ships) plus the GPL
text both must travel with. Those three are bundled only — the update
channel doesn't publish them — so they refresh with each release that
re-runs this.

It never touches freedom-filters.txt (#391), Freedom's own hand-maintained
fixes compiled next to uBlock's filters: that file isn't vendored, so it
is neither written nor removed here (FREEDOM_FILE below, checked at start).

Filter list data is (c) the respective list authors: the EasyList family
dual-licensed GPLv3+ / CC BY-SA 3.0+ (the app redistributes it under
CC BY-SA), uBlock Origin's filters and scriptlets GPL-3.0; see
app/src/main/assets/adblock/README.md.
"""

import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen

OUT = Path(__file__).resolve().parents[2] / "app/src/main/assets/adblock"

# file name -> source URL. The names are what Adblock.kt's categories load.
LISTS = {
    "easylist.txt": "https://easylist.to/easylist/easylist.txt",
    # #405: Android-only so far (freedom-adblock-service doesn't publish
    # it, so the update channel doesn't refresh it: bundled, like uBlock's).
    "easylistgermany.txt": "https://easylist.to/easylistgermany/easylistgermany.txt",
    "easyprivacy.txt": "https://easylist.to/easylist/easyprivacy.txt",
    "fanboy-cookiemonster.txt": "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt",
    "fanboy-annoyance.txt": "https://secure.fanboy.co.nz/fanboy-annoyance.txt",
}

# uBlock Origin's filters plus its Quick fixes, as desktop bundles them
# (fetch-adblock-lists.js, category `ublock`). Built at one commit of
# uAssets' gh-pages branch (the site the Pages URLs serve), so the header
# can name an exact, permanent source.
UBLOCK_FILE = "ublock-filters.txt"

# Freedom's own list (#391): maintained by hand, never written by this script.
FREEDOM_FILE = "freedom-filters.txt"
UBLOCK_REPO = "uBlockOrigin/uAssets"
UBLOCK_BRANCH = "gh-pages"
UBLOCK_PAGES = "https://ublockorigin.github.io/uAssets/"
UBLOCK_LISTS = ["filters/filters.txt", "filters/quick-fixes.txt"]

# `!#if` tokens, evaluated here because the engine doesn't: Freedom on
# Android is a Chromium mobile browser without HTML filtering. Any token
# not listed is false.
UBLOCK_ENV = {"env_chromium": True, "env_mobile": True}
MAX_INCLUDE_DEPTH = 3

# The scriptlet code (`+js(...)` resources): uBlock Origin's scriptlets
# in @ghostery/adblocker's resources.json shape. Executable code injected
# into pages, so pinned like every other downloaded executable: a fixed
# upstream tag and an in-repo sha256, checked before anything is written.
# The same tag and digest as desktop's RESOURCES (fetch-adblock-lists.js,
# which also records the uBlock Origin revision the bodies come from:
# gorhill/uBlock 1.72.3rc4, source-identical to 1.73.0).
RESOURCES_FILE = "resources.json"
RESOURCES_URL = (
    "https://raw.githubusercontent.com/ghostery/adblocker/v2.18.2/"
    "packages/adblocker/assets/ublock-origin/resources.json"
)
RESOURCES_SHA256 = "e14b498f693c4166d27971f7fdfe49b167c139a8e659cc59bedc9ab29a2348f5"

# GPL-3.0 §4/§6: the uBlock filters and scriptlets travel with the text.
GPL_FILE = "COPYING.GPL-3.0.txt"
GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.txt"
GPL_SHA256 = "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"


def get(url: str, accept: str | None = None) -> str:
    headers = {"User-Agent": "Freedom-Adblock-Fetcher"}
    if accept:
        headers["Accept"] = accept
    with urlopen(Request(url, headers=headers), timeout=120) as resp:
        return resp.read().decode("utf-8")


def fetch(url: str) -> str:
    text = get(url)
    # A server that answers 200 with something else must not silently
    # replace a list (and so turn blocking off for that category).
    if "[Adblock" not in text.splitlines()[0]:
        raise SystemExit(f"{url} does not look like an ABP filter list")
    return text


def fetch_pinned(url: str, sha256: str) -> str:
    text = get(url)
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
    if digest != sha256:
        raise SystemExit(f"{url}: sha256 mismatch: expected {sha256}, got {digest}")
    return text


def evaluate_if(expr: str) -> bool:
    """A `!#if` condition: tokens, `!`, `&&` and `||` (no parentheses in uAssets)."""
    def term(t: str) -> bool:
        t = t.strip()
        if t.startswith("!"):
            return not term(t[1:])
        if not re.fullmatch(r"[a-z_0-9]+", t):
            raise SystemExit(f"unsupported !#if term {t!r}")
        return UBLOCK_ENV.get(t, False)
    return any(all(term(t) for t in part.split("&&")) for part in expr.split("||"))


def resolve_ublock(text: str, url: str, depth: int = 0) -> str:
    """Evaluate `!#if` blocks and splice `!#include`s (same directory only, as uBlock does)."""
    out: list[str] = []
    stack: list[list[bool]] = []  # per open `!#if`: [condition, branch live]
    for raw in text.split("\n"):
        line = raw.rstrip("\r")
        s = line.strip()
        if s.startswith("!#if "):
            cond = evaluate_if(s[5:])
            stack.append([cond, cond])
            continue
        if s == "!#else":
            if not stack:
                raise SystemExit(f"{url}: !#else without !#if")
            stack[-1][1] = not stack[-1][0]
            continue
        if s == "!#endif":
            if not stack:
                raise SystemExit(f"{url}: !#endif without !#if")
            stack.pop()
            continue
        if not all(frame[1] for frame in stack):
            continue
        if s.startswith("!#include "):
            if depth >= MAX_INCLUDE_DEPTH:
                raise SystemExit(f"{url}: !#include nested too deep")
            name = s[len("!#include "):].strip()
            inc = urljoin(url, name)
            base = urljoin(url, ".")
            if urlparse(inc).netloc != urlparse(base).netloc or not inc.startswith(base):
                raise SystemExit(f"{url}: refusing out-of-tree !#include {name}")
            body = get(inc)
            if body.lstrip().startswith("<"):
                raise SystemExit(f"{inc} does not look like a uBlock filter list")
            out.append(resolve_ublock(body, inc, depth + 1))
            continue
        out.append(line)
    if stack:
        raise SystemExit(f"{url}: unterminated !#if")
    return "\n".join(out)


def build_ublock() -> str:
    commit = get(
        f"https://api.github.com/repos/{UBLOCK_REPO}/commits/{UBLOCK_BRANCH}",
        accept="application/vnd.github.sha",
    ).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise SystemExit(f"unexpected commit answer {commit!r}")
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    # GPL-3.0 §5(a): a modified work (branches evaluated, includes
    # spliced, lists concatenated) says so, and names its exact source.
    header = [
        "! Title: uBlock filters (Freedom build)",
        f"! Built by Freedom's infra/adblock/vendor-lists.py on {today} from:",
        *[f"!   https://github.com/{UBLOCK_REPO}/blob/{commit}/{p}" for p in UBLOCK_LISTS],
        f"! ({UBLOCK_REPO} commit {commit})",
        "! Modified: `!#if` blocks evaluated for a Chromium mobile build, `!#include`",
        "! files spliced in, lists concatenated. Rules themselves are unchanged.",
        "! License: GPL-3.0 (see COPYING.GPL-3.0.txt next to this file).",
        "! Copyright (C) Raymond Hill and the uBlock Origin contributors,",
        "! https://github.com/uBlockOrigin/uAssets",
        "",
    ]
    parts = ["\n".join(header)]
    for path in UBLOCK_LISTS:
        url = f"https://raw.githubusercontent.com/{UBLOCK_REPO}/{commit}/{path}"
        text = get(url)
        if not re.search(r"^! Title: uBlock", text[:2048], re.M):
            raise SystemExit(f"{url} does not look like a uBlock filter list")
        parts.append(resolve_ublock(text, url))
    return "\n".join(parts)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, url in LISTS.items():
        text = fetch(url)
        (OUT / name).write_text(text, encoding="utf-8")
        version = next((l for l in text.splitlines() if l.startswith("! Version:")), "! Version: ?")
        print(f"{name}: {len(text):,} bytes, {version[2:]}")
    ublock = build_ublock()
    (OUT / UBLOCK_FILE).write_text(ublock, encoding="utf-8")
    print(f"{UBLOCK_FILE}: {len(ublock):,} bytes")
    resources = fetch_pinned(RESOURCES_URL, RESOURCES_SHA256)
    if not json.loads(resources).get("scriptlets"):
        raise SystemExit("scriptlet resources contain no scriptlets")
    (OUT / RESOURCES_FILE).write_text(resources, encoding="utf-8")
    print(f"{RESOURCES_FILE}: {len(resources):,} bytes (sha256 {RESOURCES_SHA256[:12]}…)")
    (OUT / GPL_FILE).write_text(fetch_pinned(GPL_URL, GPL_SHA256), encoding="utf-8")
    print(f"{GPL_FILE}: ok")


if __name__ == "__main__":
    if FREEDOM_FILE in LISTS or FREEDOM_FILE in (UBLOCK_FILE, RESOURCES_FILE, GPL_FILE):
        raise SystemExit(f"{FREEDOM_FILE} is maintained by hand and must not be vendored")
    if len(sys.argv) > 1 and sys.argv[1] == "--ublock-only":
        # Refresh only the bundled-only uBlock files, keeping the
        # EasyList-family lists (and their Swarm-feed dates) as they are.
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / UBLOCK_FILE).write_text(build_ublock(), encoding="utf-8")
        (OUT / RESOURCES_FILE).write_text(fetch_pinned(RESOURCES_URL, RESOURCES_SHA256), encoding="utf-8")
        (OUT / GPL_FILE).write_text(fetch_pinned(GPL_URL, GPL_SHA256), encoding="utf-8")
    else:
        main()
