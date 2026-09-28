#!/usr/bin/env bash
# Build libc4.so — corpus.core's Colibri stateless verifier (#100) — for
# every ABI the app ships, from a checkout of corpus-core/colibri-stateless
# at the tag release.yml pins as COLIBRI_REF.
#
#   scripts/build-colibri.sh <colibri-stateless checkout> [out dir]
#
# [out dir] defaults to swarmnode/src/main/jniLibs (gitignored), where the
# swarmnode CMake step links libfreedom_colibri.so (the JNI shim,
# swarmnode/src/main/cpp/colibri_jni.c) against it and Gradle packages it.
# Without it the app still builds and runs: name resolution skips the
# Colibri tier and starts at the RPC quorum (see README § Colibri).
#
# The C core does no I/O of its own: the app runs every HTTP request it
# asks for (EnsColibri.kt), so no curl, no CLI, no server. The build's
# CMake fetches evmone, intx and blst at configure time.
set -euo pipefail

src="${1:?usage: $0 <colibri-stateless checkout> [out dir]}"
repo="$(cd "$(dirname "$0")/.." && pwd)"
out="${2:-$repo/swarmnode/src/main/jniLibs}"
src="$(cd "$src" && pwd)"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
# The NDK swarmnode pins (ndkVersion in swarmnode/build.gradle.kts), or
# the runner's default one on CI.
ndk="${ANDROID_NDK_ROOT:-$sdk/ndk/27.0.12077973}"
cmake="$(command -v cmake || echo "$sdk/cmake/3.22.1/bin/cmake")"
ninja="$(command -v ninja || echo "$sdk/cmake/3.22.1/bin/ninja")"
strip="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

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
