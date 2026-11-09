package dev.mayo.ymcl.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Raw GET for CF metadata; [apiKey] is sent as x-api-key for the official API. */
fun interface CfFetcher {
    fun get(url: String, apiKey: String?): String
}

data class CfResolution(
    val filename: String,
    val downloadUrl: String,
    val sha1: String?,
    val via: String,
)

/**
 * Three-level strategy for a CurseForge file (the manifest only has projectID
 * and fileID, no filename, so we must resolve the real download URL):
 *
 *  1. configured API key -> official CurseForge API;
 *  2. no key + proxy enabled -> public read-only proxy `api.curse.tools`
 *     (logged as `via=curse.tools`);
 *  3. neither works -> null, so the caller can list the missing files and
 *     show a readable error instead of guessing a filename.
 *
 * Pure: network is injected as [fetch], so all three levels are testable offline.
 */
class CurseForgeFileResolver(
    private val source: DownloadSource,
    private val fetch: CfFetcher,
    private val apiKey: String?,
    private val proxyEnabled: Boolean,
    private val log: (String) -> Unit = ::println,
) {
    enum class Level { API_KEY, PROXY, MISSING }

    private val json = Json { ignoreUnknownKeys = true }

    fun level(): Level = when {
        !apiKey.isNullOrBlank() -> Level.API_KEY
        proxyEnabled -> Level.PROXY
        else -> Level.MISSING
    }

    fun resolve(projectId: String, fileId: String): CfResolution? {
        if (!apiKey.isNullOrBlank()) {
            val url = source.cfOfficialFile(projectId, fileId).first()
            log("CF file $projectId/$fileId level=1 via=api url=$url")
            tryParse(url, apiKey, fileId)?.let {
                log("CF file $projectId/$fileId resolved via=api fileName=${it.filename}")
                return it.copy(via = "api")
            }
            log("CF file $projectId/$fileId level=1 不可用，回退到代理")
        }
        if (proxyEnabled) {
            val url = source.cfProxyFile(projectId, fileId).first()
            log("CF file $projectId/$fileId level=2 via=curse.tools url=$url")
            tryParse(url, null, fileId)?.let {
                log("CF file $projectId/$fileId resolved via=curse.tools fileName=${it.filename}")
                return it.copy(via = "curse.tools")
            }
            log("CF file $projectId/$fileId level=2 不可用")
        }
        return null
    }

    private fun tryParse(url: String, key: String?, fileId: String): CfResolution? {
        val text = try {
            fetch.get(url, key)
        } catch (e: Exception) {
            log("CF fetch failed $url -> ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        val o = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return null
        }
        val data = o["data"]?.obj() ?: o
        val filenameRaw = data.str("fileName") ?: return null
        // Strip any path separators the server sent; never trust a raw filename.
        val filename = ModResolver.safeFilename(filenameRaw) ?: return null
        val direct = data.str("downloadUrl")?.takeIf { it.isNotBlank() }
        // When the API omits downloadUrl we build the CDN url from the REAL
        // filename it returned — never from a guessed one.
        val downloadUrl = direct ?: source.cfCdnFile(fileId, filename).first()
        return CfResolution(filename, downloadUrl, sha1Of(data["hashes"]), "")
    }

    private fun sha1Of(el: JsonElement?): String? {
        if (el == null) return null
        el.obj()?.let { return it.str("sha1") }
        el.arr()?.forEach { h ->
            val o = h as? JsonObject ?: return@forEach
            // CurseForge hash algo: 1 = SHA1, 2 = MD5.
            if (o.long("algo") == 1L) {
                val value = o.str("value")
                if (!value.isNullOrBlank()) return value
            }
        }
        return null
    }

    companion object {
        fun missingMessage(count: Int): String =
            "$count 个文件无法解析下载地址，需要 CurseForge API key 或允许代理。" +
                " YMCL 不会猜测文件名去拼接下载地址。"
    }
}

private fun JsonObject.str(key: String): String? =
    get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

private fun JsonObject.long(key: String): Long =
    get(key)?.let { runCatching { it.jsonPrimitive.content.toLongOrNull() }.getOrNull() } ?: 0L

private fun JsonElement.obj(): JsonObject? = runCatching { jsonObject }.getOrNull()
private fun JsonElement.arr(): JsonArray? = runCatching { jsonArray }.getOrNull()
