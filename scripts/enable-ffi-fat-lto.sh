#!/usr/bin/env bash
# Switch a freedom-mobile-ffi checkout's Android release profile from
# thin to fat LTO, in place (#230). Used by release.yml and by the
# README's local build steps, so there is exactly one copy of this edit.
#
# scripts/build-android.sh builds with `--profile release-android`, which
# sets `lto = "thin"` in freedom-mobile-ffi's Cargo.toml. Whole-program
# (fat) LTO lets LLVM drop and merge far more across the combined ant +
# freedom-ipfs + Myotis + Radicle + Arti graph: libfreedom_mobile_ffi.so
# comes out ~10% smaller per ABI (arm64-v8a 42.8 -> 38.3 MB at v0.12.4),
# with the same speed (see the PR for #230), at the price of a longer
# link. opt-level stays 3: "s"/"z" would shrink it further but make
# AES-CTR (Tor), ChaCha20-Poly1305 (libp2p) and SHA-256 (IPFS) several
# times slower.
#
# Only the profile changes; features, dependencies and Cargo.lock don't.
# Fails unless build-android.sh still builds that profile and the edited
# Cargo.toml parses with `lto = "fat"` in it, so a future FFI_REF that
# renames or reshapes the profile can't quietly ship thin LTO again.
#
# Usage: scripts/enable-ffi-fat-lto.sh <path-to-freedom-mobile-ffi>
set -euo pipefail

FFI_DIR="${1:?usage: $0 <path-to-freedom-mobile-ffi>}"
SCRIPT="$FFI_DIR/scripts/build-android.sh"
CARGO_TOML="$FFI_DIR/Cargo.toml"
PROFILE=release-android

[ -f "$SCRIPT" ] || { echo "enable-ffi-fat-lto: $SCRIPT not found" >&2; exit 1; }
[ -f "$CARGO_TOML" ] || { echo "enable-ffi-fat-lto: $CARGO_TOML not found" >&2; exit 1; }

if ! grep -q -x "PROFILE=$PROFILE" "$SCRIPT"; then
  echo "enable-ffi-fat-lto: $SCRIPT no longer builds --profile $PROFILE; update this script" >&2
  exit 1
fi

python3 - "$CARGO_TOML" "$PROFILE" <<'PY'
import re, sys, tomllib

path, profile = sys.argv[1], sys.argv[2]
lines = open(path, encoding="utf-8").read().splitlines(keepends=True)
header = f"[profile.{profile}]"
start = next((i for i, l in enumerate(lines) if l.strip() == header), None)
if start is None:
    sys.exit(f"enable-ffi-fat-lto: no {header} in {path}; update this script")
end = next((i for i in range(start + 1, len(lines)) if lines[i].lstrip().startswith("[")), len(lines))
lto = [i for i in range(start + 1, end) if re.match(r"\s*lto\s*=", lines[i])]
# Idempotent: rewrite an existing `lto = …` key, or add one.
if lto:
    for i in lto:
        lines[i] = 'lto = "fat"\n'
else:
    lines.insert(start + 1, 'lto = "fat"\n')
text = "".join(lines)
prof = tomllib.loads(text)["profile"][profile]
if prof.get("lto") != "fat":
    sys.exit(f"enable-ffi-fat-lto: {header} still has lto = {prof.get('lto')!r}")
open(path, "w", encoding="utf-8").write(text)
PY
echo "enable-ffi-fat-lto: fat LTO enabled for [profile.$PROFILE] in $CARGO_TOML"
