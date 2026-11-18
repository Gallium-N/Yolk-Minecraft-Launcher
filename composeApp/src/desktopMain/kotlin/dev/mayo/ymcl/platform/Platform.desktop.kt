package dev.mayo.ymcl.platform

import dev.mayo.ymcl.domain.JavaRelease
import dev.mayo.ymcl.domain.JavaReleaseInfo
import dev.mayo.ymcl.domain.JavaRuntime
import java.awt.Desktop
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.MalformedInputException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

actual fun currentOsFamily(): OsFamily {
    val name = System.getProperty("os.name").orEmpty().lowercase()
    return if (name.contains("win")) OsFamily.WINDOWS else OsFamily.UNIX
}

actual fun appDataDirectory(): String {
    if (currentOsFamily().isWindows) {
        val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
        return PathUtil.join(PathUtil.normalize(appData), "YMCL")
    }
    val xdg = System.getenv("XDG_DATA_HOME")
    val base = if (!xdg.isNullOrBlank()) {
        PathUtil.normalize(xdg)
    } else {
        PathUtil.join(PathUtil.normalize(System.getProperty("user.home")), ".local/share")
    }
    return PathUtil.join(base, "ymcl")
}

actual fun instancesDirectory(): String = PathUtil.join(appDataDirectory(), "instances")

actual fun defaultGameDirectory(): String {
    val home = File(System.getProperty("user.home"))
    val candidates = listOf(
        File(home, ".minecraft"),
        File(home, "Minecraft"),
        File(home, "Documents/.minecraft"),
    )
    return (candidates.firstOrNull { dir ->
        val versions = File(dir, "versions")
        versions.isDirectory && versions.listFiles()?.any { it.isDirectory } == true
    } ?: candidates.first()).absolutePath
}

actual fun detectJavaRuntimes(): List<JavaRuntime> {
    // Read-only discovery (P7): never spawn `java -version`. Collect candidate
    // JAVA HOMES, read each <home>/release, dedupe by canonical path.
    val homes = linkedSetOf<File>()

    fun addHome(home: File?) {
        if (home != null && home.isDirectory) homes += home
    }

    // Launcher self-installed runtimes first.
    val javaRoot = File(appDataDirectory(), "java")
    javaRoot.listFiles()?.filter { it.isDirectory }?.forEach(::addHome)

    addHome(System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let(::File))

    // PATH: each bin/java -> walk up two levels to the home (release lives there).
    System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .forEach { entry ->
            val dir = File(entry)
            val bin = File(dir, if (currentOsFamily().isWindows) "java.exe" else "java")
            if (bin.isFile) addHome(bin.parentFile?.parentFile)
        }

    fun children(dir: File): List<File> =
        dir.listFiles()?.filter { it.isDirectory }.orEmpty()

    if (!currentOsFamily().isWindows) {
        children(File("/usr/lib/jvm")).forEach(::addHome)
        children(File("/usr/java")).forEach(::addHome)
    }
    children(File(System.getProperty("user.home"), ".sdkman/candidates/java")).forEach(::addHome)
    children(File(System.getProperty("user.home"), ".gradle/jdks")).forEach(::addHome)

    if (currentOsFamily().isWindows) {
        val env = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        listOf(
            "$env\\Eclipse Adoptium",
            "$env\\Java",
            "$env\\Microsoft\\jdk-",
            "$env\\Zulu",
            System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\Eclipse Adoptium" },
        ).filterNotNull().forEach { base ->
            val dir = File(base)
            if (dir.isDirectory) children(dir).forEach(::addHome)
        }
    }

    val seen = mutableSetOf<String>()
    return homes.mapNotNull { home ->
        val canonical = try {
            home.canonicalFile
        } catch (_: Exception) {
            home.absoluteFile
        }
        if (!seen.add(canonical.path)) return@mapNotNull null
        probeHome(canonical)
    }.sortedWith(compareByDescending<JavaRuntime> { it.major }.thenBy { it.path })
}

/** Build a runtime from a JAVA HOME by reading only its `release` file. */
internal fun probeHome(home: File): JavaRuntime? {
    val release = File(home, "release")
    if (!release.isFile) return null
    val info = try {
        JavaRelease.parse(release.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    } ?: return null
    return javaRuntimeFrom(home, info, "detected")
}

/** Kept for the registry test: a binary path is resolved back to its home. */
internal fun probeJava(binary: File): JavaRuntime? {
    val home = binary.parentFile?.parentFile ?: return null
    return probeHome(home)
}

private fun javaRuntimeFrom(
    home: File,
    info: JavaReleaseInfo,
    source: String,
    installedAt: String? = null,
): JavaRuntime {
    val executable = if (currentOsFamily().isWindows) "java.exe" else "java"
    val binary = File(File(home, "bin"), executable)
    val vendor = normalizeVendor(info.vendor)
    val arch = normalizeArch(info.osArch)
    val os = if (currentOsFamily().isWindows) "windows" else "linux"
    val id = buildString {
        append(slug(vendor))
        append('-').append(info.version.replace('+', '_'))
        append('-').append(arch).append('-').append(os)
        append('-').append(
            UUID.nameUUIDFromBytes(home.absolutePath.toByteArray()).toString().take(8),
        )
    }
    return JavaRuntime(
        id = id,
        vendor = vendor,
        major = info.major,
        version = info.version,
        arch = arch,
        os = os,
        path = binary.absolutePath,
        source = source,
        installedAt = installedAt,
    )
}

private fun normalizeVendor(raw: String): String = when {
    raw.isBlank() -> "Unknown"
    raw.contains("Adoptium", ignoreCase = true) -> "Temurin"
    raw.contains("Oracle", ignoreCase = true) -> "Oracle"
    raw.contains("Azul", ignoreCase = true) -> "Zulu"
    raw.contains("Microsoft", ignoreCase = true) -> "Microsoft"
    raw.contains("Amazon", ignoreCase = true) -> "Corretto"
    raw.contains("OpenJDK", ignoreCase = true) -> "OpenJDK"
    else -> raw
}

private fun normalizeArch(raw: String): String = when (raw.lowercase()) {
    "amd64", "x86_64", "x64" -> "x64"
    "aarch64", "arm64" -> "aarch64"
    "x86", "i386", "i686" -> "x86"
    else -> raw.ifBlank { "unknown" }
}

private fun slug(value: String): String = value.lowercase()
    .map { if (it.isLetterOrDigit()) it else '-' }
    .joinToString("")
    .trim('-')
    .ifBlank { "java" }

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun decodeLogBytes(bytes: ByteArray): String {
    val withoutBom = if (bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    ) {
        bytes.copyOfRange(3, bytes.size)
    } else {
        bytes
    }

    decode(withoutBom, Charsets.UTF_8)?.let { return it }

    // Process logs from Chinese Windows and old launchers are often CP936/GBK;
    // only fall back when UTF-8 strictly fails, so valid UTF-8 is never mis-decoded.
    return decode(withoutBom, Charset.forName("GBK"))
        ?: throw java.io.IOException("无法解码字节（既不是 UTF-8 也不是 GBK）")
}

actual fun readTextAuto(path: String): String = try {
    decodeLogBytes(File(PathUtil.toNative(path)).readBytes())
} catch (e: java.io.IOException) {
    throw java.io.IOException("无法解码文本（既不是 UTF-8 也不是 GBK）：$path", e)
}

private fun decode(bytes: ByteArray, charset: Charset): String? = try {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: MalformedInputException) {
    null
} catch (_: CharacterCodingException) {
    null
}

actual fun writeTextUtf8Lf(path: String, text: String) {
    val file = File(PathUtil.toNative(path))
    file.parentFile?.mkdirs()
    file.writeText(text.replace("\r\n", "\n"), Charsets.UTF_8)
}

actual fun openFolder(path: String): Boolean {
    val target = File(PathUtil.toNative(path))
    if (!target.exists()) return false
    return try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(target)
            true
        } else {
            launchDetachedProcess(listOf("xdg-open", target.absolutePath), null) != null
        }
    } catch (_: Exception) {
        false
    }
}

actual fun pickModpackFile(): String? = try {
    val dialog = java.awt.FileDialog(
        null as java.awt.Frame?,
        "选择整合包（.mrpack / .zip）",
        java.awt.FileDialog.LOAD,
    )
    dialog.setFilenameFilter { _, name ->
        name.endsWith(".mrpack", ignoreCase = true) || name.endsWith(".zip", ignoreCase = true)
    }
    dialog.isVisible = true
    val dir = dialog.directory
    val file = dialog.file
    if (dir != null && file != null) File(dir, file).absolutePath else null
} catch (e: Exception) {
    println("YMCL file chooser failed: ${e.message}")
    null
}

private val systemDark: Boolean by lazy { detectSystemDark() }

actual fun isSystemDark(): Boolean = systemDark

actual fun themeOverride(): String? {
    val property = System.getProperty("ymcl.theme")
    return property?.takeIf { it.isNotBlank() } ?: System.getenv("YMCL_THEME")?.takeIf { it.isNotBlank() }
}

private fun detectSystemDark(): Boolean {
    themeOverride()?.trim()?.lowercase()?.let { override ->
        if (override == "dark") return true
        if (override == "light") return false
    }

    if (currentOsFamily().isWindows) {
        val output = runCommand(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme",
        ) ?: return false
        return output.contains("0x0")
    }

    val colorScheme = runCommand("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
        ?.lowercase()
    when {
        colorScheme == null -> Unit
        colorScheme.contains("prefer-dark") -> return true
        // "prefer-light" is authoritative even when the GTK theme name says "dark".
        colorScheme.contains("prefer-light") -> return false
    }

    val gtkTheme = runCommand("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")
    if (gtkTheme != null && gtkTheme.contains("dark", ignoreCase = true)) return true

    val envTheme = System.getenv("GTK_THEME")
    return envTheme?.contains("dark", ignoreCase = true) == true
}

private fun runCommand(vararg command: String): String? = try {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (process.waitFor(1, TimeUnit.SECONDS)) output else {
        process.destroyForcibly()
        null
    }
} catch (_: Exception) {
    null
}

actual fun showNotification(title: String, body: String) {
    try {
        if (currentOsFamily().isWindows) {
            val script = "New-BurntToastNotification -Text '$title','$body'"
            launchDetachedProcess(
                listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script),
                null,
            )
        } else {
            launchDetachedProcess(listOf("notify-send", title, body), null)
        }
    } catch (_: Exception) {
        // Notifications are best-effort; never break the launcher over them.
    }
}

actual fun launchDetachedProcess(command: List<String>, workingDirectory: String?): Long? {
    val builder = ProcessBuilder(command)
    if (workingDirectory != null) {
        builder.directory(File(PathUtil.toNative(workingDirectory)))
    }
    val process = builder.start()
    return try {
        process.pid()
    } catch (_: UnsupportedOperationException) {
        null
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

actual fun formatLastLaunch(epochMillis: Long?, nowMillis: Long): String {
    if (epochMillis == null) return "未启动"

    val zone = ZoneId.systemDefault()
    val then = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val minutes = Duration.between(then, now).toMinutes()

    if (minutes < 0) return "刚刚"
    if (minutes < 60) return "${minutes.coerceAtLeast(1)} 分钟前"

    val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
    return when {
        days <= 0L -> "${Duration.between(then, now).toHours()} 小时前"
        days == 1L -> "昨天 ${then.format(TIME_FORMAT)}"
        days < 7L -> "$days 天前"
        days < 14L -> "上周${weekdayChinese(then.dayOfWeek.value)}"
        else -> "$days 天前"
    }
}

private fun weekdayChinese(isoDay: Int): String = when (isoDay) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    else -> "日"
}
