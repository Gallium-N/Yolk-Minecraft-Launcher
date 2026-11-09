package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.InstanceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Minimal zip view so the parser stays pure and offline-testable. */
data class PackEntry(val name: String, val directory: Boolean)

interface ModpackArchive {
    fun entries(): List<PackEntry>
    fun read(name: String): ByteArray
    fun close()
}

/**
 * Pure parser for both pack formats. All JSON is read manually (unknown fields
 * ignored) and every path goes through [safePackPath], so a hostile archive can
 * never escape the instance directory. Zip IO is injected via [ModpackArchive].
 */
object ModpackParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val DRIVE = Regex("^[A-Za-z]:")

    /**
     * Rejects zip-slip: any `..` segment, absolute paths (posix or windows)
     * and Windows drive letters. Backslashes are treated as separators too.
     * Returns the normalized relative path, or null when it must be dropped.
     */
    fun safePackPath(raw: String): String? {
        if (raw.isEmpty()) return null
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith('/')) return null
        if (DRIVE.containsMatchIn(raw)) return null
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." }) return null
        return segments.joinToString("/")
    }

    fun parse(archive: ModpackArchive, existing: Set<String>): ModpackPlan {
        val names = archive.entries().map { it.name }
        return when {
            "modrinth.index.json" in names -> parseMrpack(archive, names, existing)
            "manifest.json" in names -> parseCurseForge(archive, names, existing)
            else -> throw ModpackParseException(
                "不是受支持的整合包：压缩包里既没有 modrinth.index.json（mrpack）也没有 manifest.json（CurseForge）。",
            )
        }
    }

    private fun parseMrpack(archive: ModpackArchive, names: List<String>, existing: Set<String>): ModpackPlan {
        val root = json.parseToJsonElement(archive.text("modrinth.index.json")).jsonObject
        val format = root.long("formatVersion")
        if (format > 1) throw ModpackParseException("不支持的 mrpack formatVersion=$format")
        val name = root.str("name")?.takeIf { it.isNotBlank() } ?: "Modrinth 整合包"
        val packVersion = root.str("versionId").orEmpty()
        val deps = root["dependencies"]?.obj()
            ?: throw ModpackParseException("modrinth.index.json 缺少 dependencies")
        val mcVersion = deps.str("minecraft")?.takeIf { it.isNotBlank() }
            ?: throw ModpackParseException("modrinth.index.json 的 dependencies 缺少 minecraft 版本")
        val (loaderKind, loaderVersion) = loaderFromDependencies(deps)

        val files = mutableListOf<PackFile>()
        var skipped = 0
        var dropped = 0
        val warnings = mutableListOf<String>()
        (root["files"]?.arr() ?: JsonArray(emptyList())).forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val env = o["env"]?.obj()
            if (env?.str("client").equals("unsupported", ignoreCase = true)) {
                skipped++
                return@forEach
            }
            val raw = o.str("path") ?: return@forEach
            val safe = safePackPath(raw)
            if (safe == null) {
                dropped++
                warnings += "zip-slip 已丢弃 files[].path：$raw"
                return@forEach
            }
            val hashes = o["hashes"]?.obj()
            val downloads = o["downloads"]?.arr()?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: emptyList()
            files += PackFile(safe, o.long("fileSize"), hashes?.str("sha1"), downloads)
        }

        val ov = collectOverrides(archive, names, listOf("overrides", "client-overrides"))
        return ModpackPlan(
            source = PackSource.MRPACK,
            name = name,
            packVersion = packVersion,
            mcVersion = mcVersion,
            loaderKind = loaderKind,
            loaderVersion = loaderVersion,
            files = files,
            overrides = ov.overrides,
            skippedServerOnly = skipped,
            droppedUnsafe = dropped + ov.dropped,
            warnings = warnings + ov.warnings,
            instanceConfigJson = ov.instanceConfigJson,
            suggestedId = InstanceId.allocate(name, mcVersion, existing),
        )
    }

    private fun parseCurseForge(archive: ModpackArchive, names: List<String>, existing: Set<String>): ModpackPlan {
        val m = json.parseToJsonElement(archive.text("manifest.json")).jsonObject
        val name = m.str("name")?.takeIf { it.isNotBlank() } ?: "CurseForge 整合包"
        val packVersion = m.str("version").orEmpty()
        val minecraft = m["minecraft"]?.obj()
            ?: throw ModpackParseException("manifest.json 缺少 minecraft")
        val mcVersion = minecraft.str("version")?.takeIf { it.isNotBlank() }
            ?: throw ModpackParseException("manifest.json 缺少 minecraft.version")
        val loaders = minecraft["modLoaders"]?.arr() ?: JsonArray(emptyList())
        val primary = loaders.firstOrNull { (it as? JsonObject)?.bool("primary") == true }
            ?: loaders.firstOrNull()
        val (loaderKind, loaderVersion) = loaderFromCfId((primary as? JsonObject)?.str("id"))

        // CurseForge manifest has NO filename and NO url. Never guess one: the
        // 3-level resolver fills in downloads later from the API / proxy.
        val files = (m["files"]?.arr() ?: JsonArray(emptyList())).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val pid = o.long("projectID").takeIf { it > 0 }?.toString() ?: return@mapNotNull null
            val fid = o.long("fileID").takeIf { it > 0 }?.toString() ?: return@mapNotNull null
            PackFile("", 0L, null, emptyList(), pid, fid)
        }
        val ov = collectOverrides(archive, names, listOf("overrides"))
        return ModpackPlan(
            source = PackSource.CURSEFORGE,
            name = name,
            packVersion = packVersion,
            mcVersion = mcVersion,
            loaderKind = loaderKind,
            loaderVersion = loaderVersion,
            files = files,
            overrides = ov.overrides,
            skippedServerOnly = 0,
            droppedUnsafe = ov.dropped,
            warnings = ov.warnings,
            instanceConfigJson = ov.instanceConfigJson,
            suggestedId = InstanceId.allocate(name, mcVersion, existing),
        )
    }

    private data class Overrides(
        val overrides: List<PackOverride>,
        val dropped: Int,
        val warnings: List<String>,
        val instanceConfigJson: String?,
    )

    /**
     * Later prefixes override earlier ones for the same relative path, so
     * `client-overrides` (last) has the highest priority. `server-overrides`
     * is not requested by the client launcher and is skipped entirely.
     * `client-overrides/.launcher/instance.json` is our own merge convention.
     */
    private fun collectOverrides(archive: ModpackArchive, names: List<String>, prefixes: List<String>): Overrides {
        val map = LinkedHashMap<String, PackOverride>()
        var dropped = 0
        val warnings = mutableListOf<String>()
        var cfg: String? = null
        for (prefix in prefixes) {
            for (name in names) {
                if (!name.startsWith("$prefix/") || name.endsWith("/")) continue
                val rel = name.removePrefix("$prefix/")
                if (prefix == "client-overrides" && rel == ".launcher/instance.json") {
                    cfg = String(archive.read(name), Charsets.UTF_8)
                    continue
                }
                val safe = safePackPath(rel)
                if (safe == null) {
                    dropped++
                    warnings += "zip-slip 已丢弃 override：$name"
                    continue
                }
                map[safe] = PackOverride(safe, archive.read(name))
            }
        }
        return Overrides(map.values.toList(), dropped, warnings, cfg)
    }

    private fun loaderFromDependencies(deps: JsonObject): Pair<String, String?> {
        for ((key, kind) in listOf(
            "fabric-loader" to "fabric", "quilt-loader" to "quilt",
            "forge" to "forge", "neoforge" to "neoforge",
        )) {
            val v = deps.str(key)
            if (!v.isNullOrBlank()) return kind to v
        }
        return "vanilla" to null
    }

    private fun loaderFromCfId(id: String?): Pair<String, String?> {
        if (id.isNullOrBlank()) return "vanilla" to null
        val lower = id.lowercase()
        val kind = when {
            lower.startsWith("fabric") -> "fabric"
            lower.startsWith("quilt") -> "quilt"
            lower.startsWith("neoforge") -> "neoforge"
            lower.startsWith("forge") -> "forge"
            else -> "vanilla"
        }
        return kind to id.substringAfter('-', "").takeIf { it.isNotBlank() }
    }

    private fun ModpackArchive.text(name: String): String = String(read(name), Charsets.UTF_8)
}

private fun JsonObject.str(key: String): String? =
    get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

private fun JsonObject.long(key: String): Long =
    get(key)?.let { runCatching { it.jsonPrimitive.content.toLongOrNull() }.getOrNull() } ?: 0L

private fun JsonObject.bool(key: String): Boolean =
    get(key)?.let { runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull() } ?: false

private fun JsonElement.obj(): JsonObject? = runCatching { jsonObject }.getOrNull()
private fun JsonElement.arr(): JsonArray? = runCatching { jsonArray }.getOrNull()
