import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Custom lint checks for :app (#280): HardcodedUiText keeps user-visible
// string literals out of the code, so every text goes through string
// resources and a translation stays a resources-only change. See
// docs/localisation.md § The lint check. :app picks the jar up with
// `lintChecks(project(":lint-checks"))`.
plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

// Lint X.Y.Z ships with AGP (X-23).Y.Z: keep this in step with the AGP
// version in the root build.gradle.kts (9.1.1 → 32.1.1).
val lintVersion = "32.1.1"

// The checks run inside lint, on lint's own Kotlin runtime (2.2 for lint
// 32.1), not the 2.4 compiler that builds them: compile against that
// API and stdlib so nothing newer slips into the jar.
kotlin {
    coreLibrariesVersion = "2.2.10"
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly("com.android.tools.lint:lint-api:$lintVersion")
    compileOnly("com.android.tools.lint:lint-checks:$lintVersion")

    testImplementation("com.android.tools.lint:lint-api:$lintVersion")
    testImplementation("com.android.tools.lint:lint-checks:$lintVersion")
    testImplementation("com.android.tools.lint:lint:$lintVersion")
    testImplementation("com.android.tools.lint:lint-tests:$lintVersion")
    testImplementation("junit:junit:4.13.2")
}
