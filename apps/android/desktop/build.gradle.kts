import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Here on the Mac: a Compose Desktop window over :shared.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(25)
}

// The cities the map's fog of war lights, the site's own file, as the phone takes it into its assets.
sourceSets["main"].resources.srcDir(rootProject.file("../web/src/data/boundaries"))
// Where China's shifted coordinates apply, the file the phone carries in its own resources.
sourceSets["main"].resources.srcDir(rootProject.file("app/src/main/resources"))

// MapLibre Compose brings every desktop backend and Compose's Material; the Mac draws its maps with
// Metal and the app has no Material, so these stay out of what is packaged.
configurations.named("runtimeClasspath") {
    listOf("lwjgl-egl", "lwjgl-opengl", "lwjgl-opengles", "lwjgl-vulkan").forEach { exclude(group = "org.lwjgl", module = it) }
    exclude(group = "org.jetbrains.compose.material")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-tooling-preview")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-tooling-preview-desktop")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.compose.multiplatform.desktop)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.sqlite.bundled)
    // MapLibre Native drawn with Metal, as the Mac's Compose window draws.
    runtimeOnly(libs.maplibre.compose.runtime.metal.macos.arm64)
}

// Whose app this is, from the repository's site.config.json, as the phone's build reads it.
@Suppress("UNCHECKED_CAST")
val siteConfig = groovy.json.JsonSlurper().parse(rootProject.file("../../site.config.json")) as Map<String, Any?>
val applicationId = (siteConfig["android"] as Map<String, Any?>)["applicationId"] as String

// The version is the release's tag, `mac-v1.2.3`: the release workflow passes it as
// `-PappVersion`, and other builds take the newest such tag. A Mac bundle's version can't start at 0.
val appVersion: String = providers.gradleProperty("appVersion").orNull
    ?: providers.exec {
        commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "mac-v*")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().removePrefix("mac-v").ifEmpty { "1.0.0" }

// The JDK's classes, for ProGuard to resolve the app's against. Compose gives it the JDK's jmods,
// which a JDK since 24 needn't carry (Temurin's doesn't), so they are read here from the running
// JDK's own image (`jrt:/`) into a jar, and named to ProGuard by a configuration file beside it.
val jdkForProguard = layout.buildDirectory.file("proguard/jdk.jar")
val jdkForProguardConfig = layout.buildDirectory.file("proguard/jdk.pro")
val exportJdkForProguard = tasks.register("exportJdkForProguard") {
    val jar = jdkForProguard.get().asFile
    val config = jdkForProguardConfig.get().asFile
    outputs.files(jar, config)
    doLast {
        jar.parentFile.mkdirs()
        val jrt = FileSystems.getFileSystem(URI.create("jrt:/"))
        ZipOutputStream(jar.outputStream().buffered()).use { out ->
            Files.list(jrt.getPath("/modules")).use { modules ->
                modules.filter { val name = it.fileName.toString(); name.startsWith("java.") || name == "jdk.unsupported" }.forEach { module ->
                    Files.walk(module).use { files ->
                        files.filter { it.toString().endsWith(".class") && !it.toString().endsWith("module-info.class") }.forEach { file ->
                            out.putNextEntry(ZipEntry(module.relativize(file).toString()))
                            Files.copy(file, out)
                            out.closeEntry()
                        }
                    }
                }
            }
        }
        config.writeText("-libraryjars '${jar.absolutePath}'\n")
    }
}
tasks.matching { it.name == "proguardReleaseJars" }.configureEach { dependsOn(exportJdkForProguard) }

// The icon is kept as SVG (icon/here.svg) and made into the bundle's .icns as it is packaged.
val macIcon = layout.buildDirectory.file("icon/Here.icns")
val renderIcon = tasks.register<Exec>("renderMacIcon") {
    inputs.files("icon/here.svg", "icon/icns.swift")
    outputs.file(macIcon)
    workingDir = file("icon")
    commandLine("swift", "icns.swift", "here.svg", macIcon.get().asFile.absolutePath)
}

compose.desktop {
    application {
        mainClass = "cc.hyoban.moments.desktop.MainKt"
        jvmArgs += listOf("-Dapple.awt.application.name=Here", "--enable-native-access=ALL-UNNAMED")
        // Packaged with Temurin 25, fetched by Gradle when it isn't here: Compose needs a jpackage that
        // Android Studio's JDK lacks and refuses Homebrew's.
        // Asked for only when packaging, so other builds neither look for it nor fetch it.
        if (gradle.startParameter.taskNames.any { "package" in it.lowercase() || "distributable" in it.lowercase() || "runtime" in it.lowercase() }) {
            javaHome = javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
                vendor.set(JvmVendorSpec.ADOPTIUM)
            }.get().metadata.installationPath.asFile.absolutePath
        }

        // The release build (`packageReleaseDmg`) runs ProGuard over the jars, taking away what
        // nothing reaches; desktop/rules.pro says what must stay.
        buildTypes.release.proguard {
            isEnabled.set(true)
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("rules.pro"), jdkForProguardConfig)
        }

        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageName = "Here"
            packageVersion = appVersion
            description = "Moments, places and trips"
            vendor = siteConfig["site"].let { (it as Map<String, Any?>)["author"] as String }
            // What the app runs on: the JDK's own modules it needs, nothing more.
            modules("java.instrument", "java.sql", "jdk.unsupported")
            macOS {
                bundleID = applicationId
                dockName = "Here"
                appCategory = "public.app-category.lifestyle"
                iconFile.set(macIcon)
                minimumSystemVersion = "14.0"
                infoPlist {
                    // The words it is shown in follow the Mac's language: Chinese or English.
                    extraKeysRawXml = """
                        <key>CFBundleDevelopmentRegion</key><string>zh_CN</string>
                        <key>CFBundleLocalizations</key><array><string>zh_CN</string><string>en</string></array>
                        <key>NSHighResolutionCapable</key><true/>
                    """.trimIndent()
                }
            }
        }
    }
}

tasks.matching { it.name in setOf("createDistributable", "packageDmg", "packageReleaseDmg", "createReleaseDistributable") }
    .configureEach { dependsOn(renderIcon) }

// The runtime's modules compressed, as Compose's jlink can but doesn't offer to set: it halves what
// the JDK takes on disk.
tasks.matching { it.javaClass.name.startsWith("org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask") }.configureEach {
    @Suppress("UNCHECKED_CAST")
    val level = javaClass.methods.first { it.name.startsWith("getCompressionLevel") }.invoke(this) as Property<Any>
    val zip = Class.forName("org.jetbrains.compose.desktop.application.internal.RuntimeCompressionLevel", true, javaClass.classLoader)
    level.set(zip.enumConstants.first { (it as Enum<*>).name == "ZIP" })
}

// What the packaged app's jars carry that it never reads, taken out of the app image before it
// becomes the DMG (`:desktop:run` keeps them): Skiko's library, which the launcher loads from beside
// the jars (`skiko.library.path`); SQLite's libraries for other systems; and the ICU data the lunar
// calendar doesn't need (break iteration, collation, names of languages, regions, currencies and
// units, transliteration, spelled-out numbers). The bundle is signed again as Compose signs it.
tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    // Plain values, read here, so the task keeps to the configuration cache.
    val bundle = layout.buildDirectory.dir(if (name == "createReleaseDistributable") "compose/binaries/main-release/app/Here.app" else "compose/binaries/main/app/Here.app").get().asFile
    val resources = layout.buildDirectory.dir("compose/default-resources").get().asFile
    doLast {
        val icuData = "com/ibm/icu/impl/data/icudata/"
        val icuUnread = listOf("brkitr/", "coll/", "lang/", "region/", "curr/", "unit/", "translit/", "rbnf/", "unames.icu").map { icuData + it }
        fun unread(jar: String, entry: String): Boolean = when {
            jar.startsWith("skiko-awt-runtime-") -> entry.endsWith(".dylib")
            jar.startsWith("sqlite-bundled-jvm-") -> entry.startsWith("natives/") && !entry.startsWith("natives/osx_arm64/") && !entry.endsWith("/")
            jar.startsWith("icu4j-") -> icuUnread.any { entry.startsWith(it) }
            else -> false
        }
        File(bundle, "Contents/app").listFiles().orEmpty().filter { it.extension == "jar" }.forEach { jar ->
            val dropped = ZipFile(jar).use { zip -> zip.entries().asSequence().any { unread(jar.name, it.name) } }
            if (!dropped) return@forEach
            val slim = File(jar.parentFile, jar.name + ".slim")
            ZipFile(jar).use { zip ->
                ZipOutputStream(slim.outputStream().buffered()).use { out ->
                    zip.entries().asSequence().filterNot { unread(jar.name, it.name) }.forEach { entry ->
                        out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                        zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
            slim.renameTo(jar)
        }
        val entitlements = resources.walkTopDown().first { it.name == "default-entitlements.plist" }
        val sign = ProcessBuilder("codesign", "--force", "--deep", "--options", "runtime", "--entitlements", entitlements.absolutePath, "--sign", "-", bundle.absolutePath)
            .inheritIO().start()
        check(sign.waitFor() == 0) { "codesign failed" }
    }
}
