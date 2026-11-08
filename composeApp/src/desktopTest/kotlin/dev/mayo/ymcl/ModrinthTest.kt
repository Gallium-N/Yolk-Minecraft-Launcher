package dev.mayo.ymcl

import dev.mayo.ymcl.app.DownloadFileSystem
import dev.mayo.ymcl.app.DownloadPayload
import dev.mayo.ymcl.app.DownloadTransport
import dev.mayo.ymcl.app.InstanceSummary
import dev.mayo.ymcl.app.MetaFetcher
import dev.mayo.ymcl.app.ModInstallException
import dev.mayo.ymcl.app.ModInstaller
import dev.mayo.ymcl.app.ModResolver
import dev.mayo.ymcl.app.ModrinthClient
import dev.mayo.ymcl.app.ModrinthDependency
import dev.mayo.ymcl.app.ModrinthFileRef
import dev.mayo.ymcl.app.ModrinthRateLimit
import dev.mayo.ymcl.app.ModrinthService
import dev.mayo.ymcl.app.ModrinthVersion
import dev.mayo.ymcl.app.OfficialDownloadSource
import dev.mayo.ymcl.platform.ModrinthMetaFetcher
import dev.mayo.ymcl.platform.ModrinthRawResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/** Offline tests for Modrinth: facets, versions, dependency graph, filenames, install, 429. */
class ModrinthTest {
    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    private class FakeFs : DownloadFileSystem {
        val files = linkedMapOf<String, ByteArray>()
        override fun exists(p: String): Boolean = files.containsKey(p)
        override fun size(p: String): Long = files[p]?.size?.toLong() ?: 0L
        override fun sha1Of(p: String): String = digest(files[p] ?: byteArrayOf())
        override fun readAll(p: String): ByteArray = files[p] ?: byteArrayOf()
        override fun writeNew(p: String, data: ByteArray) { files[p] = data }
        override fun append(p: String, data: ByteArray) { files[p] = (files[p] ?: byteArrayOf()) + data }
        override fun delete(p: String) { files.remove(p) }
        override fun move(from: String, to: String) { files[to] = files.remove(from)!! }
        override fun unpackNatives(jarBytes: ByteArray, destDir: String) {}
        private fun digest(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes)
                .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }

    private fun file(
        filename: String = "mod.jar",
        sha1: String? = "abc",
        url: String = "http://fake/mod.jar",
    ) = ModrinthFileRef(url, filename, true, sha1, null, 1L)

    private fun version(
        projectId: String,
        id: String,
        type: String = "release",
        date: String = "2024-01-01",
        deps: List<ModrinthDependency> = emptyList(),
        files: List<ModrinthFileRef> = listOf(file()),
        gameVersions: List<String> = listOf("1.20.1"),
        loaders: List<String> = listOf("fabric"),
    ) = ModrinthVersion(
        id = id, projectId = projectId, versionNumber = "$projectId-$id",
        versionType = type, name = id, gameVersions = gameVersions, loaders = loaders,
        files = files, dependencies = deps, datePublished = date,
    )

    private fun required(versionId: String) =
        ModrinthDependency(versionId, null, null, "required")

    private fun optional(versionId: String) =
        ModrinthDependency(versionId, null, null, "optional")

    // 1. facets contain project_type + versions + categories, as a 2-D JSON array.
    @Test
    fun `facets include project type mc and loader`() {
        val f = ModResolver.facets("1.20.1", "fabric")
        assertTrue(f.contains("project_type:mod"))
        assertTrue(f.contains("versions:1.20.1"))
        assertTrue(f.contains("categories:fabric"))
        assertEquals(3, Json.parseToJsonElement(f).jsonArray.size)
        // Every source produces a single Modrinth URL with the facets encoded.
        val url = OfficialDownloadSource.modrinthSearch("sodium", f).single()
        assertTrue(url.contains("facets="))
        assertTrue(url.contains("query=sodium"))
    }

    // 2. release preferred over newer beta; alpha never the default but visible.
    @Test
    fun `release wins over beta and alpha is not the default`() {
        val release = version("P", "r", type = "release", date = "2024-01-01")
        val beta = version("P", "b", type = "beta", date = "2025-01-01")
        val alpha = version("P", "a", type = "alpha", date = "2026-01-01")
        assertEquals(release, ModResolver.defaultVersion(listOf(alpha, beta, release)))
        assertEquals(beta, ModResolver.defaultVersion(listOf(alpha, beta)))
        assertNull(ModResolver.defaultVersion(listOf(alpha)))
        assertEquals(listOf("r", "b", "a"), ModResolver.visibleVersions(listOf(alpha, beta, release)).map { it.id })
    }

    // 2b. strict double filter.
    @Test
    fun `version must declare both mc and loader`() {
        val ok = version("P", "ok", gameVersions = listOf("1.20.1"), loaders = listOf("fabric"))
        val wrongMc = version("P", "m", gameVersions = listOf("1.21.1"), loaders = listOf("fabric"))
        val wrongLoader = version("P", "l", gameVersions = listOf("1.20.1"), loaders = listOf("forge"))
        assertTrue(ModResolver.compatible(ok, "1.20.1", "fabric"))
        assertFalse(ModResolver.compatible(wrongMc, "1.20.1", "fabric"))
        assertFalse(ModResolver.compatible(wrongLoader, "1.20.1", "fabric"))
    }

    // 3. A -> B(required) -> C(required), three levels.
    @Test
    fun `three level required dependency chain resolves`() {
        val c = version("C", "c1")
        val b = version("B", "b1", deps = listOf(required("c1")))
        val a = version("A", "a1", deps = listOf(required("b1")))
        val lookup = mapOf("b1" to b, "c1" to c)
        val r = ModResolver.resolve(a, emptySet(), { lookup[it] })
        assertEquals(listOf("A", "B", "C"), r.install.map { it.version.projectId })
        assertEquals(2, r.install.last().depth)
        assertTrue(r.errors.isEmpty())
        assertNull(r.blocked)
    }

    // 3b. optional is listed but not auto-installed; ticking installs it.
    @Test
    fun `optional dependency is listed default off`() {
        val o = version("O", "o1")
        val a = version("A", "a1", deps = listOf(optional("o1")))
        val lookup = mapOf("o1" to o)
        val off = ModResolver.resolve(a, emptySet(), { lookup[it] })
        assertEquals(1, off.optional.size)
        assertEquals(listOf("A"), off.install.map { it.version.projectId })
        val on = ModResolver.resolve(a, emptySet(), { lookup[it] }, setOf("o1"))
        assertEquals(listOf("A", "O"), on.install.map { it.version.projectId })
    }

    // 4. A -> B -> A cycle terminates with a readable error.
    @Test
    fun `dependency cycle is detected without hanging`() {
        val a = version("A", "a1", deps = listOf(required("b1")))
        val b = version("B", "b1", deps = listOf(required("a1")))
        val lookup = mapOf("a1" to a, "b1" to b)
        try {
            ModResolver.resolve(a, emptySet(), { lookup[it] })
            fail("expected CycleException")
        } catch (e: ModResolver.CycleException) {
            assertTrue(e.message!!.contains("循环依赖"))
        }
    }

    // 4b. incompatible already installed blocks the install.
    @Test
    fun `installed incompatible dependency blocks install`() {
        val a = version("A", "a1", deps = listOf(
            ModrinthDependency(null, "B", null, "incompatible"),
        ))
        val r = ModResolver.resolve(a, setOf("B"), { null })
        assertNotNull(r.blocked)
        assertTrue(r.blocked!!.contains("不兼容"))
    }

    // 5. chain deeper than MAX_DEPTH is truncated and recorded.
    @Test
    fun `dependency depth beyond five is truncated and recorded`() {
        val chain = (0..8).map { i ->
            val dep = if (i < 8) listOf(required("v${i + 1}")) else emptyList()
            version("P$i", "v$i", deps = dep)
        }
        val lookup = chain.associateBy { it.id }
        val r = ModResolver.resolve(chain[0], emptySet(), { lookup[it] })
        assertEquals(ModResolver.MAX_DEPTH + 1, r.install.size) // depths 0..5
        assertTrue(r.errors.any { it.contains("深度超过 ${ModResolver.MAX_DEPTH}") })
    }

    // 6. filename traversal is rejected; separators stripped; >100 truncated.
    @Test
    fun `unsafe filenames are rejected and long names truncated`() {
        assertNull(ModResolver.safeFilename("../../etc/passwd"))
        assertNull(ModResolver.safeFilename("..\\..\\windows\\system32\\evil.jar"))
        assertNull(ModResolver.safeFilename("a/../b.jar"))
        assertEquals("bar.jar", ModResolver.safeFilename("foo/bar.jar"))
        assertEquals("bar.jar", ModResolver.safeFilename("foo\\bar.jar"))
        val long = "x".repeat(120) + ".jar"
        val safe = ModResolver.safeFilename(long)!!
        assertTrue(safe.length <= ModResolver.MAX_FILENAME)
        assertTrue(safe.endsWith(".jar"))
    }

    // 7. sha1 mismatch -> exactly one redownload -> still bad -> failure list.
    @Test
    fun `sha1 mismatch redownloads once then enters failure list`() {
        val good = "good".toByteArray()
        val bad = "bad".toByteArray()
        var calls = 0
        val fs = FakeFs()
        val installer = ModInstaller(fs, { calls++; bad }) { }
        val v = version("A", "a1", files = listOf(file(filename = "a.jar", sha1 = sha1(good))))
        val planned = listOf(ModResolver.Planned(v, v.primaryFile!!, 0, "root"))
        val report = installer.install("inst", planned, instanceRunning = false)
        assertEquals(2, calls) // one download + exactly one redownload
        assertEquals(1, report.failures.size)
        assertTrue(report.failures[0].contains("sha1"))
        assertFalse(fs.exists("versions/inst/mods/a.jar"))
    }

    // 7b. redownload success path.
    @Test
    fun `sha1 mismatch then good redownload installs`() {
        val good = "good".toByteArray()
        val bad = "bad".toByteArray()
        var calls = 0
        val fs = FakeFs()
        val installer = ModInstaller(fs, { calls++; if (calls == 1) bad else good }) { }
        val v = version("A", "a1", files = listOf(file(filename = "a.jar", sha1 = sha1(good))))
        val planned = listOf(ModResolver.Planned(v, v.primaryFile!!, 0, "root"))
        val report = installer.install("inst", planned, instanceRunning = false)
        assertTrue(report.failures.isEmpty())
        assertEquals(listOf("a.jar"), report.installed)
        assertEquals(2, calls)
        assertTrue(fs.exists("versions/inst/mods/a.jar"))
        assertEquals(sha1(good), sha1(fs.readAll("versions/inst/mods/a.jar")))
    }

    // 8. running instance rejects the install.
    @Test
    fun `running instance is refused`() {
        val fs = FakeFs()
        val installer = ModInstaller(fs, { ByteArray(0) }) { }
        val v = version("A", "a1")
        val planned = listOf(ModResolver.Planned(v, v.primaryFile!!, 0, "root"))
        try {
            installer.install("inst", planned, instanceRunning = true)
            fail("expected ModInstallException")
        } catch (e: ModInstallException) {
            assertTrue(e.message!!.contains("运行中"))
        }
        assertTrue(fs.files.isEmpty())
    }

    // 9. 429 reads Retry-After and never retries immediately.
    @Test
    fun `429 honours Retry-After before retrying`() {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val fetcher = ModrinthMetaFetcher(
            get = {
                calls++
                if (calls == 1) {
                    ModrinthRawResponse(429, mapOf("retry-after" to "3"), "")
                } else {
                    ModrinthRawResponse(200, emptyMap(), "{\"ok\":true}")
                }
            },
            sleep = { sleeps += it },
            log = { },
        )
        assertEquals("{\"ok\":true}", fetcher.fetch(listOf("http://fake")))
        assertEquals(listOf(3000L), sleeps)
        assertEquals(2, calls)
        assertTrue(ModrinthRateLimit.backoffMillis(null, 1) >= 1000L)
        assertTrue(ModrinthRateLimit.backoffMillis("0", 1) >= 1000L)
    }

    // 10. a Forge-only version is refused on a Fabric instance with a readable message.
    @Test
    fun `install refuses a forge only version on a fabric instance`() {
        val forge = version("JEI", "forge1", gameVersions = listOf("1.20.1"), loaders = listOf("forge"))
        val client = ModrinthClient(
            OfficialDownloadSource,
            MetaFetcher { "" },
            DownloadTransport { _, _ -> DownloadPayload(200, "http://fake", null, 0L, byteArrayOf()) },
        ) { }
        val installer = ModInstaller(FakeFs(), { ByteArray(0) }) { }
        val service = ModrinthService(client, installer) { }
        val instance = InstanceSummary(
            id = "Fabric-Lab", displayName = "Fabric-Lab", mcVersion = "1.20.1", loaderKind = "fabric",
        )
        try {
            service.install(instance, forge, emptySet(), instanceRunning = false)
            fail("expected ModInstallException")
        } catch (e: ModInstallException) {
            assertTrue(e.message!!.contains("不支持 1.20.1 + fabric"))
            assertTrue(e.message!!.contains("拒绝安装"))
        }
    }

    // 11. versions URL carries strict game_versions + loaders arrays.
    @Test
    fun `versions url carries strict mc and loader filters`() {
        val url = OfficialDownloadSource.modrinthVersions("sodium", "1.20.1", "fabric").single()
        assertTrue(url.contains("game_versions"))
        assertTrue(url.contains("loaders"))
        assertTrue(url.contains("1.20.1"))
        assertTrue(url.contains("fabric"))
    }
}
