package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.CachedLoaderVersions
import dev.mayo.ymcl.domain.CachedManifest
import dev.mayo.ymcl.domain.CachedProbe
import dev.mayo.ymcl.domain.FabricProfile
import dev.mayo.ymcl.domain.VersionEntry
import dev.mayo.ymcl.domain.VersionManifest
import dev.mayo.ymcl.platform.PathUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** File IO for the meta cache. Implemented in platform actuals; no java.io here. */
interface MetaStore {
    fun read(path: String): String?
    fun write(path: String, content: String)
    fun lastModified(path: String): Long
    fun sha1Hex(content: String): String
}

/** Tries candidate URLs in order; 302 is followed, 404/5xx/timeout fall through. */
fun interface MetaFetcher {
    fun fetch(candidates: List<String>): String
}

/**
 * Meta layer only: manifest / version json / Fabric profile caching with TTL and
 * offline fallback. This is deliberately not a game-file downloader (step 3).
 */
class MetaRepository(
    private val appRoot: String,
    private val source: DownloadSource,
    private val store: MetaStore,
    private val fetcher: MetaFetcher,
    private val now: () -> Long,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Schedules a background refresh. Tests inject `{ it() }` for determinism. */
    var background: (() -> Unit) -> Unit = DEFAULT_BACKGROUND

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val DEFAULT_BACKGROUND: (() -> Unit) -> Unit = { scope.launch { it() } }
    }

    val manifestPath: String get() = PathUtil.join(appRoot, "data", "meta", "version_manifest_v2.json")

    /** Raw GET through the injected fetcher (loader meta APIs such as Quilt). */
    fun fetch(candidates: List<String>): String = fetcher.fetch(candidates)

    fun versionPath(id: String): String =
        PathUtil.join(appRoot, "data", "meta", "versions", "$id.json")

    fun fabricPath(mcVersion: String, loaderVersion: String): String =
        PathUtil.join(appRoot, "data", "meta", "fabric", "$mcVersion-$loaderVersion.json")

    /** Cache file for the enumerated Fabric loader versions of one MC version. */
    fun fabricLoaderListPath(mcVersion: String): String =
        PathUtil.join(appRoot, "data", "meta", "fabric", "$mcVersion-loaders.json")

    /** Reachability-probe cache file, one per loader (TTL 5 min). */
    fun probePath(loader: String): String =
        PathUtil.join(appRoot, "data", "meta", "probe", "$loader.json")

    fun loadManifest(): MetaResult<VersionManifest> {
        val raw = store.read(manifestPath)
        val cached = raw?.let {
            runCatching { json.decodeFromString(CachedManifest.serializer(), it) }.getOrNull()
        }
        cached?.let {
            check(it.schemaVersion <= 1) { "Unsupported meta schemaVersion ${it.schemaVersion}" }
        }
        val cachedAt = if (raw != null) store.lastModified(manifestPath) else 0L
        val expired = cached == null ||
            MetaCachePolicy.isExpired(cachedAt, now(), MetaCachePolicy.MANIFEST_TTL_MS)

        if (!expired) {
            return MetaResult(cached!!.manifest, offline = false, sourceId = source.id, fetchedAt = cachedAt)
        }

        val refreshed = runCatching { fetcher.fetch(source.versionManifest()) }.getOrNull()
        if (refreshed != null) {
            val manifest = try {
                json.decodeFromString(VersionManifest.serializer(), refreshed)
            } catch (e: Exception) {
                throw MetaUnavailableException("manifest 解析失败：${e.message}")
            }
            val fetchedAt = now()
            store.write(
                manifestPath,
                json.encodeToString(
                    CachedManifest.serializer(),
                    CachedManifest(schemaVersion = 1, fetchedAt = fetchedAt, manifest = manifest),
                ) + "\n",
            )
            return MetaResult(manifest, offline = false, sourceId = source.id, fetchedAt = fetchedAt)
        }

        if (cached != null) {
            return MetaResult(cached.manifest, offline = true, sourceId = source.id, fetchedAt = cachedAt)
        }
        throw MetaUnavailableException("无可用 manifest 缓存且刷新失败")
    }

    fun loadVersionJson(entry: VersionEntry): MetaResult<String> {
        val path = versionPath(entry.id)
        val cached = store.read(path)
        val cachedAt = if (cached != null) store.lastModified(path) else 0L
        fun valid(text: String): Boolean =
            entry.sha1.isBlank() || store.sha1Hex(text).equals(entry.sha1, ignoreCase = true)

        if (cached != null && valid(cached)) {
            return MetaResult(cached, offline = false, sourceId = source.id, fetchedAt = cachedAt)
        }

        val fetched = runCatching { fetcher.fetch(source.versionJson(entry.id, entry.url)) }.getOrNull()
        if (fetched != null) {
            if (!valid(fetched)) {
                throw MetaUnavailableException("版本 json sha1 不匹配：${entry.id}")
            }
            val fetchedAt = now()
            store.write(path, fetched)
            return MetaResult(fetched, offline = false, sourceId = source.id, fetchedAt = fetchedAt)
        }

        if (cached != null) {
            return MetaResult(cached, offline = true, sourceId = source.id, fetchedAt = cachedAt)
        }
        throw MetaUnavailableException("无可用版本 json 缓存且刷新失败：${entry.id}")
    }

    /**
     * Fabric loader enumeration with a 24h stale-while-revalidate cache at
     * data/meta/fabric/<mc>-loaders.json:
     *  - fresh cache  -> return immediately, zero network ("loaders cache hit <mc>")
     *  - stale cache  -> return the old value immediately, refresh in background;
     *                    a failed refresh is logged only, never surfaced
     *  - no cache     -> fetch, persist, return
     * The raw parse is shared by the network path so both paths agree.
     */
    fun loadFabricLoaderVersions(mcVersion: String): List<String> {
        val path = fabricLoaderListPath(mcVersion)
        val raw = store.read(path)
        val cached = raw?.let { runCatching { json.decodeFromString<CachedLoaderVersions>(it) }.getOrNull() }
        val cachedAt = if (raw != null) store.lastModified(path) else 0L
        if (cached != null && !MetaCachePolicy.isExpired(
                cachedAt, now(), MetaCachePolicy.FABRIC_LOADER_LIST_TTL_MS,
            )
        ) {
            println("loaders cache hit $mcVersion")
            return cached.versions
        }

        if (cached != null) {
            // Stale: hand back the old list NOW and refresh off the UI path.
            background {
                runCatching { fetchAndStoreLoaderVersions(mcVersion, path) }
                    .onFailure { println("loaders background refresh failed $mcVersion: ${it.message}") }
            }
            return cached.versions
        }

        return fetchAndStoreLoaderVersions(mcVersion, path)
    }

    private fun fetchAndStoreLoaderVersions(mcVersion: String, path: String): List<String> {
        val text = fetcher.fetch(source.fabricLoaderMetadata(mcVersion))
        val versions = parseFabricLoaderVersions(text)
        val wrapper = CachedLoaderVersions(schemaVersion = 1, fetchedAt = now(), versions = versions)
        store.write(path, json.encodeToString(wrapper))
        return versions
    }

    private fun parseFabricLoaderVersions(text: String): List<String> {
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
                val loader = element.jsonObject["loader"]?.jsonObject ?: return@mapNotNull null
                loader["version"]?.jsonPrimitive?.content
            }
        } else {
            Regex("<version>([^<]+)</version>").findAll(text).map { it.groupValues[1] }.toList()
        }
    }

    /**
     * Load a fresh (<=5 min) reachability probe result, or null when absent/expired.
     * [clock] is supplied by the caller (real wall time in production, a fixed
     * value in tests) so the 5-minute TTL is independent of the meta-repository's
     * injected meta clock. The caller then re-probes via
     * [MavenProbe.probeCandidates] and calls [saveProbe].
     */
    fun loadProbe(loader: String, clock: () -> Long): MavenProbe.Result? {
        val raw = store.read(probePath(loader)) ?: return null
        val cached = runCatching { json.decodeFromString<CachedProbe>(raw) }.getOrNull()
            ?: return null
        if (cached.schemaVersion > 1) return null
        if (MetaCachePolicy.isExpired(cached.fetchedAt, clock(), MetaCachePolicy.PROBE_TTL_MS)) {
            return null
        }
        return MavenProbe.Result(
            reachable = cached.reachable,
            via = cached.via,
            tried = cached.tried,
            note = cached.note,
        )
    }

    /** Persist a probe result with schemaVersion for the 5-minute TTL window. */
    fun saveProbe(loader: String, result: MavenProbe.Result, clock: () -> Long) {
        store.write(
            probePath(loader),
            json.encodeToString(
                CachedProbe(
                    schemaVersion = 1,
                    fetchedAt = clock(),
                    reachable = result.reachable,
                    via = result.via,
                    tried = result.tried,
                    note = result.note,
                ),
            ) + "\n",
        )
    }

    /** Raw cached profile json, used by the loader installer to keep every field. */
    fun fabricProfileText(mcVersion: String, loaderVersion: String): String =
        store.read(fabricPath(mcVersion, loaderVersion))
            ?: throw MetaUnavailableException("Fabric profile 缓存不存在：$mcVersion/$loaderVersion")

    /** Caches the finished profile json verbatim at data/meta/fabric/<mc>-<loader>.json (TTL 24h). */
    fun loadFabricProfile(mcVersion: String, loaderVersion: String): MetaResult<FabricProfile> {
        val path = fabricPath(mcVersion, loaderVersion)
        val raw = store.read(path)
        val cached = raw?.let {
            runCatching { json.decodeFromString(FabricProfile.serializer(), it) }.getOrNull()
        }
        val cachedAt = if (raw != null) store.lastModified(path) else 0L
        val expired = cached == null ||
            MetaCachePolicy.isExpired(cachedAt, now(), MetaCachePolicy.FABRIC_PROFILE_TTL_MS)

        if (!expired) {
            return MetaResult(cached!!, offline = false, sourceId = source.id, fetchedAt = cachedAt)
        }

        val fetchResult = runCatching { fetcher.fetch(source.fabricProfile(mcVersion, loaderVersion)) }
        val fetched = fetchResult.getOrNull()
        if (fetched != null) {
            val profile = try {
                json.decodeFromString(FabricProfile.serializer(), fetched)
            } catch (e: Exception) {
                throw MetaUnavailableException("Fabric profile 解析失败：${e.message}")
            }
            val fetchedAt = now()
            store.write(path, fetched)
            return MetaResult(profile, offline = false, sourceId = source.id, fetchedAt = fetchedAt)
        }

        if (cached != null) {
            return MetaResult(cached, offline = true, sourceId = source.id, fetchedAt = cachedAt)
        }
        throw MetaUnavailableException(
            "无可用 Fabric profile 缓存且刷新失败：$mcVersion/$loaderVersion；" +
                (fetchResult.exceptionOrNull()?.message ?: "unknown"),
        )
    }

    /**
     * Old MC versions may not work with the newest loader. Try the newest, then
     * walk back at most [maxFallback] versions; every step is logged.
     */
    fun loadFabricProfileWithFallback(
        mcVersion: String,
        maxFallback: Int = 3,
    ): FabricProfileSelection {
        val loaders = loadFabricLoaderVersions(mcVersion)
        if (loaders.isEmpty()) throw MetaUnavailableException("Fabric loader 列表为空：$mcVersion")
        val candidates = loaders.takeLast(maxFallback + 1).reversed()
        val attempts = mutableListOf<String>()
        for ((index, loader) in candidates.withIndex()) {
            val result = runCatching { loadFabricProfile(mcVersion, loader) }
            if (result.isSuccess) {
                return FabricProfileSelection(loader, result.getOrThrow().value, attempts)
            }
            val reason = result.exceptionOrNull()?.message ?: "unknown"
            attempts += "$loader: $reason"
            val next = candidates.getOrNull(index + 1)
            if (next != null) {
                println("YMCL fabric: $mcVersion/$loader 的 profile 不可用，回退到 $next（$reason）")
            } else {
                println("YMCL fabric: $mcVersion/$loader 的 profile 不可用，无更多可回退版本（$reason）")
            }
        }
        throw MetaUnavailableException(
            "Fabric profile 全部候选失败：$mcVersion；" + attempts.joinToString("; "),
        )
    }
}

data class FabricProfileSelection(
    val loaderVersion: String,
    val profile: FabricProfile,
    val attempts: List<String>,
)
