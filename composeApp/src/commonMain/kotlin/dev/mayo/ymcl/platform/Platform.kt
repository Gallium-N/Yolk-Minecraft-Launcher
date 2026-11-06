package dev.mayo.ymcl.platform

import dev.mayo.ymcl.domain.JavaRuntime

/** Everything host-specific the launcher needs. Implemented only in platform actuals. */
expect fun appDataDirectory(): String

expect fun instancesDirectory(): String

/** Picks the existing `~/.minecraft`, `~/Minecraft` or `~/Documents/.minecraft`. */
expect fun defaultGameDirectory(): String

/** Probes JAVA_HOME, PATH, system JVM roots, sdkman and Gradle-managed JDKs. */
expect fun detectJavaRuntimes(): List<JavaRuntime>

expect fun nowMillis(): Long

/** Reads a text file as UTF-8, falling back to CP936/GBK on malformed input. */
expect fun readTextAuto(path: String): String

/** Writes UTF-8 without BOM and normalizes line endings to LF. */
expect fun writeTextUtf8Lf(path: String, text: String)

expect fun openFolder(path: String): Boolean

expect fun isSystemDark(): Boolean

/** "-Dymcl.theme=dark|light" (or YMCL_THEME) used to verify both schemes on Linux. */
expect fun themeOverride(): String?

expect fun showNotification(title: String, body: String)

/** Starts a detached process; returns its pid when the platform reports one. */
expect fun launchDetachedProcess(command: List<String>, workingDirectory: String?): Long?

/** Formats last-launch time in the locale of the product (Chinese, phase 1). */
expect fun formatLastLaunch(epochMillis: Long?, nowMillis: Long): String
