#!/usr/bin/env bash
# Fail unless app/licences/bundled.json lists what libc4.so is built from
# at the COLIBRI_REF release.yml pins (#325): Colibri itself, the
# trezor-crypto copy it vendors, and the libraries its CMake fetches and
# links (blst, evmone, intx, zstd) at the tags it pins them to.
#
#   scripts/check-colibri-licences.sh <colibri-stateless checkout at COLIBRI_REF>
#
# The Gradle build already fails when bundled.json's Colibri version isn't
# COLIBRI_REF; this is the half it can't do offline: reading the pins out
# of the tag. Run by release.yml and ffi-ref.yml.
#
# Which of colibri's libs/ are linked depends on scripts/build-colibri.sh's
# flags (CURL, HTTP_SERVER and USE_MCL off): curl, libuv and llhttp belong
# to the server and the curl transport, mcl is the alternative to blst, and
# tommath isn't used by the verifier. That split was read off the link
# line of `ninja c4` at v3.0.0; a new directory under libs/ fails here until
# someone checks the same for it.
set -euo pipefail

SRC="$(cd "${1:?usage: $0 <colibri-stateless checkout>}" && pwd)"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The tag, as scripts/build-colibri.sh reads it (the repo's VERSION file
# isn't kept up to date).
TAG="$(git -C "$SRC" describe --tags --exact-match 2>/dev/null || true)"

python3 - "$SRC" "$REPO" "$TAG" <<'PY'
import json, os, re, sys

src, repo, tag = sys.argv[1:]
workflow = open(os.path.join(repo, ".github/workflows/release.yml"), encoding="utf-8").read()
ref = re.search(r"(?m)^  COLIBRI_REF: *(\S+)", workflow).group(1)
bundled = json.load(open(os.path.join(repo, "app/licences/bundled.json"), encoding="utf-8"))
listed = {c["colibri"]: c for c in bundled["components"] if "colibri" in c}
problems = []

if tag != ref:
    sys.exit(f"check-colibri-licences: {src} is at {tag or 'no tag'}, release.yml pins COLIBRI_REF {ref}")
version = tag.lstrip("v")

LINKED = {"blst", "crypto", "evmone", "intx", "zstd"}
NOT_LINKED = {"curl", "libuv", "llhttp", "mcl", "tommath"}
libs = {d for d in os.listdir(os.path.join(src, "libs")) if os.path.isdir(os.path.join(src, "libs", d))}
for new in sorted(libs - LINKED - NOT_LINKED):
    problems.append(f"libs/{new} is new at {ref}: check whether libc4.so links it, then list it in "
                    "app/licences/bundled.json or under NOT_LINKED in this script")
for gone in sorted((LINKED | NOT_LINKED) - libs):
    problems.append(f"libs/{gone} is gone at {ref}: drop it from this script (and from bundled.json if it's listed)")

for dep in sorted(LINKED | {"self"}):
    if dep not in listed:
        problems.append(f"app/licences/bundled.json has no component with \"colibri\": \"{dep}\"")
if "self" in listed and listed["self"]["version"] != version:
    problems.append(f"bundled.json has Colibri {listed['self']['version']}, the checkout is {version}")

# The fetched libraries: every tag their CMakeLists.txt pins (Android and
# the other platforms fetch the same one) must be the version listed.
for dep in ("blst", "evmone", "intx", "zstd"):
    cmake = open(os.path.join(src, "libs", dep, "CMakeLists.txt"), encoding="utf-8").read()
    tags = set(re.findall(r'GIT_TAG\s+"?v?([0-9][^"\s)]*)', cmake))
    tags |= set(re.findall(r'/archive/refs/tags/v?([0-9][^/"\s]*?)\.tar\.gz', cmake))
    have = listed.get(dep, {}).get("version")
    if tags != {have}:
        problems.append(f"libs/{dep} pins {sorted(tags) or 'nothing readable'} at {ref}, bundled.json lists {have}")

# Colibri's own licence files, compared with the copies the app shows.
for path, text in (("LICENSE", "bundled/colibri-stateless.txt"), ("libs/crypto/LICENSE", "bundled/trezor-crypto.txt")):
    theirs = open(os.path.join(src, path), encoding="utf-8").read().strip()
    ours = open(os.path.join(repo, "app/licences", text), encoding="utf-8").read().strip()
    if theirs != ours:
        problems.append(f"{path} at {ref} differs from app/licences/{text}: copy it over")

if problems:
    sys.exit("check-colibri-licences:\n  " + "\n  ".join(problems))
print(f"check-colibri-licences: app/licences/bundled.json matches Colibri {ref}")
PY
