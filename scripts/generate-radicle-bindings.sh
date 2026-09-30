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
#   scripts/generate-radicle-bindings.sh <ffi-dir> --emit <file>   # write them to <file> instead
#
# release.yml runs --emit after building the .so and caches the result with
# the library (#309), then compares it with the committed copy on every run,
# cache hit or not, so a FFI_REF bump that changes the Radicle surface can't
# ship with stale bindings.
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
EMIT="${3:-}"
if [ "$MODE" = "--emit" ] && [ -z "$EMIT" ]; then
  echo "usage: $0 <path-to-freedom-mobile-ffi> --emit <file>" >&2
  exit 1
fi
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO/swarmnode/src/main/java/uniffi/libradicle_uniffi/libradicle_uniffi.kt"
TRIPLE=aarch64-linux-android
PROFILE=release-android

# Pick the rlib of the libradicle-uniffi revision this checkout resolves to,
# not just the newest file: a restored build cache (CI's rust-cache) can
# leave rlibs of other revisions under other hashes, with any mtime. Each
# rlib records the source path it was compiled from, and cargo checks a git
# dependency out into a per-revision directory, so the one built from the
# resolved package's source directory is the one the .so links.
DEPS="$FFI_DIR/target/$TRIPLE/$PROFILE/deps"
SRC_DIR="$(cd "$FFI_DIR" && cargo metadata --format-version 1 \
  | jq -r '.packages[] | select(.name=="libradicle-uniffi") | .manifest_path' | head -1)"
[ -n "$SRC_DIR" ] && [ "$SRC_DIR" != null ] || {
  echo "generate-radicle-bindings: libradicle-uniffi is not in $FFI_DIR's dependency graph" >&2
  exit 1
}
SRC_DIR="$(dirname "$SRC_DIR")/src/"
RLIB=""
# Newest among the matches: the same revision built with other features.
for candidate in $(ls -t "$DEPS"/liblibradicle_uniffi-*.rlib 2>/dev/null || true); do
  if grep -q -a -F -- "$SRC_DIR" "$candidate"; then RLIB="$candidate"; break; fi
done
[ -n "$RLIB" ] || {
  echo "generate-radicle-bindings: no libradicle-uniffi rlib built from $SRC_DIR under $DEPS;" >&2
  echo "build with scripts/enable-ffi-chain.sh + scripts/enable-ffi-radicle.sh + scripts/build-android.sh first" >&2
  exit 1
}
echo "generate-radicle-bindings: using $RLIB" >&2

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
elif [ "$MODE" = "--emit" ]; then
  mkdir -p "$(dirname "$EMIT")"
  cp "$GEN" "$EMIT"
  echo "generate-radicle-bindings: wrote $EMIT"
else
  mkdir -p "$(dirname "$DEST")"
  cp "$GEN" "$DEST"
  echo "generate-radicle-bindings: wrote $DEST"
fi
