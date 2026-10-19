import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties: Properties? = rootProject.file("local.properties").takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

android {
    namespace = "cc.hyoban.moments"
    compileSdk = 37

    defaultConfig {
        applicationId = "cc.hyoban.moments"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // Where the records come from, and the origin their photos and videos are served from.
        buildConfigField("String", "API_URL", "\"https://hyoban-cc-api.hyoban.workers.dev\"")
        buildConfigField("String", "ASSET_ORIGIN", "\"https://image.hyoban.cc\"")
    }

    // A release keystore from local.properties (`release.storeFile`, `release.storePassword`,
    // `release.keyAlias`, `release.keyPassword`) when there is one; otherwise release builds
    // are signed with the debug key, so they install over a debug build and keep its data.
    val releaseStore = localProperties?.getProperty("release.storeFile")?.let(rootProject::file)

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = localProperties?.getProperty("release.storePassword")
                keyAlias = localProperties?.getProperty("release.keyAlias")
                keyPassword = localProperties?.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            // Phones are all arm64; the map's native library for the other ABIs would triple the APK.
            ndk { abiFilters += "arm64-v8a" }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // The map's native library compressed in the APK, a third of its size, and unpacked on install.
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.material.kolor)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre)
    implementation(libs.media3.exoplayer)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
