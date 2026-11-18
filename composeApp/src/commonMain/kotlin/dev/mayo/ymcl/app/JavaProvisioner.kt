package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.JavaRuntime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Content of <appRoot>/java/<id>/install.json (P7 §3). All paths are relative. */
@Serializable
data class JavaInstallInfo(
    val schemaVersion: Int = 1,
    val vendor: String = "Temurin",
    val major: Int = 0,
    val fullVersion: String = "",
    val sha256: String = "",
    val installedAt: String = "",
    /** Relative id of the runtime home under `<appRoot>/java/`, no absolute path. */
    val path: String = "",
)

data class JavaAsset(
    val name: String,
    val checksum: String,
    val link: String,
)

/**
 * Pure helpers for the Java auto-provisioner. Network/file IO lives in a
 * platform actual; everything testable offline is here.
 */
object JavaProvisioner {
    private val json = Json { ignoreUnknownKeys = true }

    /** `available_releases` response -> distinct, sorted majors. */
    fun parseAvailableMajors(body: String): List<Int> = try {
        json.parseToJsonElement(body).jsonObject["available_releases"]
            ?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
            ?.distinct()?.sorted().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    /** `[0].binary.package.{name,checksum}` + `[0].binary.package.link`. */
    fun parseAsset(body: String): JavaAsset? = try {
        val first = json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject ?: return null
        val bin = first["binary"]?.jsonObject ?: return null
        val pkg = bin["package"]?.jsonObject ?: return null
        val name = pkg["name"]?.jsonPrimitive?.content ?: return null
        val checksum = pkg["checksum"]?.jsonPrimitive?.content.orEmpty()
        val link = pkg["link"]?.jsonPrimitive?.content.orEmpty()
        JavaAsset(name, checksum, link)
    } catch (_: Exception) {
        null
    }

    /** Relative install id (no absolute path), e.g. `temurin-21-linux-x64`. */
    fun installId(vendor: String, major: Int, os: String, arch: String): String {
        fun slug(v: String) = v.lowercase().map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("").trim('-').ifBlank { "java" }
        return "${slug(vendor)}-$major-$os-$arch"
    }

    fun sha256Matches(actualHex: String, expectedHex: String): Boolean =
        expectedHex.isBlank() || actualHex.equals(expectedHex, ignoreCase = true)

    /** Reject zip-slip / absolute / backslash traversal paths. */
    fun safeEntry(name: String): Boolean {
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.contains("..")) return false
        if (name.contains("\\")) return false
        return true
    }

    /**
     * If every archive entry shares a single top-level directory, that prefix
     * should be stripped when extracting. Returns the prefix (with trailing /)
     * or null when there is no single common root.
     */
    fun singleTopDir(entries: List<String>): String? {
        val normalized = entries.filter { it.isNotBlank() && safeEntry(it) }
        if (normalized.isEmpty()) return null
        val top = normalized.map { it.substringBefore('/', "") }.filter { it.isNotEmpty() }.distinct()
        return if (top.size == 1 && normalized.all { it.contains('/') }) "${top.single()}/" else null
    }

    /** Label used in logs for a candidate URL (which mirror/source answered). */
    fun sourceLabel(url: String): String = when {
        url.contains("huawei", true) -> "huawei"
        url.contains("tuna", true) || url.contains("tsinghua", true) -> "tuna"
        url.contains("github", true) || url.contains("adoptium.net", true) -> "github"
        else -> url.substringAfter("//").substringBefore("/").ifBlank { url }
    }
}

/** Progress of one Java install, reported to the wizard/step ladder. */
data class JavaInstallProgress(
    val phase: String,
    val bytes: Long = 0L,
    val total: Long = 0L,
)

data class JavaInstallResult(
    val runtime: JavaRuntime?,
    val usedSource: String,
    val bytes: Long,
    val sha256: String,
    val error: String = "",
)

/**
 * Host boundary for Java auto-install. Implemented in desktopMain (network +
 * unzip/tar), faked in tests. All progress/cancellation stays on the UI path
 * (never block a click — see the P0.2 curl-20s lesson).
 */
interface JavaInstallOps {
    /** Distinct, sorted majors from Adoptium's available_releases; empty on failure. */
    fun availableMajors(): List<Int>

    /** Blocks on IO; caller runs it on a background thread. */
    fun install(
        major: Int,
        onProgress: (JavaInstallProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): JavaInstallResult

    /** Re-scan runtimes after an install (reads release files, no spawn). */
    fun rescan(): List<JavaRuntime>
}
