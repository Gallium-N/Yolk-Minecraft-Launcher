package dev.mayo.ymcl.platform

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
    val executable = if (currentOsFamily().isWindows) "java.exe" else "java"
    val candidates = linkedSetOf<File>()

    fun add(binary: File) {
        if (binary.isFile && binary.canExecute()) {
            candidates += binary
        }
    }

    fun addHome(home: File?) {
        if (home != null) add(File(home, "bin/$executable"))
    }

    addHome(System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let(::File))
    System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .forEach { add(File(it, executable)) }

    fun children(dir: File): List<File> =
        dir.listFiles()?.filter { it.isDirectory }.orEmpty()

    children(File(Path.of("/usr", "lib", "jvm").toString())).forEach(::addHome)
    children(File(System.getProperty("user.home"), ".sdkman/candidates/java")).forEach(::addHome)
    children(File(System.getProperty("user.home"), ".gradle/jdks")).forEach(::addHome)

    // Windows note for P6/P7: also scan
    //   %ProgramFiles%\Eclipse Adoptium\*\bin\java.exe
    // and HKCU\Software\Adoptium\JDK / HKLM\SOFTWARE\JavaSoft\JDK registry entries.

    val seen = mutableSetOf<String>()
    return candidates.mapNotNull { binary ->
        val canonical = try {
            binary.canonicalFile
        } catch (_: Exception) {
            binary.absoluteFile
        }
        if (!seen.add(canonical.path)) return@mapNotNull null
        probeJava(canonical)
    }.sortedWith(compareByDescending<JavaRuntime> { it.major }.thenBy { it.path })
}

internal fun probeJava(binary: File): JavaRuntime? {
    return try {
        val process = ProcessBuilder(binary.absolutePath, "-XshowSettings:properties", "-version")
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            println("YMCL java probe timeout: ${binary.absolutePath}")
            return null
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.exitValue() != 0) {
            println("YMCL java probe failed (${process.exitValue()}): ${binary.absolutePath}")
            return null
        }

        val properties = output.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null
            else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }.toMap()

        val version = properties["java.version"] ?: return null
        val vendor = normalizeVendor(properties["java.vendor"].orEmpty())
        val major = parseMajor(version)
        if (major < 0) return null
        val arch = normalizeArch(properties["os.arch"].orEmpty())
        val os = if (currentOsFamily().isWindows) "windows" else "linux"
        val id = buildString {
            append(slug(vendor))
            append('-').append(version.replace('+', '_'))
            append('-').append(arch).append('-').append(os)
            append('-').append(
                UUID.nameUUIDFromBytes(binary.absolutePath.toByteArray()).toString().take(8),
            )
        }

        JavaRuntime(
            id = id,
            vendor = vendor,
            major = major,
            version = version,
            arch = arch,
            os = os,
            path = binary.absolutePath,
            source = "detected",
            installedAt = null,
        )
    } catch (e: Exception) {
        println("YMCL java probe error for ${binary.absolutePath}: ${e.message}")
        null
    }
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

private fun parseMajor(version: String): Int {
    val normalized = if (version.startsWith("1.")) version.substring(2) else version
    val digits = normalized.takeWhile { it.isDigit() }
    return digits.toIntOrNull() ?: -1
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
