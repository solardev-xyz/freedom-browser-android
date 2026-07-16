plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

android {
    namespace = "baby.freedom.swarm"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
        // Matches the ABIs we build libant_ffi.so for (cargo xtask
        // build-android-arm64 / -x86_64 in solardev-xyz/ant) — see
        // README § Building libant_ffi.so.
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }

    // JNI shim over the prebuilt libant_ffi.so in src/main/jniLibs/.
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        aidl = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/DEPENDENCIES",
            "META-INF/FastDoubleParser-LICENSE",
            "META-INF/FastDoubleParser-NOTICE",
            "META-INF/DISCLAIMER",
        )
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
}
