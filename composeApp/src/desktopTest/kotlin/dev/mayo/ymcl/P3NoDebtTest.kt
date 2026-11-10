package dev.mayo.ymcl

import dev.mayo.ymcl.app.DownloadFileSystem
import dev.mayo.ymcl.app.DownloadPayload
import dev.mayo.ymcl.app.DownloadSource
import dev.mayo.ymcl.app.DownloadTransport
import dev.mayo.ymcl.app.GameDownloader
import dev.mayo.ymcl.app.InstallerRun
import dev.mayo.ymcl.app.LoaderInstallException
import dev.mayo.ymcl.app.LoaderInstaller
import dev.mayo.ymcl.app.LoaderProcess
import dev.mayo.ymcl.app.MetaFetcher
import dev.mayo.ymcl.app.MetaRepository
import dev.mayo.ymcl.app.MetaStore
import dev.mayo.ymcl.app.Reachability
import dev.mayo.ymcl.domain.LoaderKind
import dev.mayo.ymcl.domain.VersionEntry
import dev.mayo.ymcl.domain.VersionManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/** Section A–F of the P3 no-debt book: offline loader / asset / matrix tests. */
class P3NoDebtTest {

    // ---------- shared in-memory fixtures ----------

    class MemFs : DownloadFileSystem {
        val files = linkedMapOf<String, ByteArray>()
        override fun exists(p: String) = files.containsKey(p)
        override fun size(p: String) = files[p]?.size?.toLong() ?: 0L
        override fun sha1Of(p: String) = MessageDigest.getInstance("SHA-1")
            .digest(files[p] ?: byteArrayOf())
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        override fun readAll(p: String) = files[p] ?: byteArrayOf()
        override fun writeNew(p: String, data: ByteArray) { files[p] = data }
        override fun append(p: String, data: ByteArray) { files[p] = (files[p] ?: byteArrayOf()) + data }
        override fun delete(p: String) { files.remove(p) }
        override fun move(from: String, to: String) { files[to] = files.remove(from)!! }
        override fun unpackNatives(jarBytes: ByteArray, destDir: String) {}
        override fun listDirs(relativePath: String): List<String> = files.keys
            .filter { it.startsWith("$relativePath/") && it.count { c -> c == '/' } >= 2 }
            .map { it.removePrefix("$relativePath/").substringBefore('/') }
            .distinct().sorted()
    }

    class MemSource : DownloadSource {
        override val id = "fake"
        override fun versionManifest() = listOf("manifest")
        override fun versionJson(id: String, url: String) = listOf("version")
        override fun library(path: String) = listOf("library/$path")
        override fun asset(hash: String) = listOf("asset/$hash")
        override fun clientJar(versionId: String, url: String) = listOf("client")
        override fun loaderMaven(group: String, artifact: String, version: String, classifier: String?, ext: String) =
            listOf("mirror/${group.replace('.', '/')}/$artifact/$version/$artifact-$version.$ext")
        override fun fabricLoaderMetadata(mcVersion: String) = listOf("fabric-metadata")
        override fun fabricProfile(mcVersion: String, loaderVersion: String) = listOf("fabric-profile")
        override fun assetIndex(id: String, url: String) = listOf(url)
    }

    class MemStore : MetaStore {
        val files = mutableMapOf<String, String>()
        override fun read(path: String) = files[path]
        override fun write(path: String, content: String) { files[path] = content }
        override fun lastModified(path: String) = 0L
        override fun sha1Hex(content: String) = ""
    }

    class FakeProcess(
        var reachable: Boolean = true,
        var generatedDir: String = "1.20.1-forge-47.2.0",
        var generatedProfile: String? = null,
    ) : LoaderProcess {
        val runs = mutableListOf<List<String>>()
        var fs: MemFs? = null
        override fun probe(url: String, attempts: Int) =
            Reachability(reachable, attempts, List(attempts) { if (reachable) 200 else 0 })
        override fun runInstaller(
            jarPath: String, args: List<String>, workDir: String, timeoutMinutes: Long,
        ): InstallerRun {
            runs += args
            if (args.any { it == "--installClient" } && generatedProfile != null) {
                fs?.files?.put(
                    "versions/$generatedDir/$generatedDir.json",
                    generatedProfile!!.toByteArray(Charsets.UTF_8),
                )
            }
            return InstallerRun(0, listOf("--installClient <dir>", "Launching target 'forgeclient'"), 42)
        }
    }

    private val fabricProfileJson = """
        {"id":"fabric-loader-0.20.1-1.20.1","inheritsFrom":"1.20.1",
         "mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
         "libraries":[{"name":"org.ow2.asm:asm:9.10.1","url":"upstream"}]}
    """.trimIndent()

    private val metadataXml = "<metadata><versioning><versions><version>0.20.1</version></versions></versioning></metadata>"

    private fun vanillaJson(libCount: Int): String {
        val libs = (1..libCount).joinToString(",") { i -> """{"name":"vanilla:lib$i:1.0"}""" }
        return """{"id":"1.20.1","type":"release","mainClass":"net.minecraft.client.main.Main",
            "arguments":{"game":["--username"],"jvm":["-Xmx2G"]},
            "libraries":[$libs],"assetIndex":{"id":"5"},"assets":"5","javaVersion":{"majorVersion":17}}""".trimIndent()
    }

    private fun forgeProfile(libCount: Int): String {
        val libs = (1..libCount).joinToString(",") { i ->
            """{"name":"net.minecraftforge:forge:$i","url":"https://maven.minecraftforge.net/"}"""
        }
        return """{"id":"1.20.1-forge-47.2.0","inheritsFrom":"1.20.1",
            "mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher",
            "arguments":{"game":["--launchTarget","forgeclient"],"jvm":["-Dforge.x=1"]},
            "libraries":[$libs]}""".trimIndent()
    }

    private val fakeTransport = DownloadTransport { url, _ ->
        DownloadPayload(200, url, null, 4L, "jar!".toByteArray())
    }

    private fun fixture(
        fetcher: MetaFetcher = MetaFetcher { candidates ->
            if (candidates.any { it.contains("metadata") }) metadataXml else fabricProfileJson
        },
        process: LoaderProcess = FakeProcess(),
    ): Triple<LoaderInstaller, MemFs, MemSource> {
        val source = MemSource()
        val fs = MemFs()
        val store = MemStore()
        store.files["/root/data/meta/version_manifest_v2.json"] =
            """{"schemaVersion":1,"manifest":{"versions":[{"id":"1.20.1"}]}}"""
        store.files["/root/data/meta/fabric/1.20.1-0.20.1.json"] = fabricProfileJson
        val meta = MetaRepository("/root", source, store, fetcher, { 0L })
        val downloader = GameDownloader(fakeTransport, fs) { }
        val installer = LoaderInstaller(source, downloader, fs, meta, log = { }, process = process, gameRoot = "/root")
        return Triple(installer, fs, source)
    }

    // ---------- A: four loaders ----------

    @Test
    fun `fabric profile urls are rewritten through DownloadSource`() {
        val (installer, fs, source) = fixture()
        fs.files["libraries/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar"] = "jar".toByteArray()
        val result = installer.installFabric("1.20.1", VersionManifest(listOf(VersionEntry("1.20.1"))))
        assertEquals(1, result.libraryCount)
        val root = Json.parseToJsonElement(
            String(fs.files["versions/fabric-loader-0.20.1-1.20.1/fabric-loader-0.20.1-1.20.1.json"]!!),
        ).jsonObject
        assertEquals(
            source.loaderMaven("org.ow2.asm", "asm", "9.10.1", null, "jar").first(),
            root["libraries"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `quilt loader versions and profile come from quilt meta with KnotClient`() {
        val versions = """[{"loader":{"version":"0.24.0"}},{"loader":{"version":"0.20.0-beta.9"}}]"""
        val profile = """{"id":"quilt-loader-0.24.0-1.20.1","inheritsFrom":"1.20.1",
            "mainClass":"org.quiltmc.loader.impl.launch.knot.KnotClient",
            "libraries":[{"name":"org.quiltmc:quilt-loader:0.24.0","url":"https://maven.quiltmc.org/repository/release/"}]}"""
        val fetcher = MetaFetcher { candidates ->
            if (candidates.any { it.contains("/v3/versions/loader/1.20.1") && !it.contains("/profile/") }) versions else profile
        }
        val (installer, fs, _) = fixture(fetcher)
        fs.files["libraries/org/quiltmc/quilt-loader/0.24.0/quilt-loader-0.24.0.jar"] = "jar".toByteArray()
        val result = installer.installQuilt("1.20.1", null, "Quilt-Test")
        assertEquals("0.24.0", result.loaderVersion)
        assertEquals("Quilt-Test", result.versionId)
        val root = Json.parseToJsonElement(String(fs.files["versions/Quilt-Test/Quilt-Test.json"]!!)).jsonObject
        assertEquals("org.quiltmc.loader.impl.launch.knot.KnotClient", root["mainClass"]!!.jsonPrimitive.content)
        assertEquals("1.20.1", root["inheritsFrom"]!!.jsonPrimitive.content)
    }

    @Test
    fun `forge writes launcher_profiles and a self contained 117 library profile`() {
        val fake = FakeProcess(generatedProfile = forgeProfile(29))
        val (installer, fs, _) = fixture(process = fake)
        fake.fs = fs
        fs.files["versions/1.20.1/1.20.1.json"] = vanillaJson(88).toByteArray(Charsets.UTF_8)
        fs.files["versions/1.20.1/1.20.1.jar"] = "jar".toByteArray()
        val result = installer.installForge("1.20.1", "47.2.0", "Forge-Test")
        assertEquals(117, result.libraryCount)
        assertEquals("Forge-Test", result.versionId)
        assertTrue(fs.files.containsKey("launcher_profiles.json"))
        val root = Json.parseToJsonElement(String(fs.files["versions/Forge-Test/Forge-Test.json"]!!)).jsonObject
        assertFalse(root.containsKey("inheritsFrom"))
        assertEquals("Forge-Test", root["jar"]!!.jsonPrimitive.content)
        assertEquals(117, root["libraries"]!!.jsonArray.size)
        assertTrue(root["arguments"]!!.jsonObject["game"]!!.jsonArray.any {
            it.jsonPrimitive.content == "forgeclient"
        })
        assertTrue(fs.files.containsKey("versions/Forge-Test/Forge-Test.jar"))
        assertEquals(2, fake.runs.size) // --help then --installClient
    }

    @Test
    fun `neoforge unreachable throws a readable error with attempt count`() {
        val (installer, _, _) = fixture(process = FakeProcess(reachable = false))
        try {
            installer.installNeoForge("1.20.4", "20.4.237", "Neo-Test")
            fail("expected LoaderInstallException")
        } catch (e: LoaderInstallException) {
            assertTrue(e.message!!.contains("不可达"))
            assertTrue(e.message!!.contains("3 次"))
        }
    }

    @Test
    fun `neoforge reachable runs the installer jar`() {
        val fake = FakeProcess(generatedDir = "1.20.4-neoforge-20.4.237", generatedProfile = forgeProfile(4))
        val (installer, fs, _) = fixture(process = fake)
        fake.fs = fs
        fs.files["versions/1.20.4/1.20.4.json"] = vanillaJson(3).toByteArray(Charsets.UTF_8)
        fs.files["versions/1.20.4/1.20.4.jar"] = "jar".toByteArray()
        val result = installer.installNeoForge("1.20.4", "20.4.237", "Neo-Test")
        assertEquals("Neo-Test", result.versionId)
        assertTrue(fake.runs.any { it.contains("--installClient") })
    }

    @Test
    fun `vanilla instance inherits the shared base without overwriting it`() {
        val (installer, fs, _) = fixture()
        fs.files["versions/1.20.1/1.20.1.json"] = vanillaJson(3).toByteArray(Charsets.UTF_8)
        fs.files["versions/1.20.1/1.20.1.jar"] = "jar".toByteArray()
        val result = installer.install(LoaderKind.VANILLA, "1.20.1", null, "Vanilla-Two")
        assertEquals(3, result.libraryCount)
        assertTrue(fs.files.containsKey("versions/Vanilla-Two/Vanilla-Two.json"))
        assertTrue(fs.files.containsKey("versions/Vanilla-Two/Vanilla-Two.jar"))
        assertEquals(
            "1.20.1",
            Json.parseToJsonElement(String(fs.files["versions/1.20.1/1.20.1.json"]!!))
                .jsonObject["id"]!!.jsonPrimitive.content,
        )
    }

    // ---------- B: legacy virtual / map_to_resources assets ----------

    @Test
    fun `legacy version range is 1 6 through 1 12 only`() {
        assertTrue(dev.mayo.ymcl.app.LegacyAssets.isLegacy("1.6.4"))
        assertTrue(dev.mayo.ymcl.app.LegacyAssets.isLegacy("1.7.10"))
        assertTrue(dev.mayo.ymcl.app.LegacyAssets.isLegacy("1.12.2"))
        assertFalse(dev.mayo.ymcl.app.LegacyAssets.isLegacy("1.13"))
        assertFalse(dev.mayo.ymcl.app.LegacyAssets.isLegacy("1.16.5"))
        assertFalse(dev.mayo.ymcl.app.LegacyAssets.isLegacy("1.20.1"))
    }

    @Test
    fun `asset names keep spaces and CJK but reject traversal absolute and drive`() {
        val a = dev.mayo.ymcl.app.LegacyAssets
        assertEquals("minecraft/sounds/中文 音效.ogg", a.safeAssetName("minecraft/sounds/中文 音效.ogg"))
        assertEquals("a/b/c.png", a.safeAssetName("a\\b\\c.png"))
        org.junit.Assert.assertNull(a.safeAssetName("../../evil.txt"))
        org.junit.Assert.assertNull(a.safeAssetName("/etc/passwd"))
        org.junit.Assert.assertNull(a.safeAssetName("C:\\evil.txt"))
        org.junit.Assert.assertNull(a.safeAssetName("a/../b"))
    }

    @Test
    fun `virtual plan copies into virtual root and resources with original names`() {
        val idx = """{"virtual":true,"map_to_resources":true,"objects":{
            "minecraft/sounds/step 1.ogg":{"hash":"aabbccdd","size":10},
            "minecraft/textures/中文.png":{"hash":"11223344","size":20},
            "../../evil.txt":{"hash":"deadbeef","size":1}}}"""
        val plan = dev.mayo.ymcl.app.LegacyAssets.plan(idx, "1.12.2")!!
        assertEquals("assets/virtual/1.12.2", plan.root)
        assertTrue(plan.mapToResources)
        assertEquals(3, plan.objectCount)
        assertEquals(1, plan.dropped)
        assertEquals(2, plan.copies.size)
        assertEquals("assets/objects/aa/aabbccdd", plan.copies[0].first)
        assertEquals("assets/virtual/1.12.2/minecraft/sounds/step 1.ogg", plan.copies[0].second)
        assertEquals("resources/minecraft/textures/中文.png", plan.resourceCopies[1].second)
    }

    @Test
    fun `modern version without explicit virtual flag has no plan`() {
        val idx = """{"objects":{"minecraft/sounds/a.ogg":{"hash":"aabb"}}}"""
        org.junit.Assert.assertNull(dev.mayo.ymcl.app.LegacyAssets.plan(idx, "1.20.1"))
    }

    // ---------- C: Java matrix ----------

    private fun java(major: Int) = dev.mayo.ymcl.domain.JavaRuntime(
        id = "j$major", vendor = "Test", major = major, version = "$major.0",
        arch = "x64", os = "linux", path = "/java/$major/bin/java", source = "detected",
    )

    @Test
    fun `java matrix boundaries 1 16 to 1 20 5`() {
        val m = dev.mayo.ymcl.app.JavaMatrix
        assertEquals(8, m.requiredMajor("1.16.5"))
        assertEquals(8, m.requiredMajor("1.12.2"))
        assertEquals(17, m.requiredMajor("1.17"))
        assertEquals(17, m.requiredMajor("1.20.1"))
        assertEquals(17, m.requiredMajor("1.20.4"))
        assertEquals(21, m.requiredMajor("1.20.5"))
        assertEquals(21, m.requiredMajor("1.21.1"))
        assertEquals(21, m.requiredMajor("23w45a"))
    }

    @Test
    fun `java matrix selects exact 8 for old mc and rejects when missing`() {
        val m = dev.mayo.ymcl.app.JavaMatrix
        val runtimes = listOf(java(8), java(17), java(21))
        assertEquals(8, m.select("1.12.2", runtimes).java!!.major)
        val missing = m.select("1.12.2", listOf(java(17), java(21)))
        org.junit.Assert.assertNull(missing.java)
        assertTrue(m.missingMessage(missing).contains("需要 Java 8，本机只有 17/21"))
        assertEquals(21, m.select("1.20.1", runtimes).java!!.major)
        assertEquals(21, m.select("1.21.1", runtimes).java!!.major)
    }

    // ---------- D: transport resume + bmclapi-hash skip ----------

    @Test
    fun `part with mismatched sha1 prefix is discarded and re downloaded`() {
        val fs = MemFs()
        val source = MemSource()
        val logs = mutableListOf<String>()
        val calls = mutableListOf<Long>()
        val transport = DownloadTransport { url, rangeStart ->
            calls += rangeStart
            DownloadPayload(200, url, null, 3L, "abc".toByteArray())
        }
        val downloader = GameDownloader(transport, fs) { logs += it }
        val probe = MemFs()
        probe.files["x"] = "abc".toByteArray()
        val goodSha1 = probe.sha1Of("x")
        val spec = dev.mayo.ymcl.app.DownloadSpec(
            "lib/x.jar", listOf(dev.mayo.ymcl.app.CandidateUrl("mirror/x.jar", "fake")),
            expectedSha1 = goodSha1,
        )
        // A stale .part stamped with a DIFFERENT sha1 prefix.
        fs.files["lib/x.jar.part"] = "old".toByteArray()
        fs.files["lib/x.jar.part.meta"] = "size=3\nsha1Prefix=ffffffffffff\n".toByteArray(Charsets.UTF_8)
        val outcome = downloader.download(spec)
        assertEquals(0L, calls.first())
        assertEquals(3L, outcome.bytes)
        assertTrue(logs.any { it.contains("prefix mismatch") && it.contains("discard") })
        assertFalse(fs.files.containsKey("lib/x.jar.part.meta"))
    }

    @Test
    fun `second install hits skip local bmclapi hash`() {
        val fs = MemFs()
        val logs = mutableListOf<String>()
        val transport = DownloadTransport { _, _ -> error("should not hit network on 2nd install") }
        val downloader = GameDownloader(transport, fs) { logs += it }
        val spec = dev.mayo.ymcl.app.DownloadSpec(
            "lib/x.jar", listOf(dev.mayo.ymcl.app.CandidateUrl("mirror/x.jar", "fake")),
            expectedSha1 = "aabbcc",
        )
        fs.files["lib/x.jar"] = "verified".toByteArray()
        // expectedSha1 matches the pre-computed local sha1 of the fixture bytes.
        val local = fs.sha1Of("lib/x.jar")
        val outcome = downloader.download(spec.copy(expectedSha1 = local))
        assertTrue(outcome.skipped)
        assertTrue(logs.any { it.contains("skip(local, bmclapi-hash=$local)") })
    }

    // ---------- E: base role, modCount, macros ----------

    @Test
    fun `base role instances are filtered but dirs are kept`() {
        val root = java.nio.file.Files.createTempDirectory("ymcl-base-").toFile()
        try {
            val versions = java.io.File(root, "versions")
            java.io.File(versions, "1.20.1").mkdirs()
            java.io.File(versions, "1.20.1/1.20.1.json").writeText("""{"id":"1.20.1"}""")
            java.io.File(versions, "1.20.1/instance.json").writeText(
                """{"schemaVersion":1,"displayName":"1.20.1","mcVersion":"1.20.1","role":"base"}""",
            )
            java.io.File(versions, "Real").mkdirs()
            java.io.File(versions, "Real/Real.json").writeText("""{"id":"Real"}""")
            java.io.File(versions, "Real/instance.json").writeText(
                """{"schemaVersion":1,"displayName":"Real","mcVersion":"1.20.1"}""",
            )
            val repo = dev.mayo.ymcl.platform.FileInstanceRepository(root.absolutePath, true)
            val scan = repo.scan()
            assertEquals(1, scan.instances.size)
            assertEquals("Real", scan.instances[0].id)
            assertTrue(java.io.File(versions, "1.20.1").isDirectory) // not deleted
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `modCount counts only jar files and ignores disabled`() {
        val root = java.nio.file.Files.createTempDirectory("ymcl-mods-").toFile()
        try {
            val instance = java.io.File(root, "versions/Real")
            java.io.File(instance, "mods").mkdirs()
            java.io.File(instance, "Real.json").writeText("""{"id":"Real"}""")
            java.io.File(instance, "instance.json").writeText(
                """{"schemaVersion":1,"displayName":"Real","mcVersion":"1.20.1"}""",
            )
            java.io.File(instance, "mods/a.jar").writeText("a")
            java.io.File(instance, "mods/b.jar").writeText("b")
            java.io.File(instance, "mods/c.jar.disabled").writeText("c")
            java.io.File(instance, "mods/sub").mkdirs()
            assertEquals(2, dev.mayo.ymcl.platform.FileInstanceRepository(root.absolutePath, true).list().single().modCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `launch macros are 32 hex and empty xuid`() {
        val extra = dev.mayo.ymcl.app.LaunchMacros.extras("0123456789abcdef0123456789abcdef")
        assertTrue(Regex("^[0-9a-f]{32}$").matches(extra["\${clientid}"]!!))
        assertEquals("", extra["\${auth_xuid}"])
        assertEquals(2, extra.size)
    }
}
