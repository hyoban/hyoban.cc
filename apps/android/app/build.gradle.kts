import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Whose app this is, from the repository's site.config.json, which a fork changes to make the
// app its own: its application id, and the GitHub repository whose releases it updates from.
// The code keeps its package, cc.hyoban.moments, whatever the id.
@Suppress("UNCHECKED_CAST")
val siteConfig = groovy.json.JsonSlurper().parse(rootProject.file("../../site.config.json")) as Map<String, Any?>
val appId = (siteConfig["android"] as Map<String, Any?>)["applicationId"] as String
val githubRepo = siteConfig["github"] as String

val localProperties: Properties? = rootProject.file("local.properties").takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

// The release key's settings: `release.storeFile` and the rest in local.properties, else
// `ANDROID_RELEASE_STORE_FILE` and the rest in the environment, as the release workflow sets them.
fun releaseSetting(name: String): String? =
    localProperties?.getProperty("release.$name")
        ?: providers.environmentVariable(
            "ANDROID_RELEASE_" + name.replace(Regex("([A-Z])"), "_\$1").uppercase(),
        ).orNull?.takeIf { it.isNotBlank() }

// The version is the release's tag, `android-v1.2.3`: the release workflow passes it as
// `-PappVersion`, and other builds take the newest such tag, so a build from here installs over
// the release on the phone. Each part stays under 100 to keep the code growing with the name.
val appVersion: String = providers.gradleProperty("appVersion").orNull
    ?: providers.exec {
        commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "android-v*")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().removePrefix("android-v").ifEmpty { "0.1.0" }
val appVersionCode: Int = appVersion.split(".").map(String::toInt)
    .also { parts -> require(parts.size == 3 && parts.all { it in 0..99 }) { "appVersion must be x.y.z, each under 100: $appVersion" } }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

android {
    namespace = "cc.hyoban.moments"
    compileSdk = 37

    defaultConfig {
        applicationId = appId
        minSdk = 31
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The map's styles in light and dark, what the app draws until its settings say otherwise.
        // There is no API or media origin of the build's own: the app works on the phone alone
        // until one is set under 同步与来源.
        // Where places are searched for by name and found around a pin: Photon, over the
        // same OpenStreetMap data the map draws.
        buildConfigField("String", "PLACE_SEARCH_URL", "\"https://photon.komoot.io\"")
        // The repository whose GitHub releases the app updates from (site.config.json).
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
        // The accessibility service watches the app itself, by its id (res/xml/share_accessibility.xml).
        resValue("string", "application_id", appId)
        // Phones are all arm64; the map's native library for the other ABIs would triple the
        // APK, and a debug build's install on the phone with it.
        ndk { abiFilters += "arm64-v8a" }
    }

    // With the release key, debug and release builds are both signed with it, so a debug build
    // installs over the release from GitHub and keeps its data; without it, both take the debug key.
    val releaseStore = releaseSetting("storeFile")?.let(rootProject::file)

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = releaseSetting("storePassword")
                keyAlias = releaseSetting("keyAlias")
                keyPassword = releaseSetting("keyPassword")
            }
        }
    }
    val signing = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")

    buildTypes {
        debug {
            signingConfig = signing
        }
        release {
            signingConfig = signing
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // The app speaks Chinese and English, so the libraries' strings in other languages are left
    // out. Chinese takes its regions: the libraries keep theirs as zh-rCN and the like, not zh.
    androidResources { localeFilters += listOf("en", "zh-rCN", "zh-rHK", "zh-rTW") }

    // The map's native library compressed in the APK, a third of its size, and the code to half
    // of its, both unpacked on install: a smaller APK to pass around for a little more on the phone.
    packaging {
        jniLibs.useLegacyPackaging = true
        dex.useLegacyPackaging = true
    }

    // Lint's release check adds about ten seconds to every release build; CI doesn't run it either.
    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The cities the map's fog of war lights, the site's own file (scripts/generate-city-boundaries.mjs).
    sourceSets["main"].assets.srcDir(rootProject.file("../web/src/data/boundaries"))

    buildFeatures {
        aidl = true
        buildConfig = true
        resValues = true
        compose = true
    }
}

// Material 3 Expressive throughout, its components still marked experimental.
kotlin {
    compilerOptions {
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.play.services.location)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.video)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre)
    implementation(libs.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.session)
    implementation(libs.telephoto.zoomable)
    implementation(libs.capsule)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.glance.appwidget)
    implementation(libs.work.runtime)
    implementation(libs.exifinterface)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)
    implementation(libs.zip4j)
    implementation(libs.sqlite.framework)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.sqlite.jdbc)
}
