#!/usr/bin/env bash
# Fail unless release.yml's FFI_REF is a published freedom-mobile-ffi
# tag whose Cargo.toml pins every git dependency by tag, and the
# README's freedom-mobile-ffi clone/checkout lines use that same ref.
#
# A pin on a branch commit (or on an ffi build that pins unmerged
# ant/freedom-ipfs revs) works until upstream squash-merges and deletes
# the branch; then the SHA is unfetchable and both the release job's
# checkout and the README's local build steps break.
#
# release.yml runs this before building, so an UNRELEASED pin can't be
# shipped. ffi-ref.yml also runs it on every PR and push to main, but
# that only shows a red status check: main has no branch protection, so
# it blocks a merge only if `ffi-ref` is made a required check.
#
# Usage:
#   scripts/check-ffi-ref.sh [FFI_REF]            full check (default ref: release.yml)
#   scripts/check-ffi-ref.sh --cargo-toml FILE    only the Cargo.toml pin rule, on FILE
#   scripts/check-ffi-ref.sh --readme FILE REF    only the README rule, on FILE
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
ffi="https://github.com/solardev-xyz/freedom-mobile-ffi"

# Every table with a `git` key anywhere in Cargo.toml is a git dependency
# ([dependencies], [dependencies.x], [workspace.dependencies],
# [target.'…'.dependencies], [patch.…], inline or multi-line, any key
# order). Each must carry `tag`, and neither `rev` nor `branch`: a bare
# `git` follows the default branch, which moves just like `branch`.
check_cargo_toml() {
  python3 - "$1" <<'PY'
import sys, tomllib

with open(sys.argv[1], "rb") as f:
    doc = tomllib.load(f)

bad = []
def walk(node, path):
    if isinstance(node, dict):
        if isinstance(node.get("git"), str):
            if "tag" not in node or "rev" in node or "branch" in node:
                pins = ", ".join(f"{k} = {node[k]!r}" for k in ("git", "tag", "rev", "branch") if k in node)
                bad.append(f"{'.'.join(path)}: {{ {pins} }}")
            return
        for k, v in node.items():
            walk(v, path + [k])
    elif isinstance(node, list):
        for i, v in enumerate(node):
            walk(v, path + [str(i)])

walk(doc, [])
if bad:
    print("::error::freedom-mobile-ffi Cargo.toml has git dependencies not pinned by tag alone:", file=sys.stderr)
    for b in bad:
        print("  " + b, file=sys.stderr)
    sys.exit(1)
PY
}

# Each `git clone …freedom-mobile-ffi…` must name the ref, either with
# `--branch REF` or by a following `git -C …freedom-mobile-ffi checkout REF`,
# and every ref named must be FFI_REF.
check_readme() {
  python3 - "$1" "$2" <<'PY'
import re, sys

path, ref = sys.argv[1], sys.argv[2]
lines = open(path, encoding="utf-8").read().splitlines()
clones, refs, bad = 0, [], []
for n, line in enumerate(lines, 1):
    if "freedom-mobile-ffi" not in line:
        continue
    if re.search(r"\bgit clone\b", line):
        clones += 1
        m = re.search(r"--branch[ =](\S+)", line)
        if m:
            refs.append((n, m.group(1)))
    m = re.search(r"\bgit -C \S*freedom-mobile-ffi\S* checkout (\S+)", line)
    if m:
        refs.append((n, m.group(1)))
for n, r in refs:
    if r != ref:
        bad.append(f"README.md:{n}: checks out {r}")
if len(refs) < clones:
    bad.append(f"README.md: {clones} freedom-mobile-ffi clone(s) but only {len(refs)} name a ref")
if clones == 0:
    bad.append("README.md: no freedom-mobile-ffi clone line found")
if bad:
    print(f"::error::README's freedom-mobile-ffi build steps don't match FFI_REF {ref}:", file=sys.stderr)
    for b in bad:
        print("  " + b, file=sys.stderr)
    sys.exit(1)
PY
}

case "${1:-}" in
  --cargo-toml) check_cargo_toml "$2"; exit ;;
  --readme) check_readme "$2" "$3"; exit ;;
esac

ref="${1:-$(sed -n 's/^  FFI_REF: *//p' "$repo_root/.github/workflows/release.yml" | head -n1)}"

if [ -z "$ref" ]; then
  echo "::error::No FFI_REF found in .github/workflows/release.yml" >&2
  exit 1
fi

check_readme "$repo_root/README.md" "$ref"

if [ -z "$(git ls-remote --tags "$ffi" "refs/tags/$ref")" ]; then
  echo "::error::FFI_REF '$ref' is not a freedom-mobile-ffi tag. Tag freedom-mobile-ffi (with its node deps pinned by tag) and set FFI_REF and the README's clone lines to that tag before merging." >&2
  exit 1
fi

cargo_toml="$(mktemp)"
trap 'rm -f "$cargo_toml"' EXIT
curl -fsSL "https://raw.githubusercontent.com/solardev-xyz/freedom-mobile-ffi/refs/tags/$ref/Cargo.toml" -o "$cargo_toml"
check_cargo_toml "$cargo_toml"

echo "FFI_REF $ref: tagged, git deps pinned by tag, README matches"
