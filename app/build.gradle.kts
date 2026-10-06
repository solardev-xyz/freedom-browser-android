import com.android.build.api.artifact.SingleArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("com.mikepenz.aboutlibraries.plugin.android")
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
        versionCode = 43
        versionName = "0.6.26"
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

    // Per-app language (#280): the locale config Android 13+ offers in
    // Settings → Apps → Freedom → Language is generated from the
    // `res/values-<lang>/` folders, so adding a language is a
    // translation-only change. `res/resources.properties` names the
    // language of the unqualified `res/values/` (English).
    androidResources {
        generateLocaleConfig = true
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

    // `./gradlew :app:lintDebug` is the gate for localisation (#280), not
    // a general lint run: it checks only the issues below and fails the
    // build on any of them. Full lint also reports some existing errors
    // and ~150 warnings that have nothing to do with #280 (RestrictedApi,
    // WrongConstant, RequiresFeature, UseKtx, …); a gate that is red for
    // unrelated reasons stops being a gate. This one takes ~15 s of
    // analysis and is green once the strings are migrated. Pass
    // `-Plint.checkAll` for the full report (it fails on those errors).
    //   HardcodedUiText: our own check (:lint-checks), user-visible string
    //     literals in Kotlin; see docs/localisation.md § The lint check.
    //   DevicePluralRules: also ours, a plural form chosen by the phone's
    //     language instead of the text's (pluralStringResource,
    //     getQuantityString; #313 R1-F1).
    //   HardcodedText: the same for XML layouts and menus (a warning by
    //     default, made an error here).
    //   MissingTranslation / ExtraTranslation (an error and a fatal error
    //     by default): a values-<lang> folder that lacks a string, or has
    //     one values/ doesn't.
    lint {
        if (!project.hasProperty("lint.checkAll")) {
            checkOnly += setOf("HardcodedUiText", "DevicePluralRules", "HardcodedText", "MissingTranslation", "ExtraTranslation")
        }
        error += setOf("HardcodedUiText", "DevicePluralRules", "HardcodedText")
        abortOnError = true
        // Test code isn't UI: its literals are fixtures.
        ignoreTestSources = true
        // Not :swarmnode: MissingTranslation doesn't see its strings from
        // here even with checkDependencies (it judges the library by the
        // library's own values-<lang> folders, and it has none), so a
        // translation that leaves them out is caught by
        // TranslationCoverageTest instead (#313 R1-M2).
        checkDependencies = false
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
    // HardcodedUiText (#280), run by `:app:lintDebug`.
    lintChecks(project(":lint-checks"))

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
    // Certificates for an https fixture reached through a CONNECT proxy.
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}

// Settings → About → Open-source licences (#325). Everything the APK ships
// that isn't Freedom's own code, with its licence text, in one asset,
// `licences/licences.json`, built per variant by generateLicences<Variant>
// (see app/licences/README.md):
//   - the Gradle dependencies, from AboutLibraries' scan of the variant's
//     runtime classpath (POM licences, parents included), and the licence
//     texts in app/licences/ (offline: the build never fetches a text);
//   - the Rust crates in libfreedom_mobile_ffi.so, app/licences/native.json,
//     generated by scripts/native-licences.sh at FFI_REF;
//   - Colibri and what it links into libc4.so, the OpenLV bundle and the
//     filter lists, app/licences/bundled.json.
// The task fails the build when any of it is missing or stale: a
// dependency with no licence, a licence with no text, a copyright-bearing
// licence (MIT, BSD, …) without the library's own notice, native.json for
// another FFI_REF, a crate that compiles C code nobody has checked at its
// version, or Colibri at another COLIBRI_REF. checkLicencedFiles<Variant>
// then fails it for a file the APK ships (any merged asset or .so, from
// every source set, generated directory and library) that nobody has
// said is Freedom's own or listed.
aboutLibraries {
    offlineMode = true
    library { requireLicense = true }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val scan = tasks.named("prepareLibraryDefinitions$name")
        val generate = tasks.register<GenerateLicences>("generateLicences$name") {
            dependsOn(scan)
            gradleLibraries.set(layout.buildDirectory.file("generated/aboutLibraries/${variant.name}/res/raw/aboutlibraries.json"))
            licencesDir.set(layout.projectDirectory.dir("licences"))
            releaseWorkflow.set(rootProject.layout.projectDirectory.file(".github/workflows/release.yml"))
            // Which optional libraries (libc4.so) this build actually ships.
            mergedNativeLibs.set(variant.artifacts.get(SingleArtifact.MERGED_NATIVE_LIBS))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(generate, GenerateLicences::outputDir)
        // What the APK actually ships, after merging: not just
        // src/main/assets, but src/<buildType>/assets, generated asset
        // directories, and every library's assets and .so files.
        val shipped = tasks.register<CheckLicencedFiles>("checkLicencedFiles$name") {
            bundled.set(layout.projectDirectory.file("licences/bundled.json"))
            mergedAssets.set(variant.artifacts.get(SingleArtifact.ASSETS))
            mergedNativeLibs.set(variant.artifacts.get(SingleArtifact.MERGED_NATIVE_LIBS))
            outputFile.set(layout.buildDirectory.file("intermediates/licences/${variant.name}/checked"))
        }
        tasks.matching { it.name == "package$name" || it.name == "bundle$name" }.configureEach { dependsOn(shipped) }
    }
}

abstract class GenerateLicences : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val gradleLibraries: RegularFileProperty

    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licencesDir: DirectoryProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val releaseWorkflow: RegularFileProperty

    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedNativeLibs: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @Suppress("UNCHECKED_CAST")
    @TaskAction
    fun generate() {
        val dir = licencesDir.get().asFile
        val problems = mutableListOf<String>()
        fun json(file: File): Map<String, Any?> =
            groovy.json.JsonSlurper().parse(file, "UTF-8") as Map<String, Any?>
        fun readText(path: String): String? =
            File(dir, path).takeIf { it.isFile }?.readText()?.trim('\n', '\r')?.plus("\n")

        // Every text once; a component points at its texts by index.
        val texts = mutableListOf<Map<String, String>>()
        val textIndex = HashMap<String, Int>()
        fun text(title: String, body: String): Int =
            textIndex.getOrPut(body) { texts.add(mapOf("title" to title, "text" to body)); texts.size - 1 }
        val components = mutableListOf<Map<String, Any>>()
        fun component(section: String, name: String, id: String, version: String, licence: String, url: String, notice: String?, textIds: List<Int>) {
            components.add(buildMap {
                put("section", section); put("name", name); put("id", id); put("version", version)
                put("licence", licence); put("url", url); put("texts", textIds.distinct())
                if (!notice.isNullOrBlank()) put("notice", notice)
            })
        }

        // Gradle dependencies.
        val scan = json(gradleLibraries.get().asFile)
        val scanLicences = scan["licenses"] as Map<String, Map<String, Any?>>
        val config = json(File(dir, "gradle.json"))
        val generic = config["generic"] as Map<String, String>
        val notices = config["notices"] as Map<String, String>
        val libraries = scan["libraries"] as List<Map<String, Any?>>
        for (lib in libraries) {
            val id = lib["uniqueId"] as String
            val keys = (lib["licenses"] as List<String>?).orEmpty()
            if (keys.isEmpty()) {
                problems += "$id has no licence in its POM: add it to app/licences/gradle.json notices with its licence file"
                continue
            }
            val ids = mutableListOf<Int>()
            val needsNotice = keys.filter { it !in generic }
            for (key in keys) {
                val path = generic[key] ?: continue
                val body = readText(path)
                if (body == null) problems += "app/licences/$path (for $key) is missing" else {
                    ids += text(scanLicences[key]?.get("name") as? String ?: key, body)
                }
            }
            if (needsNotice.isNotEmpty()) {
                val path = notices[id]
                val body = path?.let(::readText)
                if (body == null) {
                    val what = needsNotice.joinToString { k ->
                        val l = scanLicences[k]
                        "${l?.get("name") ?: k} (${l?.get("url") ?: "no URL"})"
                    }
                    problems += "$id is under $what, which app/licences/gradle.json has no shared text for: " +
                        "put the library's own licence file in app/licences/gradle/ and list it under notices " +
                        "(or, only for a licence whose text is the same for every library, as Apache-2.0's is, add it under generic)"
                } else {
                    ids += text(needsNotice.joinToString(", ") { scanLicences[it]?.get("name") as? String ?: it } + " — " + (lib["name"] ?: id), body)
                }
            }
            val licence = keys.joinToString(", ") { k -> scanLicences[k]?.let { (it["spdxId"] ?: it["name"]) as String } ?: k }
            val url = (lib["website"] as? String) ?: ((lib["scm"] as? Map<String, Any?>)?.get("url") as? String) ?: ""
            component("android", (lib["name"] as? String)?.takeIf { it.isNotBlank() } ?: id, id,
                lib["artifactVersion"] as? String ?: "", licence, url, null, ids)
        }
        val present = libraries.mapTo(HashSet()) { it["uniqueId"] as String }
        val gradleVersions = libraries.associate { "${it["uniqueId"]} ${it["artifactVersion"]}" to it["uniqueId"] as String }
        for (stale in notices.keys - present) {
            problems += "app/licences/gradle.json lists a notice for $stale, which is no longer a dependency: remove it"
        }

        // Release pins.
        val workflow = releaseWorkflow.get().asFile.readText()
        fun pin(name: String) = Regex("(?m)^  $name: *(\\S+)").find(workflow)?.groupValues?.get(1)
        val ffiRef = pin("FFI_REF")
        val colibriRef = pin("COLIBRI_REF")

        // The Rust crates in libfreedom_mobile_ffi.so.
        val native = json(File(dir, "native.json"))
        if (native["ffiRef"] != ffiRef) {
            problems += "app/licences/native.json is for FFI_REF ${native["ffiRef"]}, release.yml pins $ffiRef: " +
                "run scripts/native-licences.sh on a freedom-mobile-ffi checkout at $ffiRef and commit it"
        }
        val nativeTexts = (native["texts"] as List<Map<String, String>>).map { text(it["name"]!!, it["text"]!!) }
        val bundled = json(File(dir, "bundled.json"))
        // A crate that compiles C code (native.json's "native") can carry
        // code under other licences than its own: bundled.json's
        // nativeCode says, for that crate at that version, what it builds,
        // and the components with "inCrate" list it.
        val nativeCode = bundled["nativeCode"] as Map<String, String>
        val nativeCrates = HashSet<String>()
        for (crate in native["crates"] as List<Map<String, Any?>>) {
            val ids = (crate["texts"] as List<Number>).map { nativeTexts[it.toInt()] }
            if (ids.isEmpty()) problems += "native crate ${crate["name"]} has no licence text"
            val key = "${crate["name"]} ${crate["version"]}"
            if (crate["native"] == true) {
                nativeCrates += key
                if (key !in nativeCode) {
                    problems += "native crate $key compiles C code: check what it vendors, list that in " +
                        "app/licences/bundled.json's components (with \"inCrate\": \"$key\"), and say what you found under nativeCode"
                }
            }
            component("native", crate["name"] as String, crate["name"] as String, crate["version"] as String,
                crate["licence"] as String, crate["url"] as String, null, ids)
        }
        for (stale in nativeCode.keys - nativeCrates) {
            problems += "app/licences/bundled.json's nativeCode has $stale, which native.json has no C-compiling crate for: " +
                "recheck it at the new version (and the components that say they're in it)"
        }

        // Colibri, the C code in those crates and in JNA, the OpenLV
        // bundle and the filter lists. A component in an optional library
        // (Colibri's, in libc4.so, which a local build may leave out) is
        // listed only when this build ships that library, but checked
        // either way.
        val so = mergedNativeLibs.get().asFile.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.mapTo(sortedSetOf()) { it.name }
        val optional = bundled["optionalNativeLibraries"] as Map<String, String>
        for (c in bundled["components"] as List<Map<String, Any?>>) {
            val name = c["name"] as String
            val shipped = optional.none { (lib, key) -> c[key] != null && lib !in so }
            val ids = (c["texts"] as List<String>).mapNotNull { path ->
                // A standard text is titled by its file (texts/GPL-3.0.txt:
                // "GPL-3.0"), a project's own licence file by the licence.
                val title = if (path.startsWith("texts/")) path.removePrefix("texts/").removeSuffix(".txt") else c["licence"] as String
                readText(path)?.let { text(title, it) }
                    ?: null.also { problems += "app/licences/$path (for $name) is missing" }
            }
            if (c["colibri"] == "self" && "v${c["version"]}" != colibriRef) {
                problems += "app/licences/bundled.json has Colibri ${c["version"]}, release.yml pins COLIBRI_REF $colibriRef: " +
                    "update it and what it links (scripts/check-colibri-licences.sh)"
            }
            (c["inCrate"] as String?)?.let {
                if (it !in nativeCrates) problems += "app/licences/bundled.json: $name is in $it, which native.json has no C-compiling crate for: recheck it"
            }
            (c["inGradle"] as String?)?.let {
                if (it !in gradleVersions) problems += "app/licences/bundled.json: $name is in $it, which isn't a dependency at that version: recheck it"
            }
            if (shipped) component(c["section"] as String, name, name, c["version"] as String, c["licence"] as String,
                c["url"] as String, c["notice"] as String?, ids)
        }

        if (problems.isNotEmpty()) {
            throw GradleException("Open-source licences (#325, app/licences/README.md):\n  - " + problems.joinToString("\n  - "))
        }
        val out = outputDir.get().asFile.resolve("licences")
        out.deleteRecursively()
        out.mkdirs()
        out.resolve("licences.json").writeText(
            groovy.json.JsonOutput.toJson(mapOf("components" to components, "texts" to texts)),
        )
    }
}

/**
 * Fails the build for a file the APK ships that nobody has accounted for
 * (#325): a merged asset that's neither in bundled.json's `own` nor in a
 * component's `files`, or a merged `.so` that isn't in its
 * `nativeLibraries` (saying whose code it is). Reads the merged outputs, so
 * an asset from src/release/assets, a generated directory or a library is
 * caught as well as one in src/main/assets.
 */
abstract class CheckLicencedFiles : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundled: RegularFileProperty

    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedAssets: DirectoryProperty

    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedNativeLibs: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @Suppress("UNCHECKED_CAST")
    @TaskAction
    fun check() {
        val config = groovy.json.JsonSlurper().parse(bundled.get().asFile, "UTF-8") as Map<String, Any?>
        val problems = mutableListOf<String>()
        val claimed = HashSet(config["own"] as List<String>)
        val listed = HashSet<String>()
        for (c in config["components"] as List<Map<String, Any?>>) listed += (c["files"] as List<String>?).orEmpty()
        claimed += listed
        // generateLicences' own output.
        claimed += "licences/licences.json"

        val assets = mergedAssets.get().asFile
        val shipped = assets.walkTopDown().filter { it.isFile }.map { it.relativeTo(assets).invariantSeparatorsPath }.toSortedSet()
        for (file in shipped - claimed) {
            problems += "assets/$file is neither Freedom's own nor listed with its licence: add it to app/licences/bundled.json"
        }
        for (file in (claimed - shipped).sorted()) {
            problems += "app/licences/bundled.json lists assets/$file, which the APK doesn't ship"
        }

        val libs = config["nativeLibraries"] as Map<String, String>
        // A library a local build may leave out (libc4.so: README, step 3).
        val optional = (config["optionalNativeLibraries"] as Map<String, String>).keys
        for (lib in optional - libs.keys) problems += "app/licences/bundled.json's optionalNativeLibraries has $lib, which isn't in nativeLibraries"
        val so = mergedNativeLibs.get().asFile.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.mapTo(sortedSetOf()) { it.name }
        for (lib in so - libs.keys) {
            problems += "$lib is a native library the APK ships that app/licences/bundled.json's nativeLibraries doesn't know: " +
                "find what's linked into it, list any third-party code under components, and add it to nativeLibraries"
        }
        for (lib in (libs.keys - so - optional).sorted()) {
            problems += "app/licences/bundled.json's nativeLibraries has $lib, which the APK no longer ships: remove it"
        }

        if (problems.isNotEmpty()) {
            throw GradleException("Open-source licences (#325, app/licences/README.md):\n  - " + problems.joinToString("\n  - "))
        }
        outputFile.get().asFile.writeText("ok\n")
    }
}
