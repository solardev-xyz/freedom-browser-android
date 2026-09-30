#!/usr/bin/env bash
# Turn the `tor` feature on in a freedom-mobile-ffi checkout's
# scripts/build-android.sh, in place, so libfreedom_mobile_ffi.so also
# carries the embedded Arti (Tor) client and its onion-only SOCKS5
# listener (`freedom_tor_*`, vendored header swarmnode/src/main/cpp/freedom_tor.h).
# Run it AFTER scripts/enable-ffi-chain.sh and scripts/enable-ffi-radicle.sh:
# it extends their `--features chain,radicle` to `chain,radicle,tor`.
# Run by scripts/build-ffi.sh, the one build recipe release.yml and the
# README's local build share.
#
# Fails unless every (non-comment) cargo line that passes
# --no-default-features now carries the exact substituted fragment, so a
# future FFI_REF that reshapes the invocation can't quietly build a .so
# without Tor.
#
# Usage: scripts/enable-ffi-tor.sh <path-to-freedom-mobile-ffi>
set -euo pipefail

FFI_DIR="${1:?usage: $0 <path-to-freedom-mobile-ffi>}"
SCRIPT="$FFI_DIR/scripts/build-android.sh"
FROM='--no-default-features --features chain,radicle --crate-type'
TO='--no-default-features --features chain,radicle,tor --crate-type'
# What counts as "tor is on": the list above, alone or already extended
# by a later helper, so re-running build-ffi.sh in the same checkout passes.
DONE='--no-default-features --features chain,radicle,tor(,[A-Za-z0-9_-]+)* --crate-type'

[ -f "$SCRIPT" ] || { echo "enable-ffi-tor: $SCRIPT not found" >&2; exit 1; }

# Idempotent: an already-patched line no longer contains $FROM.
sed -i "s/$FROM/$TO/" "$SCRIPT"

code_lines="$(grep -v -E '^[[:space:]]*#' "$SCRIPT" | grep -F -- '--no-default-features' || true)"
if [ -z "$code_lines" ]; then
  echo "enable-ffi-tor: no --no-default-features cargo call in $SCRIPT; its shape changed, update this script" >&2
  exit 1
fi
if printf '%s\n' "$code_lines" | grep -v -q -E -- "$DONE"; then
  echo "enable-ffi-tor: could not enable the tor feature on every cargo call in $SCRIPT" >&2
  echo "(run scripts/enable-ffi-chain.sh and scripts/enable-ffi-radicle.sh first):" >&2
  printf '%s\n' "$code_lines" | grep -v -E -- "$DONE" >&2
  exit 1
fi
echo "enable-ffi-tor: tor feature enabled in $SCRIPT"
