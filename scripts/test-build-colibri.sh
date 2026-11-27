#!/usr/bin/env bash
# Offline tests for which CMake/Ninja scripts/build-colibri.sh picks (#621):
# the Android SDK's, never the runner image's, chosen the same way on
# every runner.
set -uo pipefail
script="$(cd "$(dirname "$0")" && pwd)/build-colibri.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail=0

# A fake tool printing its version the way cmake/ninja do.
tool() { # tool <path> <version line>
  mkdir -p "$(dirname "$1")"
  printf '#!/bin/sh\necho "%s"\n' "$2" > "$1"; chmod +x "$1"
}
# A fake SDK with an NDK and the given CMake package versions.
sdk() { # sdk <name> [cmake version[:no-ninja]]...
  local d="$tmp/$1" v; shift
  mkdir -p "$d/ndk/27.0.12077973"
  echo "Pkg.Revision = 27.0.12077973" > "$d/ndk/27.0.12077973/source.properties"
  for v in "$@"; do
    tool "$d/cmake/${v%%:*}/bin/cmake" "cmake version ${v%%:*}"
    [[ "$v" == *:no-ninja ]] || tool "$d/cmake/${v%%:*}/bin/ninja" "sdk-ninja-${v%%:*}"
  done
  echo "$d"
}
# A PATH holding a system cmake 4.5.0 and ninja, as on ubuntu-26.04.
sys="$tmp/sys"; tool "$sys/cmake" "cmake version 4.5.0"; tool "$sys/ninja" "1.13.2"

expect() { # expect <name> <sdk dir> <wanted substring>
  local got
  got="$(ANDROID_HOME="$2" ANDROID_SDK_ROOT= ANDROID_NDK_ROOT= PATH="$sys:/usr/bin:/bin" "$script" --tools 2>&1)"
  if [[ "$got" == *"$3"* ]]; then echo "ok   $1"; else echo "FAIL $1: wanted '$3' in '$got'"; fail=1; fi
}

expect "3.22.1 preferred" "$(sdk a 3.22.1 3.31.5 4.1.2)" "cmake version 3.22.1 ($tmp/a/cmake/3.22.1/bin/cmake); ninja sdk-ninja-3.22.1"
expect "newest 3.x (GitHub runner)" "$(sdk b 4.1.2 3.31.5 3.10.2)" "cmake version 3.31.5 ($tmp/b/cmake/3.31.5/bin/cmake); ninja sdk-ninja-3.31.5"
expect "numeric, not lexical order" "$(sdk c 3.9.0 3.18.1)" "cmake version 3.18.1"
expect "only 4.x" "$(sdk d 4.1.2 4.0.0)" "cmake version 4.1.2 ($tmp/d/cmake/4.1.2/bin/cmake)"
expect "system ninja when the SDK has none" "$(sdk e 3.31.5:no-ninja)" "cmake version 3.31.5 ($tmp/e/cmake/3.31.5/bin/cmake); ninja 1.13.2"
expect "system cmake only without an SDK one" "$(sdk f)" "cmake version 4.5.0 ($sys/cmake); ninja 1.13.2"
expect "NDK in the key" "$(sdk g 3.31.5)" "NDK 27.0.12077973;"

exit "$fail"
