# Freedom — Swarm Browser for Android

A native Android browser that loads both regular `https://` sites and decentralised content addressed via `bzz://` hashes or `ens://` names. An embedded [bee-lite](https://github.com/Solar-Punk-Ltd/bee-lite) node runs inside the app and serves Swarm content through a local HTTP gateway; the WebView sees an ordinary `http://127.0.0.1:1633/bzz/…` URL.

- **Package:** `baby.freedom.mobile` · **Version:** 0.2.0
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

Building `mobile.aar` (required — it's not checked in; see [below](#building-mobileaar)) additionally requires:

| Component | Version | Notes |
|---|---|---|
| Go | 1.26+ | `gomobile bind` compiles the embedded Bee node. |
| Android NDK | r27+ | Installed via `sdkmanager "ndk;27.0.12077973"` or similar. |
| gomobile | latest | `go install golang.org/x/mobile/cmd/gomobile@latest` — handled by `make install` inside the `bee-lite-java` clone. |

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

# 2. Build the embedded Bee node. ~5 minutes on first run, cache-fast after.
#    Produces the ~143 MiB swarmnode/libs/mobile.aar, which is gitignored
#    (too big for GitHub) and must exist before Gradle can build the app.
#    Needs Go 1.26+, Android NDK r27+, and gomobile — see § Building mobile.aar
#    for toolchain setup. Only needed on a fresh clone or when upgrading Bee.
git clone https://github.com/Solar-Punk-Ltd/bee-lite-java.git /tmp/bee-lite-java
( cd /tmp/bee-lite-java && make install && make build )
cp /tmp/bee-lite-java/build/mobile.aar swarmnode/libs/mobile.aar

# 3. Build the debug APK.
./gradlew :app:assembleDebug

# 4. Install on a connected device or running emulator.
./gradlew :app:installDebug
# or, for a slim per-ABI APK on a physical arm64 device:
#   adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

The build produces three debug APKs — `app-arm64-v8a-debug.apk` (~166 MiB), `app-x86_64-debug.apk` (~210 MiB), and `app-universal-debug.apk` (~556 MiB, all ABIs). See [APK size](#apk-size) for what to ship.

## Running on an emulator

The embedded node's DNS-based bootstrap fails on Android emulator DNS, so Freedom hard-codes a set of pre-resolved leaf multiaddrs (see [`PLAN.md § Emulator DHT bootstrap`](./PLAN.md)). No extra configuration needed — just:

```bash
# Create a Pixel AVD with Android 16 (API 36), arm64 on Apple Silicon.
avdmanager create avd -n freedom -k "system-images;android-36;google_apis;arm64-v8a"
emulator -avd freedom &

./gradlew :app:installDebug
adb shell monkey -p baby.freedom.mobile 1
```

Expected behaviour on cold start:

- Address bar shows `ens://freedombrowser.eth/` with an amber status dot (node starting).
- Within ~25 s the dot turns green; peer count ramps to ~80+ within 60 s.
- Tap the status dot to see wallet address, peer count, and gateway URL.

Verify the gateway from the host:

```bash
adb forward tcp:1633 tcp:1633
curl http://127.0.0.1:1633/health      # {"status":"ok","version":"..."}
curl http://127.0.0.1:1633/status      # beeMode=ultra-light, ...
```

## Project layout

```
freedom-browser-android/
├── app/                          # Android application (Compose + Material 3)
│   └── src/main/java/baby/freedom/mobile/
│       ├── MainActivity.kt       # hosts the Compose tree, binds NodeService
│       ├── browser/              # tabs, address bar, WebView, resolver
│       ├── ens/                  # Keccak256, ENS contenthash, Universal Resolver
│       └── node/NodeService.kt   # foreground service owning the Swarm node
├── swarmnode/                    # Kotlin wrapper around bee-lite (published-ready)
│   ├── libs/mobile.aar           # ~143 MiB embedded Bee node (gitignored — build it, see below)
│   └── src/main/java/baby/freedom/swarm/
│       ├── SwarmNode.kt          # lifecycle + StateFlow<NodeInfo>
│       ├── NodeInfo.kt           # wallet, peers, error
│       └── NodeStatus.kt         # Stopped | Starting | Running | Error
├── PLAN.md                       # architecture, decisions, known issues
├── build.gradle.kts              # plugin versions
├── settings.gradle.kts           # module wiring + flatDir for mobile.aar
└── .envrc.example                # JAVA_HOME / ANDROID_HOME pointers for macOS
```

Two Gradle modules:
- `:app` — the Android application.
- `:swarmnode` — a self-contained Android library wrapping `mobile.aar`, depended on by `:app`. Designed to be publishable on its own (see [`PLAN.md § is it worth publishing?`](./PLAN.md)).

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
# package: name='baby.freedom.mobile' versionCode='3' versionName='0.2.0'
```

## Building `mobile.aar`

`mobile.aar` is the embedded Bee node — ~143 MiB of statically-linked Go. It's **not checked in**: it exceeds GitHub's 100 MB per-file limit, and rebuilds are non-deterministic (see [Reproducibility caveat](#reproducibility-caveat)). Every fresh clone needs to build it once.

### Provenance

- Upstream doesn't ship a prebuilt AAR. `Solar-Punk-Ltd/swarm-mobile-android` has `*.aar` in `.gitignore` and its README points consumers at `Solar-Punk-Ltd/bee-lite-java` to produce one.
- The AAR Freedom expects is built from pristine upstream Go sources (`Solar-Punk-Ltd/bee-lite-java`) via `make build`. The only "patch" is whatever `go mod tidy` pinned for transitive deps at build time.

### Steps

```bash
# 1. Clone bee-lite-java somewhere outside this repo.
git clone https://github.com/Solar-Punk-Ltd/bee-lite-java.git /tmp/bee-lite-java

# 2. Produce the AAR. The 'install' target runs go mod tidy + gomobile init.
cd /tmp/bee-lite-java
make install
make build      # gomobile bind -target=android -androidapi=30 -ldflags="-checklinkname=0"

# 3. Copy the result into Freedom (replace <freedom-browser-android> with this repo's path).
cp build/mobile.aar <freedom-browser-android>/swarmnode/libs/mobile.aar

# 4. Build Freedom against the new AAR.
cd <freedom-browser-android>
./gradlew :app:assembleDebug
```

Expect `make build` to take several minutes on first run — `gomobile bind` compiles the full Bee + `go-ethereum` + `go-libp2p` tree four times (one per ABI). Subsequent builds are cache-fast.

### When to rebuild

After the initial build, you only need to rebuild when:

- Upgrading Bee to a newer `ethersphere/bee/v2` version.
- Shrinking the binary (adding `-ldflags="-s -w"`, stripping DWARF, Go build tags to drop Bee packages).
- Patching the Go side (e.g. exposing a new method on `MobileNode`).

### What's inside

The resulting AAR contains:

- `classes.jar` (~24 KiB) — gomobile Java/JNI bridge + the public `mobile.MobileNode` / `mobile.MobileNodeOptions` surface.
- `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/libgojni.so` (~62–68 MiB each) — the full Bee node statically linked (`Solar-Punk-Ltd/bee-lite` + `ethersphere/bee/v2` + `go-ethereum` + `go-libp2p`).

### Reproducibility caveat

`make install` runs `go mod tidy`, which re-resolves indirect dependency versions against whatever's currently available on the Go module proxy. Two builds on different days will be functionally equivalent but produce different SHA-256s as `golang.org/x/{crypto,net,sys,...}` and other transitive deps release patches. Pinning the exact bytes would require a vendored Go module tree, which upstream doesn't provide.

## APK size

The embedded Bee node is ~62–68 MiB per ABI, so the size story depends on how many ABIs you ship.

`app/build.gradle.kts` already enables per-ABI splits (`arm64-v8a` + `x86_64`) alongside a universal fallback, so every debug/release build produces:

| APK | Size | Use |
|---|---|---|
| `app-arm64-v8a-debug.apk` | ~166 MiB | Physical arm64 devices, Apple Silicon emulators |
| `app-x86_64-debug.apk` | ~210 MiB | x86_64 Android emulators |
| `app-universal-debug.apk` | ~556 MiB | Fallback / `:installDebug` default |

For distribution, Android App Bundles ship just the one ABI the device needs via Play Store's dynamic delivery:

```bash
./gradlew :app:bundleRelease
# app/build/outputs/bundle/release/app-release.aab  (~150 MiB bundle, ~80 MiB per-device install)
```

## Troubleshooting

**Gradle can't find `JAVA_HOME`.** Run `source .envrc` or set `JAVA_HOME` to a JDK 17 install. The wrapper requires it; there's no fallback.

**`./gradlew` downloads Gradle every invocation.** Your `GRADLE_USER_HOME` is set to an ephemeral path or you're offline. Point it at a persistent directory (default: `~/.gradle`).

**Node never reaches `Running` on emulator.** Check `adb logcat -s SwarmNode` for errors. The most common failure mode is DNS — Freedom already ships the leaf-multiaddr workaround, but if you're on a restrictive network the node may still fail to dial any peer. Try on a different network or a physical device.

**`UnsatisfiedLinkError: dlopen failed: library "libgojni.so" not found` after a minified release build.** Make sure `swarmnode/consumer-rules.pro` is being honoured — it ships `-keep class mobile.** { *; }` and `-keep class go.** { *; }`; R8 removes the gomobile-generated classes without these.

## Further reading

- [`PLAN.md`](./PLAN.md) — architecture, decisions, known issues and their workarounds.
- [Swarm docs](https://docs.ethswarm.org/) — the Swarm network itself.
- [`bee-lite-java`](https://github.com/Solar-Punk-Ltd/bee-lite-java) — Go sources for the embedded node.
- [`gomobile` reference](https://pkg.go.dev/golang.org/x/mobile/cmd/gobind) — Go ↔ Java type mapping rules.

## License

TBD. Not yet decided.
