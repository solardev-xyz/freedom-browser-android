#!/usr/bin/env bash
# Turn the `radicle` feature on in a freedom-mobile-ffi checkout's
# scripts/build-android.sh, in place, so libfreedom_mobile_ffi.so also
# carries the embedded Radicle node (libradicle-uniffi, with its
# spawn-free `no-spawn` serving). Run it AFTER scripts/enable-ffi-chain.sh:
# it extends that script's `--features chain` to `--features chain,radicle`.
# Used by release.yml and by the README's local build steps.
#
# The Kotlin side calls the node through the UniFFI bindings committed in
# swarmnode/src/main/java/uniffi/libradicle_uniffi/, which
# scripts/generate-radicle-bindings.sh regenerates from the built .so.
#
# Fails unless every (non-comment) cargo line that passes
# --no-default-features now carries the exact substituted fragment, so a
# future FFI_REF that reshapes the invocation can't quietly build a .so
# without Radicle.
#
# Usage: scripts/enable-ffi-radicle.sh <path-to-freedom-mobile-ffi>
set -euo pipefail

FFI_DIR="${1:?usage: $0 <path-to-freedom-mobile-ffi>}"
SCRIPT="$FFI_DIR/scripts/build-android.sh"
FROM='--no-default-features --features chain --crate-type'
TO='--no-default-features --features chain,radicle --crate-type'

[ -f "$SCRIPT" ] || { echo "enable-ffi-radicle: $SCRIPT not found" >&2; exit 1; }

# Idempotent: an already-patched line no longer contains $FROM.
sed -i "s/$FROM/$TO/" "$SCRIPT"

code_lines="$(grep -v -E '^[[:space:]]*#' "$SCRIPT" | grep -F -- '--no-default-features' || true)"
if [ -z "$code_lines" ]; then
  echo "enable-ffi-radicle: no --no-default-features cargo call in $SCRIPT; its shape changed, update this script" >&2
  exit 1
fi
if printf '%s\n' "$code_lines" | grep -v -q -F -- "$TO"; then
  echo "enable-ffi-radicle: could not enable the radicle feature on every cargo call in $SCRIPT" >&2
  echo "(run scripts/enable-ffi-chain.sh first):" >&2
  printf '%s\n' "$code_lines" | grep -v -F -- "$TO" >&2
  exit 1
fi
echo "enable-ffi-radicle: radicle feature enabled in $SCRIPT"
