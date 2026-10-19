// The Android app that records Moments, places and trips and exports them for the site.
// Kotlin comes with the Android Gradle plugin (built-in Kotlin); only the compiler plugins
// for Compose and kotlinx.serialization are applied on top.
buildscript {
    dependencies {
        // Built-in Kotlin takes this Kotlin Gradle plugin instead of the one it bundles.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
