#!/usr/bin/env bash
# Write app/licences/native.json: every Rust crate linked into
# libfreedom_mobile_ffi.so at the FFI_REF release.yml pins, with the
# licence text(s) it's shipped under (#325). The app's Gradle build folds
# it into Settings → About → Open-source licences, and fails when its
# FFI_REF isn't release.yml's (see app/licences/README.md).
#
#   scripts/native-licences.sh <freedom-mobile-ffi checkout>           rewrite the committed file
#   scripts/native-licences.sh <freedom-mobile-ffi checkout> --check   fail if it's stale
#
# The checkout must be at the FFI_REF tag. The crate graph is the one the
# release builds: this runs the same enable-ffi-*.sh helpers as
# scripts/build-ffi.sh (idempotent, so a checkout that was already built
# is fine) and reads the feature list back from the build script they
# patched, for both Android targets.
#
# Needs cargo (rustup picks the checkout's rust-toolchain.toml) and
# cargo-about $CARGO_ABOUT_VERSION on PATH (release.yml and ffi-ref.yml
# download the pinned release binary). The crate sources are fetched
# first and the licence scan then runs --offline: licence files come from
# the published crates only, never from a crate's git repository at
# whatever its default branch says today, so the output is a function of
# FFI_REF alone.
set -euo pipefail

CARGO_ABOUT_VERSION=0.9.2

FFI_DIR="$(cd "${1:?usage: $0 <path-to-freedom-mobile-ffi> [--check]}" && pwd)"
MODE="${2:-write}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
OUT="$REPO/app/licences/native.json"

case "$MODE" in write|--check) ;; *) echo "native-licences: unknown option $MODE" >&2; exit 2 ;; esac

have="$(cargo-about --version 2>/dev/null || true)"
if [ "$have" != "cargo-about $CARGO_ABOUT_VERSION" ]; then
  echo "native-licences: need cargo-about $CARGO_ABOUT_VERSION on PATH (found: ${have:-none})" >&2
  exit 1
fi

want_ref="$(sed -n 's/^  FFI_REF: *//p' "$REPO/.github/workflows/release.yml")"
ref="$(git -C "$FFI_DIR" describe --tags --exact-match 2>/dev/null || true)"
if [ -z "$want_ref" ] || [ "$ref" != "$want_ref" ]; then
  echo "native-licences: $FFI_DIR is at '${ref:-no tag}', release.yml pins FFI_REF '$want_ref'" >&2
  exit 1
fi

"$HERE/enable-ffi-chain.sh" "$FFI_DIR" >/dev/null
"$HERE/enable-ffi-radicle.sh" "$FFI_DIR" >/dev/null
"$HERE/enable-ffi-tor.sh" "$FFI_DIR" >/dev/null
features="$(grep -v -E '^[[:space:]]*#' "$FFI_DIR/scripts/build-android.sh" \
  | sed -n 's/.*--no-default-features --features \([A-Za-z0-9_,-]*\) --crate-type.*/\1/p' | sort -u)"
if [ -z "$features" ] || [ "$(printf '%s\n' "$features" | wc -l)" != 1 ]; then
  echo "native-licences: can't read one feature list from $FFI_DIR/scripts/build-android.sh; update this script" >&2
  exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

( cd "$FFI_DIR" && cargo fetch --locked >/dev/null )
( cd "$FFI_DIR" && cargo-about generate --format json --locked --offline \
    -c "$HERE/native-licences.toml" \
    --no-default-features --features "${features//,/ }" \
    -o "$tmp/about.json" 2> "$tmp/about.log" ) || { cat "$tmp/about.log" >&2; exit 1; }

python3 - "$tmp/about.json" "$tmp/native.json" "$ref" "$features" "$REPO/app/licences" <<'PY'
import json, os, re, sys

src, dst, ref, features, licences = sys.argv[1:]
about = json.load(open(src, encoding="utf-8"))

# Crates that are Freedom's own code rather than third-party components:
# freedom-mobile-ffi itself and anything else in its tree (a path
# dependency has no source; vendor/sqlite3-src is a no-op stand-in), and
# the Freedom projects it pulls from git. Not listed.
OWN_GIT = ("github.com/freedom-hq/ant", "github.com/solardev-xyz/freedom-ipfs", "github.com/solardev-xyz/libradicle")

def git_repo(source):
    # "git+ssh://git@github.com/o/r.git?tag=v1#<sha>" -> ("github.com/o/r", "<sha>")
    m = re.match(r"git\+(?:ssh://git@|https://)([^?#]+?)(?:\.git)?(?:\?[^#]*)?#([0-9a-f]+)$", source)
    return (m.group(1), m.group(2)) if m else (None, None)

def is_own(pkg):
    source = pkg.get("source")
    if source is None:
        return True
    repo, _ = git_repo(source)
    return repo in OWN_GIT

# A crate that compiles C/C++/assembly (a build dependency on cc or
# cmake) or links a native library (`links`) can carry code under another
# licence than its own Cargo.toml says: libgit2-sys builds libgit2 with
# LibXDiff and PCRE2, liblzma-sys builds XZ Utils. The build makes
# bundled.json account for every one of these, at its version.
NATIVE_BUILD = {"cc", "cmake", "autotools", "nasm-rs", "cxx-build"}

def builds_native(pkg):
    return bool(pkg.get("links")) or any(
        d.get("kind") == "build" and d["name"] in NATIVE_BUILD for d in pkg.get("dependencies", []))

texts = {}   # (id, name, text) -> set of crate ids
for lic in about["licenses"]:
    key = (lic["id"], lic["name"], lic["text"].strip("\n") + "\n")
    for used in lic["used_by"]:
        texts.setdefault(key, set()).add(used["crate"]["id"])

ordered = sorted(texts)
index = {key: i for i, key in enumerate(ordered)}

crates, missing, dirs = [], [], {}
for entry in about["crates"]:
    pkg = entry["package"]
    if is_own(pkg):
        continue
    mine = sorted(index[k] for k, ids in texts.items() if pkg["id"] in ids)
    if not mine:
        missing.append(f'{pkg["name"]} {pkg["version"]} ({pkg.get("license") or "no licence field"})')
        continue
    source = pkg.get("source") or ""
    repo, rev = git_repo(source)
    if source.startswith("registry+"):
        url = f'https://crates.io/crates/{pkg["name"]}/{pkg["version"]}'
    elif repo:
        # The exact commit Cargo.lock pins, not the default branch.
        url = f"https://{repo}/tree/{rev}"
    else:
        url = pkg.get("repository") or pkg.get("homepage") or ""
    if not url:
        sys.exit(f'native-licences: no source URL for {pkg["name"]} {pkg["version"]} ({source or "no source"}); update this script')
    crate = {
        "name": pkg["name"],
        "version": pkg["version"],
        "licence": pkg.get("license") or "",
        "url": url,
        "texts": mine,
    }
    if builds_native(pkg):
        crate["native"] = True
        dirs[(pkg["name"], pkg["version"])] = os.path.dirname(pkg["manifest_path"])
    crates.append(crate)

# The C code those crates compile in is listed in bundled.json, each
# component naming the crate it's in; a text taken from the crate's own
# sources ("crateFiles") must still be that file, byte for byte.
bundled = json.load(open(os.path.join(licences, "bundled.json"), encoding="utf-8"))
stale = []
for c in bundled["components"]:
    if "inCrate" not in c:
        continue
    name, _, version = c["inCrate"].partition(" ")
    where = dirs.get((name, version))
    if where is None:
        stale.append(f'{c["name"]}: bundled.json says it\'s in {c["inCrate"]}, which isn\'t a native-building crate at FFI_REF {ref}')
        continue
    for text, path in c.get("crateFiles", {}).items():
        mine = open(os.path.join(licences, text), encoding="utf-8").read().strip("\n")
        theirs = open(os.path.join(where, path), encoding="utf-8").read().strip("\n")
        if mine != theirs:
            stale.append(f'app/licences/{text} ({c["name"]}) differs from {c["inCrate"]}\'s {path}: copy it over')
if stale:
    sys.exit("native-licences: bundled.json's C code in Rust crates is out of date:\n  " + "\n  ".join(stale))

if missing:
    sys.exit("native-licences: no accepted licence text for:\n  " + "\n  ".join(sorted(missing))
             + "\n(add the licence to scripts/native-licences.toml's `accepted` once it's been checked,"
             + " or clarify the crate there)")

# Only the texts a listed crate uses (Freedom's own crates are left out).
used = sorted({i for c in crates for i in c["texts"]})
remap = {old: new for new, old in enumerate(used)}
for c in crates:
    c["texts"] = [remap[i] for i in c["texts"]]
ordered = [ordered[i] for i in used]

crates.sort(key=lambda c: (c["name"], c["version"]))
out = {
    "generatedBy": "scripts/native-licences.sh",
    "ffiRef": ref,
    "features": features.split(","),
    "crates": crates,
    "texts": [{"id": i, "name": n, "text": t} for (i, n, t) in ordered],
}
with open(dst, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
    f.write("\n")
print(f"native-licences: {len(crates)} crates, {len(ordered)} licence texts (FFI_REF {ref}, features {features})")
PY

if [ "$MODE" = --check ]; then
  if ! cmp -s "$tmp/native.json" "$OUT"; then
    echo "native-licences: app/licences/native.json is stale for FFI_REF $ref; rerun scripts/native-licences.sh and commit it" >&2
    diff -u "$OUT" "$tmp/native.json" | grep -E '^[-+] *"(name|version|licence|ffiRef)"' | head -40 >&2 || true
    exit 1
  fi
  echo "native-licences: app/licences/native.json is current"
else
  cp "$tmp/native.json" "$OUT"
  echo "native-licences: wrote app/licences/native.json"
fi
