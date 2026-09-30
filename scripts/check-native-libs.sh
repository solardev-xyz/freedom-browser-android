#!/usr/bin/env bash
# Check the prebuilt native libraries an APK is about to package, whether
# they were just built or restored from release.yml's cache (#309), so a
# stale or wrong artefact fails the job instead of shipping:
#
#   scripts/check-native-libs.sh <jniLibs dir> <abi>... [--colibri]
#
# For each <abi> (arm64-v8a, x86_64), <jniLibs dir>/<abi>/ must hold:
#   - libfreedom_mobile_ffi.so for that ABI's architecture, with its SONAME,
#     the node exports build-android.sh checks, the five freedom_tor_*
#     exports tor_jni.c links against, and the Radicle UniFFI scaffolding
#     the generated bindings call;
#   - with --colibri, libc4.so for that ABI's architecture instead (the
#     Colibri verifier, scripts/build-colibri.sh), exporting the c4_*
#     functions swarmnode/src/main/cpp/colibri_jni.c calls.
#
# Uses the NDK's llvm-nm/llvm-readelf (ANDROID_NDK_ROOT or
# ANDROID_NDK_HOME), else whatever llvm-nm/nm and llvm-readelf/readelf are
# on PATH.
set -euo pipefail

DIR="${1:?usage: $0 <jniLibs dir> <abi>... [--colibri]}"
shift
COLIBRI=0
ABIS=()
for a in "$@"; do
  case "$a" in
    --colibri) COLIBRI=1 ;;
    arm64-v8a|x86_64) ABIS+=("$a") ;;
    *) echo "check-native-libs: unknown ABI $a" >&2; exit 1 ;;
  esac
done
[ "${#ABIS[@]}" -gt 0 ] || { echo "check-native-libs: no ABI given" >&2; exit 1; }

NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-/nonexistent}}"
NDK_BIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -1 || true)"
NM="${NDK_BIN:+$NDK_BIN/llvm-nm}"; NM="${NM:-$(command -v llvm-nm || echo nm)}"
READELF="${NDK_BIN:+$NDK_BIN/llvm-readelf}"; READELF="${READELF:-$(command -v llvm-readelf || echo readelf)}"

fail() { echo "::error::check-native-libs: $*" >&2; exit 1; }

# Capture tool output before grepping (not `nm | grep -q`): under pipefail,
# grep -q exiting early SIGPIPEs nm and fails the pipeline.
check_arch() {
  local so="$1" abi="$2" machine want
  machine="$("$READELF" -h "$so" | sed -n 's/^ *Machine: *//p')"
  case "$abi" in
    arm64-v8a) want=AArch64 ;;
    x86_64) want='Advanced Micro Devices X86-64' ;;
  esac
  [ "$machine" = "$want" ] || fail "$so is built for '$machine', not $abi"
}

has_exports() {
  local so="$1" syms sym
  shift
  syms="$("$NM" -D --defined-only "$so")"
  for sym in "$@"; do
    grep -q -E " T $sym\$" <<<"$syms" || fail "$so is missing the export $sym"
  done
}

for abi in "${ABIS[@]}"; do
  if [ "$COLIBRI" = 1 ]; then
    so="$DIR/$abi/libc4.so"
    [ -f "$so" ] || fail "$so not found"
    check_arch "$so" "$abi"
    has_exports "$so" \
      c4_create_rpc_ctx c4_free_rpc_ctx c4_rpc_execute_json_status \
      c4_req_set_response c4_req_set_error c4_set_storage_config \
      c4_get_file_storage_plugin c4_get_current_version_number \
      c4_rpc_set_min_latest_block_ts
  else
    so="$DIR/$abi/libfreedom_mobile_ffi.so"
    [ -f "$so" ] || fail "$so not found"
    check_arch "$so" "$abi"
    grep -q 'SONAME.*libfreedom_mobile_ffi\.so' <<<"$("$READELF" -d "$so")" || fail "$so has no SONAME"
    has_exports "$so" \
      ant_init ant_start_gateway freedom_ipfs_node_new_with_data_dir \
      freedom_ipfs_node_start_gateway_online freedom_mobile_init_logging \
      freedom_tor_start freedom_tor_stop freedom_tor_status_json \
      freedom_tor_version freedom_tor_string_free
    grep -q -a uniffi_libradicle_uniffi_fn_func_start "$so" || fail "$so has no Radicle exports (was the radicle feature on?)"
  fi
  echo "check-native-libs: $so ok ($(du -h "$so" | cut -f1), sha256 $(sha256sum "$so" | cut -d" " -f1))"
done
