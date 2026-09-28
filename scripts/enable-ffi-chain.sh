#!/usr/bin/env bash
# Turn ant's `chain` feature on in a freedom-mobile-ffi checkout's
# scripts/build-android.sh, in place. Used by release.yml and by the
# README's local build steps, so there is exactly one copy of this edit.
#
# build-android.sh passes --no-default-features to its cargo call, which
# drops both `chain` (ant's on-chain /wallet, /stamps, /chequebook gateway
# surfaces) and `radicle`, and it has no way to pass features in. This
# rewrites that cargo invocation to add `--features chain`;
# scripts/enable-ffi-radicle.sh then adds `radicle`.
#
# Fails unless every (non-comment) cargo line that passes
# --no-default-features now carries the exact substituted fragment, so a
# future FFI_REF that reshapes the invocation can't quietly build a .so
# without `chain` — a comment or stray mention elsewhere doesn't count.
#
# Usage: scripts/enable-ffi-chain.sh <path-to-freedom-mobile-ffi>
set -euo pipefail

FFI_DIR="${1:?usage: $0 <path-to-freedom-mobile-ffi>}"
SCRIPT="$FFI_DIR/scripts/build-android.sh"
FROM='--no-default-features --crate-type'
TO='--no-default-features --features chain --crate-type'
# What counts as "chain is on": `chain` first in the feature list, alone or
# already extended by a later helper (enable-ffi-radicle.sh makes it
# `chain,radicle`), so re-running this after that one still passes.
DONE='--no-default-features --features chain(,[A-Za-z0-9_-]+)* --crate-type'

[ -f "$SCRIPT" ] || { echo "enable-ffi-chain: $SCRIPT not found" >&2; exit 1; }

# Idempotent: an already-patched line (by this script or a later helper)
# no longer contains $FROM.
sed -i "s/$FROM/$TO/" "$SCRIPT"

# Code lines (comments stripped) that turn default features off.
code_lines="$(grep -v -E '^[[:space:]]*#' "$SCRIPT" | grep -F -- '--no-default-features' || true)"
if [ -z "$code_lines" ]; then
  echo "enable-ffi-chain: no --no-default-features cargo call in $SCRIPT; its shape changed, update this script" >&2
  exit 1
fi
if printf '%s\n' "$code_lines" | grep -v -q -E -- "$DONE"; then
  echo "enable-ffi-chain: could not enable the chain feature on every cargo call in $SCRIPT:" >&2
  printf '%s\n' "$code_lines" | grep -v -E -- "$DONE" >&2
  exit 1
fi
echo "enable-ffi-chain: chain feature enabled in $SCRIPT"
