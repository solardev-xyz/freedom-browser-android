#!/usr/bin/env bash
# Build libfreedom_mobile_ffi.so from a freedom-mobile-ffi checkout the way
# release.yml ships it: ant's `chain` feature, the Radicle node, the Tor
# client and fat LTO (scripts/enable-ffi-*.sh), then the checkout's own
# scripts/build-android.sh. Output lands in <ffi>/target/android/
# (jniLibs/<abi>/ and headers/), as build-android.sh leaves it.
#
#   scripts/build-ffi.sh <path-to-freedom-mobile-ffi> [abi]
#
# [abi] (arm64-v8a or x86_64) builds that ABI only: release.yml runs one
# job per ABI in parallel (#309). Without it, both are built, one after
# the other.
#
# Everything that shapes the built library from a given FFI_REF lives in
# this script and the helpers it calls, so release.yml keys its cache of
# the built library on their hashes (see the `ffi` job there). A change to
# how the library is built belongs here, not in the workflow, or the cache
# keeps serving a library built the old way.
#
# build-android.sh builds every ABI in its `declare -A ABI=(…)` table; to
# build one, this rewrites that table to the one entry for the build, and
# fails unless exactly that entry is left, so a future FFI_REF that
# reshapes the table can't quietly build the wrong ABI (or none). The
# table is put back when the script exits, however it exits, so a later
# run for the other ABI (or both) in the same checkout sees it whole.
set -euo pipefail

FFI_DIR="$(cd "${1:?usage: $0 <path-to-freedom-mobile-ffi> [arm64-v8a|x86_64]}" && pwd)"
ONLY="${2:-}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$FFI_DIR/scripts/build-android.sh"

"$HERE/enable-ffi-chain.sh" "$FFI_DIR"
"$HERE/enable-ffi-radicle.sh" "$FFI_DIR"
"$HERE/enable-ffi-tor.sh" "$FFI_DIR"
"$HERE/enable-ffi-fat-lto.sh" "$FFI_DIR"

if [ -n "$ONLY" ]; then
  ORIG="$(mktemp "$FFI_DIR/scripts/.build-android.sh.XXXXXX")"
  cp -p "$SCRIPT" "$ORIG"
  trap 'mv -f "$ORIG" "$SCRIPT"' EXIT
  python3 - "$SCRIPT" "$ONLY" <<'PY'
import re, sys

path, abi = sys.argv[1], sys.argv[2]
lines = open(path, encoding="utf-8").read().splitlines(keepends=True)
start = [i for i, l in enumerate(lines) if l.strip() == "declare -A ABI=("]
if len(start) != 1:
    sys.exit(f"build-ffi: expected one `declare -A ABI=(` table in {path}, found {len(start)}; update this script")
start = start[0]
end = next((i for i in range(start + 1, len(lines)) if lines[i].strip() == ")"), None)
if end is None:
    sys.exit(f"build-ffi: unterminated ABI table in {path}; update this script")
entry = re.compile(r"\s*\[([A-Za-z0-9_-]+)\]=([A-Za-z0-9_-]+)\s*$")
body = lines[start + 1:end]
if any(l.strip() and not entry.match(l) for l in body):
    sys.exit(f"build-ffi: unexpected line in the ABI table of {path}; update this script")
keep = [l for l in body if entry.match(l) and entry.match(l).group(2) == abi]
if len(keep) != 1:
    sys.exit(f"build-ffi: {path} has no single ABI table entry for {abi}")
lines[start + 1:end] = keep
open(path, "w", encoding="utf-8").write("".join(lines))
PY
  echo "build-ffi: building $ONLY only"
fi

( cd "$FFI_DIR" && ./scripts/build-android.sh )

# build-android.sh stages whatever its table named; make sure that's it.
built="$(cd "$FFI_DIR/target/android/jniLibs" && ls)"
want="${ONLY:-$(printf 'arm64-v8a\nx86_64')}"
if [ "$built" != "$want" ]; then
  echo "build-ffi: built ABIs [$(echo $built)], expected [$(echo $want)]" >&2
  exit 1
fi
