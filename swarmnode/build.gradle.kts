import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("kotlin-parcelize")
}

android {
    namespace = "baby.freedom.swarm"
    compileSdk = 37
    // AGP 9 defaults to NDK r28; pin the r27 the JNI shim has always
    // been built with so it keeps matching the toolchain that produces
    // libfreedom_mobile_ffi.so (cargo-ndk against the runner's
    // ANDROID_NDK_ROOT — see .github/workflows/release.yml).
    ndkVersion = "27.0.12077973"

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

// AGP 9 applies the Kotlin plugin itself, so the compiler options live
// in the top-level `kotlin` block rather than `android.kotlinOptions`.
kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
}
