// Here on the Mac: a Compose Desktop window over :shared.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
}

// The cities the map's fog of war lights, the site's own file, as the phone takes it into its assets.
sourceSets["main"].resources.srcDir(rootProject.file("../web/src/data/boundaries"))
// Where China's shifted coordinates apply, the file the phone carries in its own resources.
sourceSets["main"].resources.srcDir(rootProject.file("app/src/main/resources"))

dependencies {
    implementation(project(":shared"))
    implementation(libs.compose.multiplatform.desktop)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.sqlite.bundled)
}

compose.desktop {
    application {
        mainClass = "cc.hyoban.moments.desktop.MainKt"
    }
}
