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

kotlin {
    jvmToolchain(21)
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
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
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "YMCL"
            packageVersion = "1.0.0"
        }
    }
}

// Forward -Dymcl.theme=dark|light to the application JVM (the Gradle JVM would otherwise
// swallow it). Used to verify both schemes without changing the machine's desktop theme.
tasks.withType<JavaExec>().configureEach {
    // QA/system properties for the real UI run (`./gradlew run -Dymcl.game.root=...`).
    listOf(
        "ymcl.theme", "ymcl.game.root", "ymcl.window.width", "ymcl.window.height",
        "ymcl.qa.autolaunch",
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
    listOf("ymcl.cold.root", "ymcl.cold.instance", "ymcl.cold.shot", "ymcl.cold.official").forEach { key ->
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
