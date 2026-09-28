#!/usr/bin/env bash
# Generate the Kotlin UniFFI bindings for the embedded Radicle node
# (libradicle-uniffi) from a freedom-mobile-ffi checkout that has just run
# scripts/build-android.sh with the `radicle` feature on (see
# scripts/enable-ffi-radicle.sh).
#
# The bindings are committed at swarmnode/src/main/java/uniffi/
# libradicle_uniffi/libradicle_uniffi.kt. They must come from the same
# libradicle-uniffi the .so links: the generated code checks every
# function's checksum at load and refuses a skewed library. So:
#
#   scripts/generate-radicle-bindings.sh <ffi-dir>           # regenerate in place
#   scripts/generate-radicle-bindings.sh <ffi-dir> --check   # fail if the committed copy is stale
#
# release.yml runs --check after building the .so, so a FFI_REF bump that
# changes the Radicle surface can't ship with stale bindings.
#
# The generator is freedom-mobile-ffi's own bindgen/ crate (pinned to the
# uniffi version the scaffolding links). It runs in library mode against the
# libradicle-uniffi rlib cargo left in target/ — the shipped .so is
# symbol-stripped, and UniFFI reads its metadata from the symbol table.
# `cdylib_name` points the generated loader at libfreedom_mobile_ffi.so, the
# one library the Radicle scaffolding actually lives in.
set -euo pipefail

FFI_DIR="$(cd "${1:?usage: $0 <path-to-freedom-mobile-ffi> [--check]}" && pwd)"
MODE="${2:-write}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO/swarmnode/src/main/java/uniffi/libradicle_uniffi/libradicle_uniffi.kt"
TRIPLE=aarch64-linux-android
PROFILE=release-android

# Newest rlib: an older build of another libradicle-uniffi revision may
# still sit next to it under a different hash.
RLIB="$(ls -t "$FFI_DIR/target/$TRIPLE/$PROFILE/deps/"liblibradicle_uniffi-*.rlib 2>/dev/null | head -1 || true)"
[ -n "$RLIB" ] || {
  echo "generate-radicle-bindings: no libradicle-uniffi rlib under $FFI_DIR/target/$TRIPLE/$PROFILE;" >&2
  echo "build with scripts/enable-ffi-chain.sh + scripts/enable-ffi-radicle.sh + scripts/build-android.sh first" >&2
  exit 1
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cat > "$WORK/uniffi.toml" <<'TOML'
[crates.libradicle_uniffi.bindings.kotlin]
cdylib_name = "freedom_mobile_ffi"
TOML

( cd "$FFI_DIR" && cargo run --quiet --manifest-path bindgen/Cargo.toml --release -- \
    generate --library "$RLIB" --config "$WORK/uniffi.toml" \
    --language kotlin --no-format --out-dir "$WORK/out" )
GEN="$WORK/out/uniffi/libradicle_uniffi/libradicle_uniffi.kt"
[ -f "$GEN" ] || { echo "generate-radicle-bindings: uniffi-bindgen did not produce $GEN" >&2; exit 1; }

# The Radicle scaffolding must have survived into the shipped .so too.
for abi_dir in "$FFI_DIR"/target/android/jniLibs/*/; do
  so="$abi_dir/libfreedom_mobile_ffi.so"
  [ -f "$so" ] || continue
  if ! grep -q -a uniffi_libradicle_uniffi_fn_func_start "$so"; then
    echo "generate-radicle-bindings: $so has no Radicle exports (was the radicle feature on?)" >&2
    exit 1
  fi
done

if [ "$MODE" = "--check" ]; then
  if ! cmp -s "$GEN" "$DEST"; then
    echo "generate-radicle-bindings: $DEST is stale for this build; rerun without --check and commit it" >&2
    diff -u "$DEST" "$GEN" | head -40 >&2 || true
    exit 1
  fi
  echo "generate-radicle-bindings: committed bindings match the build"
else
  mkdir -p "$(dirname "$DEST")"
  cp "$GEN" "$DEST"
  echo "generate-radicle-bindings: wrote $DEST"
fi
