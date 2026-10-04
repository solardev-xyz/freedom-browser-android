# Open-source licences

Settings → About → **Open-source licences** (#325) lists every third-party
component in the APK, with its licence text. It reads one asset,
`licences/licences.json`, which `:app:generateLicences<Variant>`
(`app/build.gradle.kts`) builds on every assemble from:

| Source | What | Kept current by |
|---|---|---|
| AboutLibraries' scan of the variant's runtime classpath | Gradle dependencies (AndroidX, Compose, OkHttp, ZXing, JNA, …) and the licences their POMs name | Generated at build time |
| `gradle.json` + `texts/` + `gradle/` | The text for each licence: `generic` maps a licence whose text is the same for every library (Apache-2.0, LGPL-2.1, the Android SDK licence) to `texts/`; `notices` gives a library under any other licence (MIT, BSD, …) its own licence file, copyright line included | Hand-kept; the build checks it |
| `native.json` | The third-party Rust crates in `libfreedom_mobile_ffi.so`: 776 at `FFI_REF` v0.12.11, with the licence text(s) each ships under, its source (crates.io, or the git commit Cargo.lock pins), and `native` on the ones that compile C code or link a native library. Freedom's own crates (freedom-mobile-ffi and its in-tree path crates, ant, freedom-ipfs, libradicle) aren't listed | `scripts/native-licences.sh` (cargo-about) |
| `bundled.json` + `bundled/` | What Colibri links into `libc4.so` (Colibri, its vendored trezor-crypto, blst, evmone, intx, zstd); the C code the `native` crates compile into `libfreedom_mobile_ffi.so` (libgit2 with LibXDiff, PCRE2 and llhttp; XZ Utils' liblzma; SQLite), each naming its crate with `inCrate`, and libffi in JNA's `libjnidispatch.so` (`inGradle`); the OpenLV bundle (`assets/openlv/openlv.esm.js`: @openlv/*, websocket-mqtt, @noble/*, eventemitter3, ts-pattern) and the filter lists. Also `own`, the assets that are Freedom's own; `nativeCode`, what each `native` crate compiles, by crate and version; and `nativeLibraries`, whose code each `.so` in the APK is | Hand-kept; the build, `scripts/native-licences.sh` and `scripts/check-colibri-licences.sh` check it |

Nothing is fetched at build time: AboutLibraries runs with `offlineMode`, and
every text comes from this directory.

## What fails, and where

`generateLicences<Variant>` fails the build (every assemble, debug too) when:

- a Gradle dependency has no licence in its POM;
- a dependency's licence isn't under `generic` and the library has no entry under `notices` (a new MIT or BSD library, or a licence nobody has looked at);
- `notices` names a library that's no longer a dependency;
- a listed text file is missing;
- `native.json` was generated for another `FFI_REF` than `release.yml` pins;
- a `native` crate (one that compiles C, C++ or assembly, or links a native library) has no `nativeCode` entry at its exact version, or `nativeCode` has one for a crate that's gone or changed version;
- a component's `inCrate` crate or `inGradle` dependency isn't there at that version (so a bump makes someone look at what it vendors now);
- `bundled.json`'s Colibri isn't `release.yml`'s `COLIBRI_REF`.

`checkLicencedFiles<Variant>` (run before packaging) reads what the APK actually ships, after merging every source set, generated directory and library, and fails when:

- an asset is neither in `own` nor in a component's `files`, or one listed there isn't shipped;
- a `.so` isn't in `nativeLibraries`, or one listed there isn't shipped (except one in `optionalNativeLibraries`: `libc4.so`, which a local build may leave out — README step 3; then `generateLicences` leaves the components marked with its key, `colibri`, out of the list, and still checks them against `COLIBRI_REF`).

The `licences` job (`ffi-ref.yml` on every PR and push to `main`; `release.yml`,
which won't publish without it) goes further with the two external sources:

- `scripts/native-licences.sh <freedom-mobile-ffi at FFI_REF> --check` regenerates the crate list and fails if `native.json` differs, if a crate's licence isn't one of `scripts/native-licences.toml`'s `accepted` (a new copyleft crate stops here), or if a text `bundled.json` took from a crate's sources (`crateFiles`) no longer matches that file;
- `scripts/check-colibri-licences.sh <colibri-stateless at COLIBRI_REF>` reads the tags Colibri's CMake pins its libraries to and compares them, and Colibri's own licence files, with `bundled.json`; a new directory under Colibri's `libs/` fails until someone decides whether it's linked.

## When you…

- **add a Gradle dependency** under Apache-2.0: nothing to do. Under MIT, BSD or anything else: put the library's own licence file in `gradle/<group>_<artifact>.txt` and list it under `notices`.
- **bump `FFI_REF`**: after checking out the new tag, `scripts/native-licences.sh /path/to/freedom-mobile-ffi` and commit `native.json`. If the build then names a `native` crate, read its `build.rs` (what it compiles for an Android target, with the features enabled) and its bundled sources' licence files; list any third-party C code under `components` with `inCrate`, copying the licence file into `bundled/` and naming it under `crateFiles`, and write what you found under `nativeCode`. It needs [cargo-about](https://github.com/EmbarkStudios/cargo-about) 0.9.2 on `PATH` (the release binary, as the CI job installs it); it patches the checkout's `build-android.sh` with the `enable-ffi-*.sh` helpers, as `scripts/build-ffi.sh` does.
- **bump `COLIBRI_REF`**: update the Colibri entries in `bundled.json` (versions, and `bundled/` if a licence file changed) and run `scripts/check-colibri-licences.sh` on a checkout of the new tag.
- **add a dependency that ships a `.so`**: find what's statically linked into it (JNA's `libjnidispatch.so` carries libffi), list that, and add the library to `nativeLibraries`.
- **re-vendor OpenLV or add an asset**: list it in `bundled.json` with its licence files, or under `own` if it's Freedom's own.
