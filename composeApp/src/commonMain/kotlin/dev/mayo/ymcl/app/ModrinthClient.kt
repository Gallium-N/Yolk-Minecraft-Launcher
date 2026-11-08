package dev.mayo.ymcl.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val USER_AGENT = "YMCL/0.1 (local)"

/**
 * Modrinth v2 API. Uses ONLY the injected [MetaFetcher] (JSON strings) and
 * [DownloadTransport] (file bytes) — no new HTTP stack, no java.* here. Every
 * URL comes from [DownloadSource], so no host literals live in this file.
 * Unknown JSON fields are ignored (manual JsonObject reads, never strict DTOs).
 */
class ModrinthClient(
    private val source: DownloadSource,
    private val fetcher: MetaFetcher,
    private val transport: DownloadTransport,
    private val log: (String) -> Unit = ::println,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun getJson(candidates: List<String>): String {
        candidates.firstOrNull()?.let {
            log("MR GET $it")
            log("MR headers: User-Agent: $USER_AGENT")
        }
        return fetcher.fetch(candidates)
    }

    private fun parse(text: String): JsonElement = json.parseToJsonElement(text)

    fun search(query: String, mcVersion: String, loader: String, popular: Boolean = false): List<ModrinthHit> {
        val facets = ModResolver.facets(mcVersion, loader)
        val raw = getJson(source.modrinthSearch(query, facets, popular = popular))
        val hits = parse(raw).jsonObject["hits"]?.jsonArray?.toList() ?: emptyList()
        return hits.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            ModrinthHit(
                projectId = o.str("project_id") ?: return@mapNotNull null,
                slug = o.str("slug") ?: "",
                title = o.str("title") ?: "",
                description = o.str("description") ?: "",
                author = o.str("author") ?: "",
                downloads = o.long("downloads"),
                iconUrl = o.str("icon_url"),
                latestVersionId = o.str("latest_version"),
            )
        }
    }

    fun project(idOrSlug: String): ModrinthProject {
        val o = parse(getJson(source.modrinthProject(idOrSlug))).jsonObject
        return ModrinthProject(
            id = o.str("id") ?: idOrSlug,
            slug = o.str("slug") ?: idOrSlug,
            title = o.str("title") ?: "",
            description = o.str("description") ?: "",
            projectType = o.str("project_type") ?: "mod",
        )
    }

    /** Strictly filtered by the instance's mc + loader. */
    fun versions(idOrSlug: String, mcVersion: String, loader: String): List<ModrinthVersion> {
        val arr = parse(getJson(source.modrinthVersions(idOrSlug, mcVersion, loader))).jsonArray.toList()
        return arr.mapNotNull { parseVersion(it) }
            .filter { ModResolver.compatible(it, mcVersion, loader) }
    }

    fun version(versionId: String): ModrinthVersion {
        val o = parse(getJson(source.modrinthVersion(versionId))).jsonObject
        return parseVersion(o) ?: throw MetaFetchException("无法解析版本 $versionId")
    }

    /** CDN bytes; caller verifies sha1. */
    fun file(url: String): ByteArray {
        val candidates = source.modrinthFile(url)
        candidates.firstOrNull()?.let { log("MR FILE GET $it") }
        val payload = transport.get(candidates.first(), 0)
        if (payload.statusCode !in 200..299) {
            throw MetaFetchException("模组文件下载失败：HTTP ${payload.statusCode} $url")
        }
        return payload.bytes
    }

    private fun parseVersion(el: JsonElement): ModrinthVersion? {
        val o = runCatching { el.jsonObject }.getOrNull() ?: return null
        val id = o.str("id") ?: return null
        val files = o["files"]?.jsonArray?.toList()?.mapNotNull { f ->
            val fo = runCatching { f.jsonObject }.getOrNull() ?: return@mapNotNull null
            val url = fo.str("url") ?: return@mapNotNull null
            val hashes = fo["hashes"]?.jsonObjectOrNull()
            ModrinthFileRef(
                url = url,
                filename = fo.str("filename") ?: "",
                primary = fo.bool("primary"),
                sha1 = hashes?.str("sha1"),
                sha512 = hashes?.str("sha512"),
                size = fo.long("size"),
            )
        } ?: emptyList()
        val deps = o["dependencies"]?.jsonArray?.toList()?.mapNotNull { d ->
            val dobj = runCatching { d.jsonObject }.getOrNull() ?: return@mapNotNull null
            ModrinthDependency(
                versionId = dobj.str("version_id"),
                projectId = dobj.str("project_id"),
                fileName = dobj.str("file_name"),
                dependencyType = dobj.str("dependency_type") ?: "required",
            )
        } ?: emptyList()
        return ModrinthVersion(
            id = id,
            projectId = o.str("project_id") ?: "",
            versionNumber = o.str("version_number") ?: "",
            versionType = o.str("version_type") ?: "release",
            name = o.str("name") ?: "",
            gameVersions = o.strings("game_versions"),
            loaders = o.strings("loaders"),
            files = files,
            dependencies = deps,
            datePublished = o.str("date_published") ?: "",
        )
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun JsonObject.long(key: String): Long =
        get(key)?.let { runCatching { it.jsonPrimitive.content.toLongOrNull() }.getOrNull() } ?: 0L

    private fun JsonObject.bool(key: String): Boolean =
        get(key)?.let { runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull() } ?: false

    private fun JsonObject.strings(key: String): List<String> =
        (get(key) as? JsonArray)?.toList()?.mapNotNull { e ->
            runCatching { e.jsonPrimitive.content }.getOrNull()
        } ?: emptyList()

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
}
