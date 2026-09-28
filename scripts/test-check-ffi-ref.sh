#!/usr/bin/env bash
# Offline tests for scripts/check-ffi-ref.sh's Cargo.toml and README rules.
set -uo pipefail
check="$(cd "$(dirname "$0")" && pwd)/check-ffi-ref.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail=0

expect() { # expect pass|fail name -- command...
  local want="$1" name="$2"; shift 3
  if "$@" >/dev/null 2>&1; then got=pass; else got=fail; fi
  if [ "$got" = "$want" ]; then echo "ok   $name"; else echo "FAIL $name (wanted $want, got $got)"; fail=1; fi
}

toml() { printf '%s\n' "$2" > "$tmp/$1.toml"; echo "$tmp/$1.toml"; }

expect pass "inline tag" -- "$check" --cargo-toml "$(toml a '[dependencies]
ant-ffi = { git = "https://x/ant", tag = "v1" }
serde = "1"')"
expect pass "multi-line table tag" -- "$check" --cargo-toml "$(toml b '[dependencies.ant-ffi]
git = "https://x/ant"
tag = "v1"')"
expect fail "inline git then rev" -- "$check" --cargo-toml "$(toml c '[dependencies]
ant-ffi = { git = "https://x/ant", rev = "abc" }')"
expect fail "inline rev before git" -- "$check" --cargo-toml "$(toml d '[dependencies]
ant-ffi = { rev = "abc", git = "https://x/ant" }')"
expect fail "inline branch" -- "$check" --cargo-toml "$(toml e '[dependencies]
ant-ffi = { git = "https://x/ant", branch = "main" }')"
expect fail "multi-line table rev" -- "$check" --cargo-toml "$(toml f '[dependencies.ant-ffi]
git = "https://x/ant"
rev = "abc"')"
expect fail "bare git (default branch)" -- "$check" --cargo-toml "$(toml g '[dependencies]
ant-ffi = { git = "https://x/ant" }')"
expect fail "tag plus rev" -- "$check" --cargo-toml "$(toml h '[dependencies]
ant-ffi = { git = "https://x/ant", tag = "v1", rev = "abc" }')"
expect fail "target-specific rev" -- "$check" --cargo-toml "$(toml i '[target.'"'"'cfg(target_os = "android")'"'"'.dependencies]
ant-ffi = { git = "https://x/ant", rev = "abc" }')"
expect fail "workspace dependency rev" -- "$check" --cargo-toml "$(toml j '[workspace.dependencies]
ant-ffi = { git = "https://x/ant", rev = "abc" }')"
expect fail "patch branch" -- "$check" --cargo-toml "$(toml k '[patch."https://x/ant"]
ant-ffi = { git = "https://y/ant", branch = "fix" }')"

readme() { printf '%s\n' "$2" > "$tmp/$1.md"; echo "$tmp/$1.md"; }
clone='git clone https://github.com/solardev-xyz/freedom-mobile-ffi.git /tmp/freedom-mobile-ffi'
expect pass "readme --branch matches" -- "$check" --readme "$(readme r1 "${clone/clone/clone --branch v1}")" v1
expect pass "readme checkout matches" -- "$check" --readme "$(readme r2 "$clone &&
  git -C /tmp/freedom-mobile-ffi checkout v1")" v1
expect fail "readme --branch stale" -- "$check" --readme "$(readme r3 "${clone/clone/clone --branch v0}")" v1
expect fail "readme checkout stale" -- "$check" --readme "$(readme r4 "$clone &&
  git -C /tmp/freedom-mobile-ffi checkout 4520355")" v1
expect fail "readme one of two stale" -- "$check" --readme "$(readme r5 "${clone/clone/clone --branch v1}
$clone &&
  git -C /tmp/freedom-mobile-ffi checkout v0")" v1
expect fail "readme clone with no ref" -- "$check" --readme "$(readme r6 "$clone")" v1
expect fail "readme no clone" -- "$check" --readme "$(readme r7 "nothing here")" v1

exit $fail
