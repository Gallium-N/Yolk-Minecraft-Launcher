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
    System.getProperty("ymcl.theme")?.let { systemProperty("ymcl.theme", it) }

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
