import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

val localProperties = Properties().apply {
    rootDir.resolve("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
val preparedMaplibreRoot = rootDir.resolve(".maplibre-maven")
val maplibreRepository =
    providers.environmentVariable("MAPLIBRE_COMPOSE_REPOSITORY").orNull
        ?: localProperties.getProperty("maplibre.repository")
        ?: preparedMaplibreRoot.resolve("compose").takeIf { it.isDirectory }?.path
val maplibreNativeFfiRepository =
    providers.environmentVariable("MAPLIBRE_NATIVE_FFI_REPOSITORY").orNull
        ?: localProperties.getProperty("maplibre.nativeFfiRepository")
        ?: preparedMaplibreRoot.resolve("native-ffi").takeIf { it.isDirectory }?.path

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maplibreRepository?.let {
            maven(url = uri(it)) {
                content { includeGroupAndSubgroups("org.maplibre.compose") }
            }
        }
        maplibreNativeFfiRepository?.let {
            maven(url = uri(it)) {
                content { includeGroupAndSubgroups("org.maplibre.nativeffi") }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Moments"

include(":app")
// What the phone and the Mac share, and the Mac app itself.
include(":shared")
include(":desktop")
