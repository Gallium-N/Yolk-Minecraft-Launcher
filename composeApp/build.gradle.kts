import java.io.File
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// P8: version + git sha compiled into the app so Settings/About can show
// `v<version> (<sha>)` both in dev runs and in packaged builds. CI passes
// -Pymcl.version=<V from git describe>; default keeps local builds working.
val ymclVersion: String = providers.gradleProperty("ymcl.version").orElse("1.0.0").get()
val ymclSha: String = try {
    ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start().inputStream.bufferedReader().use { it.readLine()?.trim() }
        ?.takeIf { it.isNotBlank() } ?: "dev"
} catch (_: Exception) { "dev" }

val buildInfoDir = layout.buildDirectory.dir("generated/ymcl/commonMain/kotlin")
val generateBuildInfo by tasks.registering {
    val out = buildInfoDir.get().asFile
    outputs.dir(out)
    doLast {
        val pkg = File(out, "dev/mayo/ymcl").apply { mkdirs() }
        File(pkg, "BuildInfo.kt").writeText(
            """
            package dev.mayo.ymcl

            /** Generated at build time by composeApp/build.gradle.kts (P8). */
            object BuildInfo {
                const val VERSION = "$ymclVersion"
                const val SHA = "$ymclSha"
            }
            """.trimIndent(),
        )
    }
}

kotlin {
    jvmToolchain(21)
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(buildInfoDir)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            // Only the small core icon set (PlayArrow / Warning / Settings / MoreVert / ...).
            // material-icons-extended is intentionally not used.
            implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(project(":hmcl-core"))
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("junit:junit:4.13.2")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.mayo.ymcl.DesktopMainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "YMCL"
            packageVersion = ymclVersion

            // P8 runtime trimming. jdeps --print-module-deps over lib/app/*.jar
            // plus the runtime-only modules the app reaches reflectively (see
            // task book §3; java.net.http is mandatory — omitting it broke the
            // downloader). -Pymcl.allModules restores the untrimmed jlink image.
            if (project.hasProperty("ymcl.allModules")) {
                includeAllModules = true
            } else {
                // jdeps --print-module-deps over lib/app/*.jar (excluding the
                // unused javafx/fx-gson bridges) yields the first 8; the rest
                // are runtime-only additions from the task book (reflective /
                // charset / zipfs / HTTPS / locale) so nothing required is lost.
                modules(
                    "java.base",
                    "java.desktop",
                    "java.instrument",
                    "java.net.http",
                    "java.sql",
                    "jdk.attach",
                    "jdk.management",
                    "jdk.unsupported",
                    "jdk.crypto.ec",
                    "jdk.charsets",
                    "jdk.zipfs",
                    "java.management",
                    "jdk.localedata",
                    "java.logging",
                    "java.xml",
                )
            }
            windows {
                iconFile.set(project.file("../design/logo/ymcl-mark-dark.ico"))
                console = project.hasProperty("ymcl.console")
                // Single-file installer (WiX). perUserInstall keeps it elevation-free,
                // and upgradeUuid is what lets a newer build replace an older one.
                perUserInstall = true
                dirChooser = true
                shortcut = true
                menuGroup = "YMCL"
                upgradeUuid = "5f3d8f0e-2b0a-4c8e-9a41-1e6f7b2c9d31"
            }
            linux {
                iconFile.set(project.file("../design/logo/ymcl-mark-light-512.png"))
                menuGroup = "Games"
                appCategory = "Game"
                shortcut = true
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateBuildInfo)
}

// Forward -Dymcl.theme=dark|light to the application JVM (the Gradle JVM would otherwise
// swallow it). Used to verify both schemes without changing the machine's desktop theme.
tasks.withType<JavaExec>().configureEach {
    // QA/system properties for the real UI run (`./gradlew run -Dymcl.game.root=...`).
    listOf(
        "ymcl.theme", "ymcl.game.root", "ymcl.window.width", "ymcl.window.height",
        "ymcl.qa.autolaunch", "ymcl.qa.modrinth.instance", "ymcl.qa.modrinth.search",
        "ymcl.qa.modrinth.dialog", "ymcl.qa.modrinth.install",
        "ymcl.qa.modpack.file", "ymcl.qa.modpack.start",
        "ymcl.qa.newinstance.version", "ymcl.qa.newinstance.loader",
        "ymcl.qa.newinstance.start", "ymcl.qa.newinstance.stageDelayMs",
        "ymcl.qa.newinstance.cancelAtStage",
        "ymcl.qa.neoforge.unreachable", "ymcl.qa.loader.unreachable",
        "ymcl.qa.loader.timeoutMs", "ymcl.qa.getcontent.tab", "ymcl.qa.page",
        // QA-only: force reduceMotion without changing the persisted setting.
        "ymcl.reduceMotion",
    ).forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }

    // Skiko extracts its native library under $HOME/.skiko by default. In
    // read-only/sandboxed environments fall back to a build-local data dir.
    val skikoHome = File(System.getProperty("user.home"), ".skiko")
    if (!skikoHome.canWrite()) {
        val local = layout.buildDirectory.dir("skiko-data").get().asFile
        local.mkdirs()
        systemProperty("skiko.data.path", local.absolutePath)
    }
}

// KMP with a single custom target does not create a task called `test`, but the phase-1
// acceptance command is `./gradlew test`.
tasks.register("test") {
    group = "verification"
    description = "Runs all desktop unit tests."
    dependsOn("desktopTest")
}

tasks.named<Test>("desktopTest") {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

// P3 step-1 meta self-check. Run with -Dymcl.meta.mode=offline for the offline
// fallback check; -Dymcl.meta.preferMirror=false switches to the official source.
tasks.register<JavaExec>("metaSelfCheck") {
    group = "verification"
    description = "Runs the P3 meta-layer online/offline self-check."
    val mainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = mainCompilation.output.allOutputs + mainCompilation.runtimeDependencyFiles
    mainClass.set("dev.mayo.ymcl.MetaSelfCheckKt")
    System.getProperty("ymcl.meta.mode")?.let { systemProperty("ymcl.meta.mode", it) }
    System.getProperty("ymcl.meta.preferMirror")?.let { systemProperty("ymcl.meta.preferMirror", it) }
}

// P3 step-3 self-check: install vanilla 1.20.1 into an isolated directory and launch it.
tasks.register<JavaExec>("installSelfCheck") {
    group = "verification"
    description = "Runs the P3 vanilla install + launch self-check."
    val mainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = mainCompilation.output.allOutputs + mainCompilation.runtimeDependencyFiles
    mainClass.set("dev.mayo.ymcl.InstallSelfCheckKt")
    System.getProperty("ymcl.install.dir")?.let { systemProperty("ymcl.install.dir", it) }
    systemProperty(
        "ymcl.shot.path",
        rootProject.layout.buildDirectory.file("p3-vanilla-menu.png").get().asFile.absolutePath,
    )
}

// A-group structural proof: real stalls, candidate failure reasons, failure list.
// -Dymcl.a3=true switches to the deliberate non-zero-exit case.
tasks.register<JavaExec>("stallSelfCheck") {
    group = "verification"
    description = "Runs the P3 A-group structural self-check."
    val mainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = mainCompilation.output.allOutputs + mainCompilation.runtimeDependencyFiles
    mainClass.set("dev.mayo.ymcl.StallSelfCheckKt")
    System.getProperty("ymcl.a3")?.let { systemProperty("ymcl.a3", it) }
}

// P3 v2 unified-loader real-machine check (fabric/forge/quilt/neoforge).
tasks.register<JavaExec>("loaderV2SelfCheck") {
    group = "verification"
    description = "Runs the P3 v2 unified loader install + launch self-check."
    val mainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = mainCompilation.output.allOutputs + mainCompilation.runtimeDependencyFiles
    mainClass.set("dev.mayo.ymcl.LoaderV2SelfCheckKt")
    listOf(
        "ymcl.v2.mode", "ymcl.v2.gameRoot", "ymcl.v2.mc", "ymcl.v2.loaderVersion",
        "ymcl.v2.instanceId", "ymcl.v2.official", "ymcl.v2.shot",
    ).forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}

// P3 v2 acceptance #8: two-process cross-restart Range resume.
tasks.register<JavaExec>("resumeSelfCheck") {
    group = "verification"
    description = "Phase kill/resume for cross-restart .part Range resume."
    val mainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = mainCompilation.output.allOutputs + mainCompilation.runtimeDependencyFiles
    mainClass.set("dev.mayo.ymcl.ResumeSelfCheckKt")
    listOf(
        "ymcl.resume.phase", "ymcl.resume.root", "ymcl.resume.url", "ymcl.resume.sha1",
        "ymcl.resume.size", "ymcl.resume.killAt",
    ).forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}

// P3 v2 acceptance #6/#7: 1.12.2 virtual assets + Java 8 launch.
tasks.register<JavaExec>("legacySelfCheck") {
    group = "verification"
    description = "Installs 1.12.2 with legacy virtual assets and launches on Java 8."
    val mainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = mainCompilation.output.allOutputs + mainCompilation.runtimeDependencyFiles
    mainClass.set("dev.mayo.ymcl.LegacySelfCheckKt")
    listOf(
        "ymcl.v2.legacyRoot", "ymcl.v2.legacyMc", "ymcl.v2.java8", "ymcl.v2.shot",
    ).forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}

// P3 step-4 loader self-check. -Dymcl.loader.mode=fabric|forge|idempotent|error.
tasks.register<JavaExec>("loaderSelfCheck") {
    group = "verification"
    description = "Runs the P3 step-4 loader install + launch self-check."
    val mainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = mainCompilation.output.allOutputs + mainCompilation.runtimeDependencyFiles
    mainClass.set("dev.mayo.ymcl.LoaderSelfCheckKt")
    System.getProperty("ymcl.loader.mode")?.let { systemProperty("ymcl.loader.mode", it) }
    System.getProperty("ymcl.shot.path")?.let { systemProperty("ymcl.shot.path", it) }
}

// P4 step-0 cold-path gap: fresh root with only assets pre-warmed, real
// network downloads for libraries + client jar. -Dymcl.cold.root to override.
tasks.register<JavaExec>("coldPathSelfCheck") {
    group = "verification"
    description = "Runs the P4 cold-path (assets pre-warmed only) install + launch."
    val mainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = mainCompilation.output.allOutputs + mainCompilation.runtimeDependencyFiles
    mainClass.set("dev.mayo.ymcl.ColdPathSelfCheckKt")
    listOf(
        "ymcl.cold.root", "ymcl.cold.instance", "ymcl.cold.shot",
        "ymcl.cold.official", "ymcl.cold.injectstalls",
    ).forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

// P5 wrap-up: real mrpack import (base / loader / files) + real HmclEngine launch,
// waiting for the Forge / NeoForge main-menu marker and a window-scoped screenshot.
tasks.register<JavaExec>("modpackE2ESelfCheck") {
    group = "verification"
    description = "Runs the P5 Forge/NeoForge modpack end-to-end self-check."
    val mainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = mainCompilation.output.allOutputs + mainCompilation.runtimeDependencyFiles
    mainClass.set("dev.mayo.ymcl.ModpackE2ESelfCheckKt")
    listOf(
        "ymcl.p5.pack", "ymcl.p5.root", "ymcl.p5.shot", "ymcl.p5.expected",
        "ymcl.p5.launchOnly", "ymcl.p5.mc", "ymcl.game.root", "ymcl.theme",
    ).forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

// P4 step-0 end-to-end: one shared gameRoot, three version-isolated instances.
// Override the seed / gameRoot with -Dymcl.* if the P3 install lives elsewhere.
tasks.register<JavaExec>("installE2ESelfCheck") {
    group = "verification"
    description = "Runs the P4 shared-layout end-to-end self-check."
    val mainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = mainCompilation.output.allOutputs + mainCompilation.runtimeDependencyFiles
    mainClass.set("dev.mayo.ymcl.E2ESelfCheckKt")
    listOf(
        "ymcl.game.root", "ymcl.seed.root", "ymcl.seed.fabric",
        "ymcl.seed.version.json", "ymcl.shot.dir",
    ).forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
