plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "baby.freedom.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "baby.freedom.mobile"
        minSdk = 30
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.0"
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
    kotlinOptions { jvmTarget = "17" }

    // Per-ABI split so we can ship a slim arm64-v8a-only APK (~80 MB)
    // instead of the universal 310 MB build. Release builds and the
    // local `:installDebug` flow still work via `universalApk = true`.
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
    }
}

dependencies {
    implementation(project(":swarmnode"))

    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    implementation("androidx.datastore:datastore-preferences:1.2.1")

    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
}
