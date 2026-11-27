#!/usr/bin/env bash
# Build libc4.so — corpus.core's Colibri stateless verifier (#100) — for
# every ABI the app ships, from a checkout of corpus-core/colibri-stateless
# at the tag release.yml pins as COLIBRI_REF.
#
#   scripts/build-colibri.sh <colibri-stateless checkout> [out dir]
#   scripts/build-colibri.sh --tools   # print the NDK/CMake/Ninja it would use
#
# [out dir] defaults to swarmnode/src/main/jniLibs (gitignored), where the
# swarmnode CMake step links libfreedom_colibri.so (the JNI shim,
# swarmnode/src/main/cpp/colibri_jni.c) against it and Gradle packages it.
# Without it the app still builds and runs: chain reads skip the Colibri
# tier and start at the RPC quorum (see README § Colibri).
#
# The C core does no I/O of its own: the app runs every HTTP request it
# asks for (EnsColibri.kt), so no curl, no CLI, no server. The build's
# CMake fetches evmone, intx and blst at configure time.
set -euo pipefail

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
# The NDK swarmnode pins (ndkVersion in swarmnode/build.gradle.kts), or
# the runner's default one on CI.
ndk="${ANDROID_NDK_ROOT:-$sdk/ndk/27.0.12077973}"
# The Android SDK's CMake, not the system one, so the build doesn't change
# with the runner image (#621: ubuntu-26.04's CMake 4.5 broke it). The
# 3.22.1 Gradle uses for swarmnode if it's there, else the newest 3.x the
# SDK has (3.31.5 on GitHub's runners; 4.x drops compatibility with the
# old cmake_minimum_required of fetched deps), else its newest of any
# version, and only then whatever cmake is on PATH. Its own ninja if it
# ships one, else the system's.
sdk_cmake=""
if [[ -x "$sdk/cmake/3.22.1/bin/cmake" ]]; then
  sdk_cmake="$sdk/cmake/3.22.1"
else
  versions="$(ls "$sdk/cmake" 2>/dev/null | sort -V || true)"
  v="$(grep '^3\.' <<<"$versions" | tail -1 || true)"
  v="${v:-$(tail -1 <<<"$versions")}"
  [[ -n "$v" && -x "$sdk/cmake/$v/bin/cmake" ]] && sdk_cmake="$sdk/cmake/$v"
fi
if [[ -n "$sdk_cmake" ]]; then
  cmake="$sdk_cmake/bin/cmake"
else
  cmake="$(command -v cmake || true)"
fi
if [[ -n "$sdk_cmake" && -x "$sdk_cmake/bin/ninja" ]]; then
  ninja="$sdk_cmake/bin/ninja"
else
  ninja="$(command -v ninja || true)"
fi
if [[ -z "$cmake" || -z "$ninja" ]]; then
  echo "error: no CMake/Ninja in $sdk/cmake or on PATH" >&2
  exit 1
fi
strip="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

# The tools that shape libc4.so, for release.yml's cache key.
if [[ "${1:-}" == "--tools" ]]; then
  echo "NDK $(sed -n 's/^Pkg.Revision *= *//p' "$ndk/source.properties"); $("$cmake" --version | head -1) ($cmake); ninja $("$ninja" --version)"
  exit 0
fi

src="${1:?usage: $0 <colibri-stateless checkout> [out dir]}"
repo="$(cd "$(dirname "$0")/.." && pwd)"
out="${2:-$repo/swarmnode/src/main/jniLibs}"
src="$(cd "$src" && pwd)"

# The proof format a prover sends is chosen by the version the client
# reports, so it must be the tag's own, not git describe's guess.
version="${C4_VERSION:-$(git -C "$src" describe --tags --exact-match 2>/dev/null | sed 's/^v//')}"
if [[ -z "$version" ]]; then
  echo "error: $src is not checked out at a release tag; set C4_VERSION" >&2
  exit 1
fi

for abi in arm64-v8a x86_64; do
  build="$src/build/android-$abi"
  "$cmake" -S "$src" -B "$build" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM=android-30 \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_SCAN_FOR_MODULES=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    -DC4_VERSION="$version" \
    -DSHAREDLIB=ON -DCLI=OFF -DTEST=OFF -DCURL=OFF -DHTTP_SERVER=OFF \
    -DUSE_MCL=OFF -DETH_ZKPROOF=ON -DCHAIN_OP=ON
  "$cmake" --build "$build" --target c4
  mkdir -p "$out/$abi"
  "$strip" -o "$out/$abi/libc4.so" "$build/lib/libc4.so"
  echo "built $out/$abi/libc4.so (colibri $version)"
done
