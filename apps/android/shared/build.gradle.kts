// What the phone's app and the Mac's share: Kotlin Multiplatform, for Android and the desktop's JVM.
// Code moves here from :app a part at a time; what only a phone has stays there.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "cc.hyoban.moments.shared"
        compileSdk = 37
        minSdk = 31
        // The words both apps show are Compose resources, which an Android library packs as assets.
        androidResources { enable = true }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.multiplatform.runtime)
            api(libs.compose.multiplatform.foundation)
            api(libs.compose.multiplatform.ui)
            api(libs.compose.multiplatform.resources)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.okhttp)
            api(libs.sqlite)
            api(libs.haze)
            api(libs.haze.blur)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.capsule)
        }
    }
}

// The words of both apps (composeResources/values, Chinese, and values-en), read as `Res.string.*`
// from `cc.hyoban.moments.res` by :app and :desktop alike.
compose.resources {
    publicResClass = true
    packageOfResClass = "cc.hyoban.moments.res"
}
