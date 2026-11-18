package dev.mayo.ymcl.domain

/**
 * Parsed fields from a JDK/JRE `release` file — read-only discovery (P7).
 * We never spawn `java -version`: scanning spawn a process per candidate is
 * expensive (especially on Windows), and a `release` file has everything we
 * need. `java.version` in a release file is `JAVA_VERSION`.
 */
data class JavaReleaseInfo(
    val version: String,
    val major: Int,
    val vendor: String,
    val osArch: String,
)

/** Pure parser for a Java `release` file's `KEY="value"` lines. */
object JavaRelease {
    /** `1.8.0_412` -> 8, `21.0.13` -> 21, `17` -> 17; -1 if unparseable. */
    fun majorOf(version: String): Int {
        val v = version.trim()
        val normalized = if (v.startsWith("1.")) v.substring(2) else v
        val digits = normalized.takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: -1
    }

    fun parse(text: String): JavaReleaseInfo? {
        val kv = HashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
                value = value.substring(1, value.length - 1)
            }
            kv[key] = value
        }
        val version = kv["JAVA_VERSION"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val major = majorOf(version)
        if (major < 0) return null
        val vendor = kv["IMPLEMENTOR"]?.takeIf { it.isNotBlank() }
            ?: kv["JAVA_VENDOR"]?.takeIf { it.isNotBlank() }
            ?: ""
        return JavaReleaseInfo(
            version = version,
            major = major,
            vendor = vendor,
            osArch = kv["OS_ARCH"]?.takeIf { it.isNotBlank() } ?: "",
        )
    }
}
