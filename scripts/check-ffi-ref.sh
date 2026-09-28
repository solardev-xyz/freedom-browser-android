#!/usr/bin/env bash
# Fail unless release.yml's FFI_REF is a published freedom-mobile-ffi
# tag whose Cargo.toml pins every git dependency by tag, not by rev.
#
# A pin on a branch commit (or on an ffi build that pins unmerged
# ant/freedom-ipfs revs) works until upstream squash-merges and deletes
# the branch; then the SHA is unfetchable and both the release job's
# checkout and the README's local build steps break. This check runs on
# every PR (ffi-ref.yml) and before every release (release.yml), so an
# UNRELEASED pin can't be merged or shipped by accident.
#
# Usage: scripts/check-ffi-ref.sh [FFI_REF]  (default: read release.yml)
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
ref="${1:-$(sed -n 's/^  FFI_REF: *//p' "$repo_root/.github/workflows/release.yml" | head -n1)}"
ffi="https://github.com/solardev-xyz/freedom-mobile-ffi"

if [ -z "$ref" ]; then
  echo "::error::No FFI_REF found in .github/workflows/release.yml" >&2
  exit 1
fi

if [ -z "$(git ls-remote --tags "$ffi" "refs/tags/$ref")" ]; then
  echo "::error::FFI_REF '$ref' is not a freedom-mobile-ffi tag. Tag freedom-mobile-ffi (with its node deps pinned by tag) and set FFI_REF and the README's clone lines to that tag before merging." >&2
  exit 1
fi

cargo_toml="$(curl -fsSL "https://raw.githubusercontent.com/solardev-xyz/freedom-mobile-ffi/refs/tags/$ref/Cargo.toml")"
revs="$(printf '%s\n' "$cargo_toml" | grep -E 'git *= *"[^"]*".*\brev *=' || true)"
if [ -n "$revs" ]; then
  echo "::error::freedom-mobile-ffi $ref pins git dependencies by rev, not tag:" >&2
  printf '%s\n' "$revs" >&2
  exit 1
fi

echo "FFI_REF $ref: tagged, git deps pinned by tag"
