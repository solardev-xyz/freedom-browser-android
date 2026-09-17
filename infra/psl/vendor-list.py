#!/usr/bin/env python3
"""Regenerate the Public Suffix List the address label ships with.

    python3 infra/psl/vendor-list.py

Downloads the upstream list, drops the comments (keeping only the two
section markers, which the app's parser ignores but a reviewer wants),
adds the A-label (punycode) form of every U-label rule so a host written
either way matches, and appends the Freedom Browser virtual-origin
suffixes from `public_suffix_list.patch` — they are ours, pending
upstream (issue #6), and the label must not collapse two content roots
onto one `freedom.baby` until then.

Output: app/src/main/resources/baby/freedom/mobile/browser/public_suffix_list.dat
"""

from pathlib import Path
from urllib.request import urlopen

import idna

SOURCE = "https://publicsuffix.org/list/public_suffix_list.dat"
OUT = (
    Path(__file__).resolve().parents[2]
    / "app/src/main/resources/baby/freedom/mobile/browser/public_suffix_list.dat"
)
MARKERS = ("// ===BEGIN ICANN DOMAINS===", "// ===BEGIN PRIVATE DOMAINS===")


def a_label(rule: str) -> str:
    """Punycode form of `rule`, or `rule` if it is already ASCII/untranslatable."""
    out = []
    for label in rule.split("."):
        if label.isascii():
            out.append(label)
            continue
        try:
            out.append(idna.encode(label, uts46=True).decode("ascii"))
        except idna.IDNAError:
            return rule
    return ".".join(out)


def main() -> None:
    raw = urlopen(SOURCE).read().decode("utf-8")
    lines = [
        "// Public Suffix List, vendored for baby.freedom.mobile.browser.PublicSuffixList.",
        f"// Source: {SOURCE}",
        "// Regenerate with: python3 infra/psl/vendor-list.py",
        "//",
        "// Comments are stripped; `//` lines and blank lines are ignored by the",
        "// parser. Each U-label rule is followed by its A-label (punycode) twin.",
    ]
    for line in raw.splitlines():
        rule = line.strip()
        if rule in MARKERS:
            lines.append(rule)
            continue
        if not rule or rule.startswith("//"):
            continue
        lines.append(rule)
        ascii_rule = a_label(rule)
        if ascii_rule != rule:
            lines.append(ascii_rule)

    lines.append("// ===BEGIN FREEDOM BROWSER VIRTUAL ORIGINS===")
    lines.append("// Pending upstream: infra/psl/public_suffix_list.patch (issue #6).")
    lines += ["bzz.freedom.baby", "ens.freedom.baby", "ipfs.freedom.baby", "ipns.freedom.baby"]

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {OUT} ({len(lines)} lines)")


if __name__ == "__main__":
    main()
