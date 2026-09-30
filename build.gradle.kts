plugins {
    id("com.android.application") version "9.1.1" apply false
    id("com.android.library") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    // Only for :lint-checks, a plain JVM module (AGP 9 brings Kotlin to the
    // Android modules itself).
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("androidx.room") version "2.8.4" apply false
}
