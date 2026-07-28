# Freedom — Swarm Browser for Android

A native Android browser that loads both regular `https://` sites and decentralised content addressed via `bzz://` hashes or `ens://` names. Both embedded nodes — [ant](https://github.com/solardev-xyz/ant), a Swarm light-node in Rust serving a local bee-shaped HTTP gateway on `127.0.0.1:1633`, and the [freedom-ipfs](https://github.com/solardev-xyz/freedom-ipfs) reader serving `ipfs://` / `ipns://` on an ephemeral loopback port — ship as one combined Rust library, `libfreedom_mobile_ffi.so`, built from [freedom-mobile-ffi](https://github.com/solardev-xyz/freedom-mobile-ffi). The WebView sees ordinary `http://127.0.0.1:…` URLs.

- **Package:** `baby.freedom.mobile` · **Version:** 0.4.0
- **Inspired by:** [`Solar-Punk-Ltd/swarm-mobile-android`](https://github.com/Solar-Punk-Ltd/swarm-mobile-android)

## Install

Download the latest APK from [GitHub Releases](https://github.com/solardev-xyz/freedom-browser-android/releases): `arm64-v8a` for phones/tablets, `x86_64` for emulators, `universal` if unsure. Android will prompt to allow installs from your browser or file manager the first time ("install unknown apps"). Every release is signed with the project key, so newer releases install as updates over older ones; `SHA256SUMS` in each release verifies the download.

### Cutting a release (maintainers)

1. Bump `versionCode` + `versionName` in `app/build.gradle.kts` (and the version above).
2. Tag and push: `git tag v0.x.y && git push origin v0.x.y`.
3. [`release.yml`](.github/workflows/release.yml) builds `libfreedom_mobile_ffi.so` at the pinned `FFI_REF`, assembles signed per-ABI APKs (signing key lives in repo secrets), and publishes them with `SHA256SUMS`. When upgrading the embedded nodes, bump `FFI_REF` together with the vendored headers.

## Requirements

| Component | Version | Notes |
|---|---|---|
| JDK | 17 | Matches `sourceCompatibility` / `targetCompatibility` / `jvmTarget` in the Gradle files. |
| Android SDK | API 36 | `compileSdk = 36`, `targetSdk = 36`, `minSdk = 30` (Android 11+). |
| Android Build Tools | 36.0.0 | Installed via `sdkmanager "build-tools;36.0.0"`. |
| Gradle | 8.13 | Pinned via the wrapper; no global install needed. |
| Kotlin | 2.1.10 | Managed by Gradle plugin. |
| Android Gradle Plugin | 8.13.2 | Managed by Gradle plugin. |

Building the embedded-node artifact (required — not checked in) additionally requires:

| Component | Version | Notes |
|---|---|---|
| Rust | pinned by `rust-toolchain.toml` in freedom-mobile-ffi | Compiles `libfreedom_mobile_ffi.so` (ant + freedom-ipfs in one cdylib) — see [Building libfreedom_mobile_ffi.so](#building-libfreedom_mobile_ffiso). |
| cargo-ndk | latest | `cargo install cargo-ndk` — used by freedom-mobile-ffi's `scripts/build-android.sh`. |
| Android NDK | r27+ | Installed via `sdkmanager "ndk;27.2.12479018"` or similar. Also builds the JNI shims in `swarmnode/src/main/cpp/`. |

### One-time environment setup (macOS with Homebrew)

```bash
brew install --cask temurin@17
brew install --cask android-commandlinetools
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

The repo ships an [`.envrc.example`](./.envrc.example) that points `JAVA_HOME`, `ANDROID_HOME`, and `PATH` at Homebrew-installed toolchains. Copy it to `.envrc` (which is gitignored) and adjust for your machine:

```bash
cp .envrc.example .envrc
source .envrc   # or use direnv for automatic activation
```

## Quick start

Fresh clone, from zero to a running app:

```bash
# 1. Activate the toolchain env (JDK 17 + Android SDK).
source .envrc   # if you haven't: cp .envrc.example .envrc && edit to taste

# 2. Build the combined embedded-node library (ant/Swarm + freedom-ipfs).
#    Produces target/android/jniLibs/{arm64-v8a,x86_64}/libfreedom_mobile_ffi.so,
#    which is gitignored here and must exist before Gradle can build the app.
#    Needs cargo-ndk and ANDROID_NDK_HOME; rustup picks the toolchain from
#    the repo's rust-toolchain.toml.
git clone https://github.com/solardev-xyz/freedom-mobile-ffi.git /tmp/freedom-mobile-ffi
( cd /tmp/freedom-mobile-ffi && ./scripts/build-android.sh )
mkdir -p swarmnode/src/main/jniLibs
cp -r /tmp/freedom-mobile-ffi/target/android/jniLibs/. swarmnode/src/main/jniLibs/

# 3. Build the debug APK.
./gradlew :app:assembleDebug

# 4. Install on a connected device or running emulator.
./gradlew :app:installDebug
# or, for a slim per-ABI APK on a physical arm64 device:
#   adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

The build produces three debug APKs — `app-arm64-v8a-debug.apk` (~157 MiB), `app-x86_64-debug.apk` (~189 MiB), and `app-universal-debug.apk` (~456 MiB, all ABIs). See [APK size](#apk-size) for what to ship.

## Running on an emulator

No emulator-specific configuration is needed (see [DHT bootstrap](#dht-bootstrap) below for why):

```bash
# Create a Pixel AVD with Android 16 (API 36), arm64 on Apple Silicon.
avdmanager create avd -n freedom -k "system-images;android-36;google_apis;arm64-v8a"
emulator -avd freedom &

./gradlew :app:installDebug
adb shell monkey -p baby.freedom.mobile 1
```

Expected behaviour on cold start:

- Address bar is blank and the home page renders from the app's assets; the status dot is amber (node starting).
- Within ~25 s the dot turns green; peer count ramps to ~80+ within 60 s.
- Tap the status dot to see peer count and gateway URL.

Verify the gateway from the host:

```bash
adb forward tcp:1633 tcp:1633
curl http://127.0.0.1:1633/health      # {"status":"ok","version":"..."}
curl http://127.0.0.1:1633/status      # beeMode=ultra-light, ...
```

### DHT bootstrap

Swarm's default bootnode is `/dnsaddr/mainnet.ethswarm.org`, which requires multi-step TXT-record resolution — something mobile DNS stacks routinely fumble (the old bee-lite integration needed hard-coded pre-resolved multiaddrs for exactly this reason). ant's dnsaddr resolver walks the whole TXT tree itself and appends Cloudflare's `1.1.1.1` as a fallback nameserver whenever the system resolver config is unreadable — which is always the case on Android — so bootstrap works out of the box on the emulator, no pre-resolved bootnode list required.

## Swarm content retrieval

A fresh Swarm node pulls chunks on demand through the DHT, and any individual chunk lookup can transiently fail (HTTP `404 {"address not found or incorrect"}`) even when the content is healthy and plenty of peers are connected. A typical Swarm-hosted site loads 10–30 sub-resources; a modest per-request failure rate compounds into broken CSS, missing images, and videos that don't load. Retries almost always succeed — the problem is strictly first contact with cold content.

Freedom handles this in two layers:

1. **Navigation-time probe.** `GatewayProbe` HEAD-polls `/bzz/<hash>` before the WebView loads, with escalating delays, a 5-minute budget, and a grace window for `ECONNREFUSED` during node startup. The tab's spinner stays active while the probe runs; on timeout or unreachable the tab routes to `assets/error/error.html` with a **Try Again** button that re-enters the probe.
2. **Native request interception.** Sub-resource fetches go through `WebViewClient.shouldInterceptRequest` in `BrowserWebView.kt`, which:
    - Retries transient `404` / `5xx` from `/bzz/`, `/ipfs/`, `/ipns/` with bounded backoff (~17 s across 8 attempts, inside the WebView's ~30 s request-hang window).
    - Rewrites absolute-root paths like `/_next/static/…` back under the current `/bzz/<hash>/` root (Next.js-style sites reference sub-resources this way and would otherwise 404).
    - Synthesises proper `206 Partial Content` for `<video>` / `<audio>` `Range` requests by fetching the body once into a small in-process LRU and slicing it. (Load-bearing under bee, which returned the full body for every Range; ant serves real single-range 206s, but the local buffer still means seeking never re-fetches.)
    - Stamps every outgoing fetch with `Swarm-Chunk-Retrieval-Timeout: 30s`, `Swarm-Redundancy-Strategy: 3`, `Swarm-Redundancy-Fallback-Mode: true` — the same retrieval hints bee honors, parsed by ant for parity.

Unlike the Electron-based desktop port, Android WebView does not allow registering a custom `bzz:` scheme as a first-class origin (there is no `session.protocol.handle` equivalent). So the WebView loads the gateway URL directly (`http://127.0.0.1:1633/bzz/<hash>/…`) and `BrowserState.currentBzzRoot` tracks the active root so the interceptor can rewrite absolute-root paths at request time.

## Project layout

```
freedom-browser-android/
├── app/                          # Android application (Compose + Material 3)
│   └── src/main/java/baby/freedom/mobile/
│       ├── MainActivity.kt       # hosts the Compose tree, binds NodeService
│       ├── browser/              # tabs, address bar, WebView, resolver
│       ├── ens/                  # Keccak256, ENS contenthash, Universal Resolver
│       └── node/NodeService.kt   # foreground service owning the Swarm node
├── swarmnode/                    # Kotlin wrapper around the embedded nodes
│   ├── src/main/jniLibs/         # libfreedom_mobile_ffi.so per ABI — combined ant + freedom-ipfs (gitignored)
│   ├── src/main/cpp/             # vendored ant.h + freedom_ipfs.h, JNI shims over both C APIs
│   └── src/main/java/baby/freedom/swarm/
│       ├── SwarmNode.kt          # ant lifecycle + StateFlow<NodeInfo>
│       ├── AntNative.kt          # raw JNI surface over the ant C API
│       ├── IpfsNode.kt           # freedom-ipfs lifecycle + StateFlow<IpfsInfo>
│       ├── FreedomIpfsNative.kt  # raw JNI surface over the freedom-ipfs C API
│       ├── NodeInfo.kt           # status, peers, error
│       └── NodeStatus.kt         # Stopped | Starting | Running | Error
├── TODO.md                       # todo + deferred work
├── build.gradle.kts              # plugin versions
├── settings.gradle.kts           # module wiring
└── .envrc.example                # JAVA_HOME / ANDROID_HOME pointers for macOS
```

Two Gradle modules:
- `:app` — the Android application.
- `:swarmnode` — a self-contained Android library wrapping both embedded nodes (`libfreedom_mobile_ffi.so` + the JNI shims), depended on by `:app`. Designed to be publishable on its own.

## Common tasks

```bash
./gradlew :app:assembleDebug           # debug APK
./gradlew :app:installDebug            # install on device/emulator
./gradlew :app:assembleRelease         # release APK (unsigned)
./gradlew :swarmnode:assembleRelease   # build the swarmnode .aar only

./gradlew clean                        # remove app/build + swarmnode/build
./gradlew --stop                       # kill background Gradle daemons
```

Reading the current APK's metadata:

```bash
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging app/build/outputs/apk/debug/app-arm64-v8a-debug.apk | head
# package: name='baby.freedom.mobile' versionCode='5' versionName='0.3.0'
```

## Building `libfreedom_mobile_ffi.so`

`libfreedom_mobile_ffi.so` is both embedded nodes in one Rust cdylib — the ant Swarm light-node plus the freedom-ipfs reader, compiled per ABI from [`solardev-xyz/freedom-mobile-ffi`](https://github.com/solardev-xyz/freedom-mobile-ffi). Combining them in a single compilation graph dedupes everything the two dependency trees share (std, tokio, hyper/axum, libp2p, ring, SQLite, …), which is ~7 MiB per ABI versus shipping two separate `.so`s. It's **not checked in**; every fresh clone builds it once:

```bash
# 1. Clone freedom-mobile-ffi somewhere outside this repo.
git clone https://github.com/solardev-xyz/freedom-mobile-ffi.git /tmp/freedom-mobile-ffi

# 2. Cross-compile both ABIs. Needs cargo-ndk + ANDROID_NDK_HOME; rustup
#    installs the pinned toolchain + targets from rust-toolchain.toml.
#    The script also verifies both C ABIs are exported and stages the
#    matching headers under target/android/headers/.
cd /tmp/freedom-mobile-ffi
./scripts/build-android.sh

# 3. Copy the results into Freedom.
cd <freedom-browser-android>
mkdir -p swarmnode/src/main/jniLibs
cp -r /tmp/freedom-mobile-ffi/target/android/jniLibs/. swarmnode/src/main/jniLibs/
```

The Kotlin side talks to it through the hand-written JNI shims in `swarmnode/src/main/cpp/` (built into `libfreedom_jni.so` by the module's CMake step): `ant_jni.c` wraps the ant C API (`ant_init`, `ant_start_gateway` — the bee-shaped HTTP gateway on `127.0.0.1:1633` —, `ant_peer_count`, `ant_shutdown`) and `freedom_ipfs_jni.c` wraps the freedom-ipfs loopback-gateway surface. When upgrading, refresh the vendored `swarmnode/src/main/cpp/{ant.h,freedom_ipfs.h}` from the build's `target/android/headers/` along with the `.so`s, and bump the pinned (ant, freedom-ipfs) tags in freedom-mobile-ffi's `Cargo.toml` — the same aggregator also feeds the iOS xcframework, so both platforms move versions together.

## APK size

The combined node library is ~20 MiB (arm64) / ~23 MiB (x86_64) and dominates the APK — everything else (dex, resources, the JNI shim) is under 6 MiB in a release build.

`app/build.gradle.kts` already enables per-ABI splits (`arm64-v8a` + `x86_64`) alongside a universal fallback, so every build produces:

| APK | Release | Debug | Use |
|---|---|---|---|
| `app-arm64-v8a-*.apk` | ~25 MiB | ~94 MiB | Physical arm64 devices, Apple Silicon emulators |
| `app-x86_64-*.apk` | ~28 MiB | ~98 MiB | x86_64 Android emulators |
| `app-universal-*.apk` | ~48 MiB | ~127 MiB | Fallback / `:installDebug` default |

(For context: shipping ant and freedom-ipfs as two separate `.so`s cost ~11 MiB more per ABI in duplicated Rust std/tokio/libp2p/SQLite; the gomobile-era APKs were 157–456 MiB.)

For distribution, Android App Bundles ship just the one ABI the device needs via Play Store's dynamic delivery:

```bash
./gradlew :app:bundleRelease
```

## Troubleshooting

**Gradle can't find `JAVA_HOME`.** Run `source .envrc` or set `JAVA_HOME` to a JDK 17 install. The wrapper requires it; there's no fallback.

**`./gradlew` downloads Gradle every invocation.** Your `GRADLE_USER_HOME` is set to an ephemeral path or you're offline. Point it at a persistent directory (default: `~/.gradle`).

**Node never reaches `Running` on emulator.** Check `adb logcat -s SwarmNode` for errors. If the node runs but gathers no peers, the network may be blocking outbound TCP dials or UDP DNS to `1.1.1.1` (ant's bootstrap fallback). Try on a different network or a physical device.

**`UnsatisfiedLinkError` after a minified release build.** Make sure `swarmnode/consumer-rules.pro` is being honoured — it keeps the native method names on `baby.freedom.swarm.AntNative` and `baby.freedom.swarm.FreedomIpfsNative`, which the JNI shims resolve by exact symbol; R8 renames them without it.

**`UnsatisfiedLinkError` mentioning `libfreedom_mobile_ffi.so` or `libfreedom_jni.so`.** The prebuilt combined library for that ABI is missing from `swarmnode/src/main/jniLibs/` — see [Building libfreedom_mobile_ffi.so](#building-libfreedom_mobile_ffiso).

## Further reading

- [`TODO.md`](./TODO.md) — what's still open and deferred.
- [Swarm docs](https://docs.ethswarm.org/) — the Swarm network itself.
- [`ant`](https://github.com/solardev-xyz/ant) — the embedded Swarm light-node (Rust).
- [`freedom-ipfs`](https://github.com/solardev-xyz/freedom-ipfs) — the embedded IPFS reader (Rust).
- [`freedom-mobile-ffi`](https://github.com/solardev-xyz/freedom-mobile-ffi) — the aggregator that builds both into one library per platform (Android `.so`, iOS xcframework).

## License

TBD. Not yet decided.
