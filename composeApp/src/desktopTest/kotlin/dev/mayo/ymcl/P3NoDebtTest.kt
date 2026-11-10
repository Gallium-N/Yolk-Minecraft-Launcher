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
}
