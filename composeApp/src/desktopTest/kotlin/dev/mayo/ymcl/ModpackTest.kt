package dev.mayo.ymcl

import dev.mayo.ymcl.app.CfFetcher
import dev.mayo.ymcl.app.CurseForgeFileResolver
import dev.mayo.ymcl.app.ModResolver
import dev.mayo.ymcl.app.ModpackArchive
import dev.mayo.ymcl.app.ModpackParseException
import dev.mayo.ymcl.app.ModpackParser
import dev.mayo.ymcl.app.OfficialDownloadSource
import dev.mayo.ymcl.app.PackEntry
import dev.mayo.ymcl.app.PackSource
import dev.mayo.ymcl.app.versionLabelOf
import dev.mayo.ymcl.domain.InstanceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure, offline tests for the P5 mrpack / CurseForge importer. */
class ModpackTest {

    private class FakeArchive(private val files: Map<String, ByteArray>) : ModpackArchive {
        override fun entries(): List<PackEntry> = files.keys.map { PackEntry(it, false) }
        override fun read(name: String): ByteArray = files[name] ?: error("missing entry: $name")
        override fun close() {}
    }

    private fun mrpack(filesJson: String, name: String = "Test Pack"): String = """
        {"formatVersion":1,"game":"minecraft","versionId":"1.0.0","name":"$name",
         "dependencies":{"minecraft":"1.20.1","fabric-loader":"0.14.22"},
         "files":[$filesJson]}
    """.trimIndent()

    private fun file(path: String, client: String = "required"): String = """
        {"path":"$path","fileSize":123,"hashes":{"sha1":"abc"},
         "downloads":["http://fixture/$path"],"env":{"client":"$client","server":"required"}}
    """.trimIndent()

    // 1. index parsing: dependencies, files, env, sha1, downloads, id allocation.
    @Test
    fun `mrpack index parses dependencies files and env`() {
        val plan = ModpackParser.parse(
            FakeArchive(mapOf("modrinth.index.json" to mrpack(file("mods/a.jar")).toByteArray())),
            emptySet(),
        )
        assertEquals(PackSource.MRPACK, plan.source)
        assertEquals("Test Pack", plan.name)
        assertEquals("1.0.0", plan.packVersion)
        assertEquals("1.20.1", plan.mcVersion)
        assertEquals("fabric", plan.loaderKind)
        assertEquals("0.14.22", plan.loaderVersion)
        assertEquals(1, plan.files.size)
        assertEquals("mods/a.jar", plan.files[0].path)
        assertEquals(123L, plan.files[0].size)
        assertEquals("abc", plan.files[0].sha1)
        assertEquals(listOf("http://fixture/mods/a.jar"), plan.files[0].downloads)
        assertEquals("Test-Pack", plan.suggestedId)
    }

    // 2. env.client == unsupported files are skipped and counted.
    @Test
    fun `env client unsupported files are skipped and counted`() {
        val files = listOf(
            file("mods/client.jar", "required"),
            file("mods/optional.jar", "optional"),
            file("mods/server-only.jar", "unsupported"),
        ).joinToString(",")
        val plan = ModpackParser.parse(
            FakeArchive(mapOf("modrinth.index.json" to mrpack(files).toByteArray())), emptySet(),
        )
        assertEquals(2, plan.files.size)
        assertEquals(1, plan.skippedServerOnly)
        assertTrue(plan.files.none { it.path.contains("server-only") })
    }

    // 3. zip-slip: ../../evil.txt is dropped and counted with a warning.
    @Test
    fun `zip slip parent traversal is dropped and warned`() {
        val files = listOf(file("../../evil.txt"), file("mods/ok.jar")).joinToString(",")
        val plan = ModpackParser.parse(
            FakeArchive(mapOf("modrinth.index.json" to mrpack(files).toByteArray())), emptySet(),
        )
        assertEquals(1, plan.files.size)
        assertEquals("mods/ok.jar", plan.files[0].path)
        assertEquals(1, plan.droppedUnsafe)
        assertTrue(plan.warnings.any { it.contains("zip-slip") })
    }

    // 4. absolute paths and Windows drive letters are dropped too.
    @Test
    fun `absolute path and windows drive are dropped`() {
        val files = listOf(file("/etc/passwd"), file("a/../b.jar"), file("mods/ok.jar"))
            .joinToString(",")
        val plan = ModpackParser.parse(
            FakeArchive(mapOf("modrinth.index.json" to mrpack(files).toByteArray())), emptySet(),
        )
        assertEquals(1, plan.files.size)
        assertEquals(2, plan.droppedUnsafe)
        assertNull(ModpackParser.safePackPath("/abs/x.jar"))
        assertNull(ModpackParser.safePackPath("D:/x.jar"))
        assertNull(ModpackParser.safePackPath("C:\\windows\\evil.txt"))
        assertNull(ModpackParser.safePackPath("a/../b.jar"))
        assertEquals("mods/x.jar", ModpackParser.safePackPath("mods\\x.jar"))
    }

    // 5. package name -> instanceId, duplicate import increments (never overwrite).
    @Test
    fun `package name allocates incrementing instance ids`() {
        val archive = { FakeArchive(mapOf("modrinth.index.json" to mrpack(file("mods/a.jar")).toByteArray())) }
        val first = ModpackParser.parse(archive(), emptySet()).suggestedId
        val second = ModpackParser.parse(archive(), setOf(first)).suggestedId
        val third = ModpackParser.parse(archive(), setOf(first, second)).suggestedId
        assertEquals("Test-Pack", first)
        assertEquals("Test-Pack-2", second)
        assertEquals("Test-Pack-3", third)
        assertEquals("Test-Pack-2", InstanceId.allocate("Test Pack", "1.20.1", setOf("Test-Pack")))
    }

    // 6. CurseForge manifest parses loader version + file count (no filename guessing).
    @Test
    fun `curseforge manifest parses loader and files`() {
        val manifest = """
            {"minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.2.0","primary":true}]},
             "name":"CF Pack","version":"2.0","overrides":"overrides",
             "files":[{"projectID":238222,"fileID":4644452,"required":true},
                      {"projectID":306612,"fileID":4644453,"required":true}]}
        """.trimIndent()
        val plan = ModpackParser.parse(
            FakeArchive(mapOf("manifest.json" to manifest.toByteArray())), emptySet(),
        )
        assertEquals(PackSource.CURSEFORGE, plan.source)
        assertEquals("CF Pack", plan.name)
        assertEquals("2.0", plan.packVersion)
        assertEquals("1.20.1", plan.mcVersion)
        assertEquals("forge", plan.loaderKind)
        assertEquals("47.2.0", plan.loaderVersion)
        assertEquals(2, plan.files.size)
        assertEquals("238222", plan.files[0].cfProjectId)
        assertEquals("4644452", plan.files[0].cfFileId)
        assertTrue(plan.files.all { it.downloads.isEmpty() && it.path.isEmpty() })
    }

    // 7. CF three-level strategy with a fake fetcher: key / proxy / missing.
    @Test
    fun `cf three level strategy uses api then proxy then readable error`() {
        val okJson = """{"data":{"fileName":"jei-1.20.1.jar","downloadUrl":"http://fixture/jei.jar","hashes":[{"algo":1,"value":"deadbeef"}]}}"""

        val l1Urls = mutableListOf<String>()
        val l1Keys = mutableListOf<String?>()
        val level1 = CurseForgeFileResolver(
            OfficialDownloadSource,
            CfFetcher { url, key -> l1Urls += url; l1Keys += key; okJson },
            "SECRET-KEY", true, log = {},
        )
        assertEquals(CurseForgeFileResolver.Level.API_KEY, level1.level())
        val r1 = level1.resolve("238222", "4644452")
        assertNotNull(r1)
        assertEquals("api", r1!!.via)
        assertEquals("jei-1.20.1.jar", r1.filename)
        assertEquals("deadbeef", r1.sha1)
        assertEquals(
            OfficialDownloadSource.cfOfficialFile("238222", "4644452").single(),
            l1Urls.single(),
        )
        assertEquals("SECRET-KEY", l1Keys.single())

        val l2Urls = mutableListOf<String>()
        val level2 = CurseForgeFileResolver(
            OfficialDownloadSource,
            CfFetcher { url, _ -> l2Urls += url; okJson },
            null, true, log = {},
        )
        assertEquals(CurseForgeFileResolver.Level.PROXY, level2.level())
        val r2 = level2.resolve("238222", "4644452")
        assertNotNull(r2)
        assertEquals("curse.tools", r2!!.via)
        assertEquals(
            OfficialDownloadSource.cfProxyFile("238222", "4644452").single(),
            l2Urls.single(),
        )

        val level3 = CurseForgeFileResolver(
            OfficialDownloadSource,
            CfFetcher { _, _ -> okJson },
            null, false, log = {},
        )
        assertEquals(CurseForgeFileResolver.Level.MISSING, level3.level())
        assertNull(level3.resolve("238222", "4644452"))
        val message = CurseForgeFileResolver.missingMessage(2)
        assertTrue(message.contains("2 个文件"))
        assertTrue(message.contains("CurseForge API key"))
        assertTrue(message.contains("不会猜测文件名"))
    }

    // 8. overrides priority: client-overrides wins over overrides; server-overrides skipped.
    @Test
    fun `client overrides win and launcher config is captured`() {
        val archive = FakeArchive(
            mapOf(
                "modrinth.index.json" to mrpack("").toByteArray(),
                "overrides/config/foo.json" to "OVERRIDES".toByteArray(),
                "client-overrides/config/foo.json" to "CLIENT".toByteArray(),
                "client-overrides/.launcher/instance.json" to "{\"xmxMb\":6144}".toByteArray(),
                "server-overrides/server-only.txt" to "SERVER".toByteArray(),
            ),
        )
        val plan = ModpackParser.parse(archive, emptySet())
        val foo = plan.overrides.single { it.path == "config/foo.json" }
        assertEquals("CLIENT", String(foo.bytes))
        assertEquals("{\"xmxMb\":6144}", plan.instanceConfigJson)
        assertTrue(plan.overrides.none { it.path.contains("server-only") })
        assertTrue(plan.overrides.none { it.path.contains(".launcher") })
    }

    // 9. CDN url is built from the REAL filename returned by the API, never a guess.
    @Test
    fun `cf cdn url comes from a resolved filename`() {
        val urls = OfficialDownloadSource.cfCdnFile("4644452", "jei-1.20.1.jar")
        assertEquals(1, urls.size)
        // Path is derived from fileId/1000, fileId%1000 and the REAL filename.
        assertTrue(urls.single().endsWith("/files/4644/452/jei-1.20.1.jar"))
    }

    // 10. unsafe server filenames are rejected by the P4 filename guard.
    @Test
    fun `server filename path traversal is rejected`() {
        assertNull(ModResolver.safeFilename("../../etc/passwd"))
        assertNull(ModResolver.safeFilename("dir/../passwd.jar"))
        assertEquals("sodium.jar", ModResolver.safeFilename("C:\\mods\\sodium.jar"))
    }

    // 11. Step-0 result-row label: raw version string -> short version + loader.
    @Test
    fun `version label strips mc and loader tokens`() {
        val a = versionLabelOf("mc1.20.1-0.5.13-fabric", listOf("fabric"))
        assertEquals("0.5.13", a.version)
        assertEquals("fabric", a.loader)
        val b = versionLabelOf("1.7.6+1.20.1", emptyList())
        assertEquals("1.7.6", b.version)
    }

    // 12. Unknown archive -> readable parse error, not a four-word "导入失败".
    @Test
    fun `unknown zip gets a readable parse error`() {
        val e = runCatching { ModpackParser.parse(FakeArchive(mapOf("readme.txt" to "hi".toByteArray())), emptySet()) }
            .exceptionOrNull()
        assertTrue(e is ModpackParseException)
        assertTrue(e!!.message!!.contains("modrinth.index.json"))
        assertTrue(e.message!!.contains("manifest.json"))
    }
}
