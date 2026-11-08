package dev.mayo.ymcl.platform

import dev.mayo.ymcl.app.InstanceRepository
import dev.mayo.ymcl.app.InstanceScan
import dev.mayo.ymcl.app.InstanceSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** loader.kind values the UI understands. */
private val KNOWN_LOADERS = setOf("vanilla", "fabric", "forge", "quilt", "neoforge")

/** Matches `fabric-loader-<loader>-<mc>` e.g. fabric-loader-0.20.1-1.20.1. */
private val FABRIC_DIR = Regex("""fabric-loader-(.+)-(\d+\.\d+(?:\.\d+)?)$""")
private val MC_VERSION = Regex("""^\d+\.\d+(?:\.\d+)?$""")

/**
 * Host-side [InstanceRepository]: reads `<gameRoot>/versions/<id>/`.
 * Kept in `platform` so `ui`/`app` never touch java.io. Each directory is
 * parsed independently; a bad version json skips just that entry and is counted.
 */
class FileInstanceRepository(
    private val gameRoot: String,
    /** LauncherSettings.isolatedInstances; ANDed with the root-mods override. */
    private val isolatedBySetting: Boolean = true,
) : InstanceRepository {
    private val json = Json { ignoreUnknownKeys = true }

    private val versionsDir: File
        get() = File(PathUtil.toNative(PathUtil.join(gameRoot, "versions")))

    override fun scan(): InstanceScan {
        val dirs = versionsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name } ?: emptyList()

        // A non-empty root mods/ is the legacy shared-root layout.
        val sharedRoot = File(PathUtil.toNative(gameRoot), "mods")
            .listFiles { f -> f.isFile }?.isNotEmpty() == true
        val isolated = isolatedBySetting && !sharedRoot

        val items = mutableListOf<InstanceSummary>()
        val errors = mutableListOf<String>()
        for (dir in dirs) {
            try {
                items += parseOne(dir, isolated)
            } catch (e: Exception) {
                errors += "${dir.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return InstanceScan(items, errors.size, errors)
    }

    private fun parseOne(dir: File, isolated: Boolean): InstanceSummary {
        val id = dir.name
        val portable = readJson(File(dir, "instance.json"))
        val displayName = portable?.str("displayName")?.takeIf { it.isNotBlank() } ?: id

        // A version json is required. A malformed one throws here and is caught
        // by scan() -> that single instance is skipped, not the whole list.
        val versionFile = versionJsonFile(dir, id)
            ?: throw IllegalArgumentException("缺少版本 JSON: ${dir.name}")
        val version = json.parseToJsonElement(readTextAuto(versionFile.absolutePath)).jsonObject

        val loader = inferLoader(id, portable, version)
        val mcVersion = inferMcVersion(id, portable, version, loader)

        return InstanceSummary(
            id = id,
            displayName = displayName,
            mcVersion = mcVersion,
            loaderKind = loader.kind,
            loaderVersion = loader.version,
            isolated = isolated,
            path = PathUtil.join(gameRoot, "versions", id),
        )
    }

    private fun versionJsonFile(dir: File, id: String): File? =
        File(dir, "$id.json").takeIf { it.isFile }
            ?: dir.listFiles { f ->
                f.isFile && f.extension == "json" &&
                    f.name != "instance.json" && f.name != "machine.local.json"
            }?.sortedBy { it.name }?.firstOrNull()

    private fun readJson(file: File): JsonObject? =
        if (file.isFile) {
            runCatching { json.parseToJsonElement(readTextAuto(file.absolutePath)).jsonObject }.getOrNull()
        } else {
            null
        }

    private data class LoaderInfo(val kind: String, val version: String?, val mcFromName: String? = null)

    /**
     * Inference order (task book): instance.json explicit -> version json
     * mainClass -> directory-name pattern -> vanilla fallback. libraries are
     * consulted for the loader version and as a secondary mainClass signal.
     */
    private fun inferLoader(id: String, portable: JsonObject?, version: JsonObject): LoaderInfo {
        // 1. instance.json explicitly names the loader.
        val explicit = portable?.obj("loader")
        val explicitKind = explicit?.str("kind")?.lowercase()?.takeIf { it in KNOWN_LOADERS }
        if (explicitKind != null) {
            val v = explicit?.str("version")?.takeIf { it.isNotBlank() }
                ?: loaderVersionFromLibraries(version, explicitKind)
            return LoaderInfo(explicitKind, v)
        }

        val mainClass = version.str("mainClass").orEmpty()
        val names = libraryNames(version)
        val kind = when {
            names.any { it.startsWith("org.quiltmc:quilt-loader") } || mainClass.contains("quiltmc", true) -> "quilt"
            mainClass.contains("KnotClient") || names.any { it.startsWith("net.fabricmc:fabric-loader") } -> "fabric"
            mainClass.contains("BootstrapLauncher") -> {
                if (names.any { it.startsWith("net.neoforged") }) "neoforge" else "forge"
            }
            names.any { it.startsWith("net.minecraftforge:forge") } -> "forge"
            else -> null
        }
        if (kind != null) {
            return LoaderInfo(kind, loaderVersionFromLibraries(version, kind))
        }

        // 3. Directory name fabric-loader-<loader>-<mc>.
        FABRIC_DIR.find(id)?.let { m ->
            return LoaderInfo("fabric", m.groupValues[1], m.groupValues[2])
        }

        // 4. Fallback.
        return LoaderInfo("vanilla", null)
    }

    private fun loaderVersionFromLibraries(version: JsonObject, kind: String): String? {
        val names = libraryNames(version)
        return when (kind) {
            "fabric" -> names.firstOrNull { it.startsWith("net.fabricmc:fabric-loader:") }
                ?.substringAfterLast(':')
            "quilt" -> names.firstOrNull { it.startsWith("org.quiltmc:quilt-loader:") }
                ?.substringAfterLast(':')
            "forge" -> names.firstOrNull { it.startsWith("net.minecraftforge:forge:") }
                ?.substringAfterLast(':')?.substringAfterLast('-')
            "neoforge" -> names.firstOrNull { it.startsWith("net.neoforged:neoforge:") }
                ?.substringAfterLast(':')?.substringAfterLast('-')
            else -> null
        }
    }

    private fun inferMcVersion(
        id: String,
        portable: JsonObject?,
        version: JsonObject,
        loader: LoaderInfo,
    ): String {
        portable?.str("mcVersion")?.takeIf { it.isNotBlank() }?.let { return it }
        loader.mcFromName?.let { return it }
        version.str("id")?.takeIf { MC_VERSION.matches(it) }?.let { return it }

        val parent = version.str("inheritsFrom")
        if (parent != null) {
            if (MC_VERSION.matches(parent)) return parent
            readJson(File(versionsDir, "$parent/instance.json"))
                ?.str("mcVersion")?.takeIf { it.isNotBlank() }?.let { return it }
            readJson(File(versionsDir, "$parent/$parent.json"))
                ?.str("id")?.takeIf { MC_VERSION.matches(it) }?.let { return it }
        }

        val names = libraryNames(version)
        names.firstOrNull { it.startsWith("net.fabricmc:intermediary:") }
            ?.substringAfterLast(':')?.takeIf { MC_VERSION.matches(it) }?.let { return it }
        names.firstOrNull { it.startsWith("net.minecraftforge:forge:") }
            ?.substringAfterLast(':')?.substringBeforeLast('-')
            ?.takeIf { MC_VERSION.matches(it) }?.let { return it }

        return id
    }

    private fun libraryNames(version: JsonObject): List<String> =
        version["libraries"]?.jsonArray?.toList()
            ?.mapNotNull { runCatching { it.jsonObject.str("name") }.getOrNull() }
            ?: emptyList()

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.let { runCatching { it.jsonObject }.getOrNull() }

    private fun JsonObject.str(key: String): String? =
        get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
}
