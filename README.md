# Freedom — Swarm Browser for Android

A native Android browser that loads both regular `https://` sites and decentralised content addressed via `bzz://` hashes or `ens://` names. An embedded [ant](https://github.com/solardev-xyz/ant) light-node (a Swarm client in Rust) runs inside the app and serves Swarm content through a local bee-shaped HTTP gateway; the WebView sees an ordinary `http://127.0.0.1:1633/bzz/…` URL. IPFS/IPNS content is served by an embedded Kubo node from the [freedom-node-mobile](https://github.com/solardev-xyz/freedom-node-mobile) AAR.

- **Package:** `baby.freedom.mobile` · **Version:** 0.3.0
- **Inspired by:** [`Solar-Punk-Ltd/swarm-mobile-android`](https://github.com/Solar-Punk-Ltd/swarm-mobile-android)

## Requirements

| Component | Version | Notes |
|---|---|---|
| JDK | 17 | Matches `sourceCompatibility` / `targetCompatibility` / `jvmTarget` in the Gradle files. |
| Android SDK | API 36 | `compileSdk = 36`, `targetSdk = 36`, `minSdk = 30` (Android 11+). |
| Android Build Tools | 36.0.0 | Installed via `sdkmanager "build-tools;36.0.0"`. |
| Gradle | 8.13 | Pinned via the wrapper; no global install needed. |
| Kotlin | 2.1.10 | Managed by Gradle plugin. |
| Android Gradle Plugin | 8.13.2 | Managed by Gradle plugin. |

Building the two embedded-node artifacts (required — neither is checked in) additionally requires:

| Component | Version | Notes |
|---|---|---|
| Rust | stable | Compiles `libant_ffi.so` (the embedded ant Swarm node) — see [Building libant_ffi.so](#building-libant_ffiso). |
| cargo-ndk | latest | `cargo install cargo-ndk` — used by ant's `cargo xtask build-android-*`. |
| Go | 1.26+ | `gomobile bind` compiles the embedded Kubo (IPFS) node — see [Building mobile.aar](#building-mobileaar). |
| Android NDK | r27+ | Installed via `sdkmanager "ndk;27.2.12479018"` or similar. Also builds the JNI shim in `swarmnode/src/main/cpp/`. |
| gomobile | latest | `go install golang.org/x/mobile/cmd/gomobile@latest` — handled by `make install` inside the `freedom-node-mobile` clone. |

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

# 2. Build the embedded ant node (Swarm). Produces
#    swarmnode/src/main/jniLibs/{arm64-v8a,x86_64}/libant_ffi.so, which is
#    gitignored and must exist before Gradle can build the app.
#    Needs Rust (stable), cargo-ndk, and the Android NDK.
git clone https://github.com/solardev-xyz/ant.git /tmp/ant
( cd /tmp/ant && cargo xtask build-android-arm64 && cargo xtask build-android-x86_64 )
mkdir -p swarmnode/src/main/jniLibs/{arm64-v8a,x86_64}
cp /tmp/ant/target/aarch64-linux-android/release/libant_ffi.so swarmnode/src/main/jniLibs/arm64-v8a/
cp /tmp/ant/target/x86_64-linux-android/release/libant_ffi.so swarmnode/src/main/jniLibs/x86_64/

# 3. Build the embedded Kubo node (IPFS). ~5 minutes on first run, cache-fast
#    after. Produces the ~143 MiB swarmnode/libs/mobile.aar (gitignored, too
#    big for GitHub). Needs Go 1.26+, Android NDK r27+, and gomobile — see
#    § Building mobile.aar for toolchain setup.
git clone https://github.com/solardev-xyz/freedom-node-mobile.git /tmp/freedom-node-mobile
( cd /tmp/freedom-node-mobile && make install && make build )
cp /tmp/freedom-node-mobile/build/mobile-*.aar swarmnode/libs/mobile.aar

# 4. Build the debug APK.
./gradlew :app:assembleDebug

# 5. Install on a connected device or running emulator.
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
│   ├── libs/mobile.aar           # ~143 MiB embedded Kubo/IPFS node (gitignored — build it, see below)
│   ├── src/main/jniLibs/         # libant_ffi.so per ABI — embedded ant/Swarm node (gitignored)
│   ├── src/main/cpp/             # ant.h (vendored) + JNI shim over the ant C API
│   └── src/main/java/baby/freedom/swarm/
│       ├── SwarmNode.kt          # lifecycle + StateFlow<NodeInfo>
│       ├── AntNative.kt          # raw JNI surface over libant_ffi.so
│       ├── NodeInfo.kt           # status, peers, error
│       └── NodeStatus.kt         # Stopped | Starting | Running | Error
├── TODO.md                       # todo + deferred work
├── build.gradle.kts              # plugin versions
├── settings.gradle.kts           # module wiring + flatDir for mobile.aar
└── .envrc.example                # JAVA_HOME / ANDROID_HOME pointers for macOS
```

Two Gradle modules:
- `:app` — the Android application.
- `:swarmnode` — a self-contained Android library wrapping the embedded ant node (`libant_ffi.so` + JNI shim) and the Kubo node (`mobile.aar`), depended on by `:app`. Designed to be publishable on its own.

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

## Building `libant_ffi.so`

`libant_ffi.so` is the embedded ant node — the Rust Swarm light-node compiled per ABI. It's **not checked in**; every fresh clone builds it once:

```bash
# 1. Clone ant somewhere outside this repo.
git clone https://github.com/solardev-xyz/ant.git /tmp/ant

# 2. Cross-compile. Needs rustup targets + cargo-ndk + ANDROID_NDK_HOME;
#    the xtask prints exactly what's missing if something isn't set up.
cd /tmp/ant
cargo xtask build-android-arm64     # aarch64-linux-android
cargo xtask build-android-x86_64    # x86_64-linux-android (emulator)

# 3. Copy the results into Freedom.
cd <freedom-browser-android>
mkdir -p swarmnode/src/main/jniLibs/{arm64-v8a,x86_64}
cp /tmp/ant/target/aarch64-linux-android/release/libant_ffi.so swarmnode/src/main/jniLibs/arm64-v8a/
cp /tmp/ant/target/x86_64-linux-android/release/libant_ffi.so swarmnode/src/main/jniLibs/x86_64/
```

The Kotlin side talks to it through the hand-written JNI shim in `swarmnode/src/main/cpp/ant_jni.c` (built by the module's CMake step), which wraps the C API in the vendored `ant.h`: `ant_init`, `ant_start_gateway` (the bee-shaped HTTP gateway on `127.0.0.1:1633`), `ant_peer_count`, `ant_shutdown`. When upgrading ant, refresh `swarmnode/src/main/cpp/ant.h` from `crates/ant-ffi/include/ant.h` along with the `.so`s.

## Building `mobile.aar`

`mobile.aar` is the embedded Kubo (IPFS) node — ~150 MiB of statically-linked Go, built from [`solardev-xyz/freedom-node-mobile`](https://github.com/solardev-xyz/freedom-node-mobile) (a combined gomobile binding of bee-lite + kubo; only the `mobile.IpfsNode` surface is used since the Swarm side moved to ant). It's **not checked in**: it exceeds GitHub's 100 MB per-file limit, and rebuilds are non-deterministic (see [Reproducibility caveat](#reproducibility-caveat)). Every fresh clone needs to build it once.

### Steps

```bash
# 1. Clone freedom-node-mobile somewhere outside this repo.
git clone https://github.com/solardev-xyz/freedom-node-mobile.git /tmp/freedom-node-mobile

# 2. Produce the AAR. The 'install' target runs go mod tidy + gomobile init.
cd /tmp/freedom-node-mobile
make install
make build      # gomobile bind -target=android -androidapi=30 -ldflags="-checklinkname=0 ..."

# 3. Copy the result into Freedom (replace <freedom-browser-android> with this repo's path).
cp build/mobile-*.aar <freedom-browser-android>/swarmnode/libs/mobile.aar

# 4. Build Freedom against the new AAR.
cd <freedom-browser-android>
./gradlew :app:assembleDebug
```

Expect `make build` to take several minutes on first run — `gomobile bind` compiles the full bee + kubo + `go-ethereum` + `go-libp2p` tree four times (one per ABI). Subsequent builds are cache-fast. A kubo-only AAR (dropping the now-unused bee half) would roughly halve it — that's a `freedom-node-mobile` change, tracked there.

### When to rebuild

After the initial build, you only need to rebuild when:

- Upgrading Kubo to a newer version.
- Shrinking the binary (adding `-ldflags="-s -w"`, stripping DWARF, Go build tags to drop unused bee packages — the AAR still statically links the whole bee tree even though Freedom now only uses its Kubo half).
- Patching the Go side (e.g. exposing a new method on `IpfsNode`).

### What's inside

The resulting AAR contains:

- `classes.jar` — gomobile Java/JNI bridge + the public `mobile.MobileNode` / `mobile.IpfsNode` surfaces.
- `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/libgojni.so` (~62–68 MiB each) — Kubo plus the (now unused) bee node statically linked (`Solar-Punk-Ltd/bee-lite` + `ethersphere/bee/v2` + `go-ethereum` + `go-libp2p`).

### Reproducibility caveat

`make install` runs `go mod tidy`, which re-resolves indirect dependency versions against whatever's currently available on the Go module proxy. Two builds on different days will be functionally equivalent but produce different SHA-256s as `golang.org/x/{crypto,net,sys,...}` and other transitive deps release patches. Pinning the exact bytes would require a vendored Go module tree, which upstream doesn't provide.

## APK size

The embedded Kubo node is ~62–68 MiB per ABI and the ant node adds its own `.so` on top, so the size story depends on how many ABIs you ship.

`app/build.gradle.kts` already enables per-ABI splits (`arm64-v8a` + `x86_64`) alongside a universal fallback, so every debug/release build produces:

| APK | Size | Use |
|---|---|---|
| `app-arm64-v8a-debug.apk` | ~157 MiB | Physical arm64 devices, Apple Silicon emulators |
| `app-x86_64-debug.apk` | ~189 MiB | x86_64 Android emulators |
| `app-universal-debug.apk` | ~456 MiB | Fallback / `:installDebug` default |

For distribution, Android App Bundles ship just the one ABI the device needs via Play Store's dynamic delivery:

```bash
./gradlew :app:bundleRelease
# app/build/outputs/bundle/release/app-release.aab  (~150 MiB bundle, ~80 MiB per-device install)
```

## Troubleshooting

**Gradle can't find `JAVA_HOME`.** Run `source .envrc` or set `JAVA_HOME` to a JDK 17 install. The wrapper requires it; there's no fallback.

**`./gradlew` downloads Gradle every invocation.** Your `GRADLE_USER_HOME` is set to an ephemeral path or you're offline. Point it at a persistent directory (default: `~/.gradle`).

**Node never reaches `Running` on emulator.** Check `adb logcat -s SwarmNode` for errors. If the node runs but gathers no peers, the network may be blocking outbound TCP dials or UDP DNS to `1.1.1.1` (ant's bootstrap fallback). Try on a different network or a physical device.

**`UnsatisfiedLinkError: dlopen failed: library "libgojni.so" not found` after a minified release build.** Make sure `swarmnode/consumer-rules.pro` is being honoured — it ships `-keep class mobile.** { *; }` and `-keep class go.** { *; }`; R8 removes the gomobile-generated classes without these. The same file keeps `baby.freedom.swarm.AntNative`'s native method names, which the ant JNI shim resolves by exact symbol.

**`UnsatisfiedLinkError` mentioning `libant_ffi.so` or `libant_jni.so`.** The prebuilt ant library for that ABI is missing from `swarmnode/src/main/jniLibs/` — see [Building libant_ffi.so](#building-libant_ffiso).

## Further reading

- [`TODO.md`](./TODO.md) — what's still open and deferred.
- [Swarm docs](https://docs.ethswarm.org/) — the Swarm network itself.
- [`ant`](https://github.com/solardev-xyz/ant) — the embedded Swarm light-node (Rust).
- [`freedom-node-mobile`](https://github.com/solardev-xyz/freedom-node-mobile) — Go sources for the embedded Kubo node (combined bee+kubo AAR).
- [`gomobile` reference](https://pkg.go.dev/golang.org/x/mobile/cmd/gobind) — Go ↔ Java type mapping rules.

## License

TBD. Not yet decided.
