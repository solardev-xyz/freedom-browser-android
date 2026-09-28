#!/usr/bin/env bash
# Turn the `tor` feature on in a freedom-mobile-ffi checkout's
# scripts/build-android.sh, in place, so libfreedom_mobile_ffi.so also
# carries the embedded Arti (Tor) client and its onion-only SOCKS5
# listener (`freedom_tor_*`, vendored header swarmnode/src/main/cpp/freedom_tor.h).
# Run it AFTER scripts/enable-ffi-chain.sh and scripts/enable-ffi-radicle.sh:
# it extends their `--features chain,radicle` to `chain,radicle,tor`.
# Used by release.yml and by the README's local build steps.
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

[ -f "$SCRIPT" ] || { echo "enable-ffi-tor: $SCRIPT not found" >&2; exit 1; }

# Idempotent: an already-patched line no longer contains $FROM.
sed -i "s/$FROM/$TO/" "$SCRIPT"

code_lines="$(grep -v -E '^[[:space:]]*#' "$SCRIPT" | grep -F -- '--no-default-features' || true)"
if [ -z "$code_lines" ]; then
  echo "enable-ffi-tor: no --no-default-features cargo call in $SCRIPT; its shape changed, update this script" >&2
  exit 1
fi
if printf '%s\n' "$code_lines" | grep -v -q -F -- "$TO"; then
  echo "enable-ffi-tor: could not enable the tor feature on every cargo call in $SCRIPT" >&2
  echo "(run scripts/enable-ffi-chain.sh and scripts/enable-ffi-radicle.sh first):" >&2
  printf '%s\n' "$code_lines" | grep -v -F -- "$TO" >&2
  exit 1
fi
echo "enable-ffi-tor: tor feature enabled in $SCRIPT"
