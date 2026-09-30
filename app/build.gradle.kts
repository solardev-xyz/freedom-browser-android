import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    namespace = "baby.freedom.mobile"
    compileSdk = 37
    // The same NDK as :swarmnode. AGP strips every packaged .so
    // (libfreedom_jni.so, JNA's libjnidispatch.so, …) with this module's
    // NDK, and AGP 9's default (r28) isn't what the README installs, so
    // without the pin a local release build silently ships them
    // unstripped ("Unable to strip the following libraries") (#230).
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "baby.freedom.mobile"
        minSdk = 30
        targetSdk = 37
        versionCode = 29
        versionName = "0.6.12"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Filter-list update trust anchor overrides (#127), for a build
        // pointed at a test publisher's feed — desktop's and iOS's
        // FREEDOM_ADBLOCK_FEED_OWNER / FREEDOM_ADBLOCK_SIG_ADDRESS. Empty
        // (the default, and every release) means the production publisher
        // pinned in AdblockFeed. Compile-time only: nothing at runtime can
        // change who the app trusts.
        fun addressProperty(name: String): String {
            val value = (project.findProperty(name) as String?).orEmpty()
            require(value.isEmpty() || Regex("^0x[0-9a-fA-F]{40}$").matches(value)) { "$name: not an address" }
            return "\"$value\""
        }
        buildConfigField("String", "ADBLOCK_FEED_OWNER", addressProperty("freedom.adblockFeedOwner"))
        buildConfigField("String", "ADBLOCK_SIGNER", addressProperty("freedom.adblockSigner"))
    }

    // Release signing comes from the environment so the same config
    // serves both CI (.github/workflows/release.yml, secrets-fed) and a
    // local machine with the keystore checked out. Without the env vars
    // release builds fall back to the debug key — installable for local
    // testing, never for publishing.
    signingConfigs {
        create("release") {
            val ksFile = System.getenv("FREEDOM_KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("FREEDOM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FREEDOM_KEY_ALIAS") ?: "freedom"
                keyPassword = System.getenv("FREEDOM_KEY_PASSWORD")
                    ?: System.getenv("FREEDOM_KEYSTORE_PASSWORD")
            }
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (System.getenv("FREEDOM_KEYSTORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Per-ABI split so we can ship a slim arm64-v8a-only APK (~30 MB)
    // instead of the universal one (~50 MB, both ABIs' native libs).
    // Release builds and the local `:installDebug` flow still work via
    // `universalApk = true`.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    testOptions {
        // Let unit tests that touch `android.util.Log` (e.g. the
        // GatewayProbe retry logger) run without Robolectric; default
        // values are fine since the tests don't actually inspect log
        // output.
        unitTests.isReturnDefaultValues = true
        // A failure on the CI runner prints its message and full stack
        // trace in the log, not just "AssertionError at SendTest.kt:N"
        // (#310); the HTML/XML reports are uploaded too (release.yml).
        unitTests.all {
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showExceptions = true
                showStackTraces = true
                showCauses = true
            }
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/DEPENDENCIES",
            "META-INF/FastDoubleParser-LICENSE",
            "META-INF/FastDoubleParser-NOTICE",
            "META-INF/DISCLAIMER",
            "META-INF/{AL2.0,LGPL2.1}",
        )
        // Store native libraries deflated (#230). The app is sideloaded
        // from GitHub Releases, which serve the APK as-is and have no
        // delta updates, so every install and update downloads the whole
        // file: compressed, the arm64-v8a APK drops by ~22 MB (~43%),
        // almost all of it libfreedom_mobile_ffi.so. The cost is on disk:
        // the installer extracts the .so files next to the APK instead
        // of mapping them straight out of it, ~19 MB more installed.
        // Dex stays stored (uncompressed): deflating it saves only ~3 MB
        // for ~6 MB more on disk and an extraction on first start.
        jniLibs.useLegacyPackaging = true
        // Only arm64-v8a and x86_64 carry libfreedom_mobile_ffi.so (see
        // :swarmnode's abiFilters), so the other ABIs that AARs bring
        // along (JNA's armeabi/armeabi-v7a/x86/mips/mips64
        // libjnidispatch.so, AndroidX's and CameraX's 32-bit helpers) can
        // never run the app. They only bloated the universal APK and let
        // it install on a 32-bit-only phone that then crashed on first
        // native call; without them that phone refuses the install (#230).
        jniLibs.excludes += setOf(
            "lib/armeabi/**",
            "lib/armeabi-v7a/**",
            "lib/x86/**",
            "lib/mips/**",
            "lib/mips64/**",
        )
    }
}

// Room writes each database version's schema here (checked in), and
// hands the directory to the instrumented tests as assets, which build a
// database at an old version from it to test a migration (#264). Not
// room-testing's MigrationTestHelper: it needs a newer
// kotlinx-serialization than the app ships, and fails at runtime with it.
room {
    schemaDirectory("$projectDir/schemas")
}

// AGP 9 applies the Kotlin plugin itself, so the compiler options live
// in the top-level `kotlin` block rather than `android.kotlinOptions`.
kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    implementation(project(":swarmnode"))

    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Material 3 Expressive (MaterialExpressiveTheme, wavy progress,
    // LoadingIndicator, icon-button shapes) is internal in the BOM's
    // 1.4.0 and only public from the 1.5.0 alphas, so this one artifact
    // overrides the BOM. It pulls compose ui / foundation / animation up
    // to 1.13.0-alpha01 transitively; everything else stays on the BOM.
    implementation("androidx.compose.material3:material3:1.5.0-alpha28")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    // Service-worker request interception (feature-gated at runtime via
    // WebViewFeature) so SW fetches on virtual dweb origins route
    // through the same interceptor as everything else.
    implementation("androidx.webkit:webkit:1.15.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // ENSIP-15 name normalization: the Java port of desktop's
    // @adraffy/ens-normalize, by the spec's author. Pure JVM, no
    // dependencies, ~60 KB with its spec tables.
    implementation("io.github.adraffy:ens-normalize:0.3.1")

    // The wallet's receive QR and QR scanner (#106): ZXing's core codec
    // (pure Java, Apache-2.0) encodes and decodes on-device; CameraX
    // feeds it frames. No Play Services / ML Kit, nothing leaves the phone.
    implementation("com.google.zxing:core:3.5.3")
    val cameraXVersion = "1.5.0"
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    // Opt-in, end-to-end encrypted recovery-phrase backup (#231). Asked
    // only from the wallet page (is backup possible, is there one to
    // restore) and, while backup is on, on returning to the app; without
    // Play services every call fails and the page says backup isn't available.
    implementation("com.google.android.gms:play-services-auth-blockstore:16.4.0")

    // window.swarm messaging (#121): the WebSocket to the node's
    // `/gsoc/subscribe` and `/pss/subscribe` (loopback only; answers the
    // node's pings). Its mockwebserver stands in for the node in tests.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    // Virtual time (runTest / StandardTestDispatcher) for tests of timeouts
    // and deadlines: exact on any runner, however slow.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    // Real org.json for unit tests: the android.jar stub returns null from
    // every method, which breaks anything that builds JSON-RPC bodies.
    testImplementation("org.json:json:20240303")
    // A JS engine for unit tests of injected page scripts (the #66
    // bottom-nav detector), run against a small fake DOM.
    testImplementation("org.mozilla:rhino:1.7.15")
    // UTS-46 for WhatwgHost's JVM tests: android.icu (what the app uses)
    // is a stub off-device, and icu4j is the same library unrepackaged.
    testImplementation("com.ibm.icu:icu4j:77.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    // Compose UI tests (the address-bar suggestion tap, #170).
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // Fixture gateway: the verification suite serves the test dapp from
    // a loopback server standing in for the local gateway, so the
    // tests are hermetic (no p2p, no external network).
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
