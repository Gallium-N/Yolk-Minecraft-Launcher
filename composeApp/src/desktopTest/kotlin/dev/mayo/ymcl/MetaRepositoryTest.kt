package dev.mayo.ymcl

import dev.mayo.ymcl.app.BmclapiDownloadSource
import dev.mayo.ymcl.app.MetaCachePolicy
import dev.mayo.ymcl.app.MetaFetchException
import dev.mayo.ymcl.app.MetaFetcher
import dev.mayo.ymcl.app.MetaRepository
import dev.mayo.ymcl.app.MetaStore
import dev.mayo.ymcl.domain.CachedLoaderVersions
import dev.mayo.ymcl.domain.CachedManifest
import dev.mayo.ymcl.domain.VersionManifest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class MetaRepositoryTest {
    private val fixture = """
        {"latest":{"release":"1.20.1","snapshot":"26.1"},"versions":[
          {"id":"1.20.1","type":"release","url":"v1.json","sha1":"abc","releaseTime":"2023-06-12T09:24:00+00:00","complianceLevel":1},
          {"id":"26.1","type":"snapshot","url":"v2.json","sha1":"def","releaseTime":"2026-01-01T00:00:00+00:00"}
        ]}
    """.trimIndent()

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private class FakeStore(var clock: Long) : MetaStore {
        val files = linkedMapOf<String, String>()
        val mtimes = linkedMapOf<String, Long>()
        override fun read(path: String): String? = files[path]
        override fun write(path: String, content: String) {
            files[path] = content
            mtimes[path] = clock
        }
        override fun lastModified(path: String): Long = mtimes[path] ?: 0L
        override fun sha1Hex(content: String): String =
            MessageDigest.getInstance("SHA-1").digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }

    private val failingFetcher = MetaFetcher { throw MetaFetchException("unreachable") }

    private fun cachedWrapper(fetchedAt: Long = 0L): String = json.encodeToString(
        CachedManifest(
            schemaVersion = 1,
            fetchedAt = fetchedAt,
            manifest = json.decodeFromString(VersionManifest.serializer(), fixture),
        ),
    )

    @Test
    fun `manifest fixture parses id type url sha1 and tolerates unknown fields`() {
        val manifest = json.decodeFromString(VersionManifest.serializer(), fixture)
        assertEquals(2, manifest.versions.size)
        assertEquals("1.20.1", manifest.versions[0].id)
        assertEquals("release", manifest.versions[0].type)
        assertEquals("v1.json", manifest.versions[0].url)
        assertEquals("abc", manifest.versions[0].sha1)
    }

    @Test
    fun `ttl boundary is inclusive at exactly six hours`() {
        val ttl = MetaCachePolicy.MANIFEST_TTL_MS
        assertFalse(MetaCachePolicy.isExpired(0L, ttl - 1, ttl))
        assertTrue(MetaCachePolicy.isExpired(0L, ttl, ttl))
        assertTrue(MetaCachePolicy.isExpired(0L, ttl + 1, ttl))
    }

    @Test
    fun `fresh cache avoids network`() {
        var calls = 0
        val store = FakeStore(clock = 1000L)
        val repo = MetaRepository(
            "/root", BmclapiDownloadSource, store,
            MetaFetcher { calls++; fixture },
            { 1000L },
        )
        store.files[repo.manifestPath] = cachedWrapper(1000L)
        store.mtimes[repo.manifestPath] = 1000L
        val result = repo.loadManifest()
        assertFalse(result.offline)
        assertEquals(2, result.value.versions.size)
        assertEquals(0, calls)
    }

    @Test
    fun `expired cache with failing fetch falls back offline`() {
        val store = FakeStore(clock = 0L)
        val now = MetaCachePolicy.MANIFEST_TTL_MS + 1
        val repo = MetaRepository("/root", BmclapiDownloadSource, store, failingFetcher, { now })
        store.files[repo.manifestPath] = cachedWrapper(0L)
        store.mtimes[repo.manifestPath] = 0L
        val result = repo.loadManifest()
        assertTrue(result.offline)
        assertEquals("bmclapi", result.sourceId)
        assertEquals(2, result.value.versions.size)
    }

    @Test
    fun `successful refresh writes schemaVersion wrapper`() {
        val store = FakeStore(clock = 0L)
        val repo = MetaRepository(
            "/root", BmclapiDownloadSource, store,
            MetaFetcher { fixture },
            { 5000L },
        )
        val result = repo.loadManifest()
        assertFalse(result.offline)
        val saved = store.files[repo.manifestPath]!!
        assertEquals(1, json.decodeFromString(CachedManifest.serializer(), saved).schemaVersion)
        assertEquals(5000L, json.decodeFromString(CachedManifest.serializer(), saved).fetchedAt)
    }

    // --- Fabric loader-list cache (24h stale-while-revalidate) -------------

    private fun loaderWrapper(versions: List<String>, fetchedAt: Long = 0L): String =
        json.encodeToString(CachedLoaderVersions(schemaVersion = 1, fetchedAt = fetchedAt, versions = versions))

    private fun loaderRepo(store: FakeStore, fetcher: MetaFetcher, now: Long): MetaRepository =
        MetaRepository("/root", BmclapiDownloadSource, store, fetcher, { now })

    @Test
    fun `fresh loader cache avoids network`() {
        val store = FakeStore(clock = 1000L)
        var calls = 0
        val repo = loaderRepo(
            store,
            MetaFetcher { calls++; throw AssertionError("must not fetch on a fresh hit") },
            now = 1000L,
        )
        val path = repo.fabricLoaderListPath("1.20.1")
        store.files[path] = loaderWrapper(listOf("0.14.21"), fetchedAt = 1000L)
        store.mtimes[path] = 1000L
        assertEquals(listOf("0.14.21"), repo.loadFabricLoaderVersions("1.20.1"))
        assertEquals(0, calls)
    }

    @Test
    fun `stale loader cache returns old list immediately then refreshes in background`() {
        val store = FakeStore(clock = 0L)
        var calls = 0
        val repo = loaderRepo(
            store,
            MetaFetcher { calls++; """[{"loader":{"version":"0.15.0"}}]""" },
            now = MetaCachePolicy.FABRIC_LOADER_LIST_TTL_MS + 1,
        ).also { it.background = { refresh -> refresh() } } // synchronous for determinism
        val path = repo.fabricLoaderListPath("1.20.1")
        store.files[path] = loaderWrapper(listOf("0.14.21"), fetchedAt = 0L)
        store.mtimes[path] = 0L

        // Caller sees the OLD value instantly; the refresh runs out of view.
        assertEquals(listOf("0.14.21"), repo.loadFabricLoaderVersions("1.20.1"))
        assertEquals(1, calls)
        // The refreshed value is what gets persisted for next time.
        assertEquals(listOf("0.15.0"), json.decodeFromString<CachedLoaderVersions>(store.files[path]!!).versions)
    }

    @Test
    fun `failed background loader refresh is swallowed and old list survives`() {
        val store = FakeStore(clock = 0L)
        val repo = loaderRepo(store, failingFetcher, now = MetaCachePolicy.FABRIC_LOADER_LIST_TTL_MS + 1)
            .also { it.background = { refresh -> refresh() } }
        val path = repo.fabricLoaderListPath("1.20.1")
        store.files[path] = loaderWrapper(listOf("0.14.21"), fetchedAt = 0L)
        store.mtimes[path] = 0L

        // Must not throw even though the refresh fetcher always fails.
        assertEquals(listOf("0.14.21"), repo.loadFabricLoaderVersions("1.20.1"))
        // Cache file is left untouched on failure.
        assertEquals(listOf("0.14.21"), json.decodeFromString<CachedLoaderVersions>(store.files[path]!!).versions)
    }

    @Test
    fun `no loader cache fetches once and persists the result`() {
        val store = FakeStore(clock = 1000L)
        var calls = 0
        val repo = loaderRepo(store, MetaFetcher { calls++; """[{"loader":{"version":"0.15.1"}}]""" }, now = 1000L)
        assertEquals(listOf("0.15.1"), repo.loadFabricLoaderVersions("1.20.1"))
        assertEquals(1, calls)
        val path = repo.fabricLoaderListPath("1.20.1")
        assertEquals(1, json.decodeFromString<CachedLoaderVersions>(store.files[path]!!).schemaVersion)
    }
}
