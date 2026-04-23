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
        versionCode = 3
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign release builds with the debug keystore so they install
            // without Play Store signing. Replace with a proper release
            // signingConfig before publishing.
            signingConfig = signingConfigs.getByName("debug")
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

    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
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
}
