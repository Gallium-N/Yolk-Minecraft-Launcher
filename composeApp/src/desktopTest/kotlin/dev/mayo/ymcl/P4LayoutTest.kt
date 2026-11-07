package dev.mayo.ymcl

import dev.mayo.ymcl.app.DownloadFileSystem
import dev.mayo.ymcl.app.DownloadPayload
import dev.mayo.ymcl.app.DownloadSource
import dev.mayo.ymcl.app.DownloadTransport
import dev.mayo.ymcl.app.GameDownloader
import dev.mayo.ymcl.app.GameInstaller
import dev.mayo.ymcl.app.LauncherSettings
import dev.mayo.ymcl.app.LauncherSettingsFile
import dev.mayo.ymcl.domain.InstanceId
import dev.mayo.ymcl.domain.InstanceLayout
import dev.mayo.ymcl.domain.IsolationMode
import dev.mayo.ymcl.domain.NameValidation
import dev.mayo.ymcl.domain.VersionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

/** P4 step-0 offline tests: slug rules, layout resolution, settings defaults, dedup. */
class P4LayoutTest {
    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    // ---- 1. Chinese displayName -> usable, non-empty instanceId ----
    @Test
    fun `chinese display name yields a usable non-empty instanceId`() {
        // A pure-CJK name folds to an empty slug and falls back to mc-<version>-<n>.
        val id = InstanceId.allocate("空岛生存", "1.20.1", emptySet())
        assertTrue(id.isNotBlank())
        assertTrue(InstanceId.validate(id) is NameValidation.Valid)
        // A mixed name keeps its ASCII part.
        assertEquals("Create", InstanceId.slugify("Create 机械时代"))
        val mixed = InstanceId.allocate("Create 机械时代", "1.20.1", emptySet())
        assertEquals("Create", mixed)
    }

    // ---- 2. Reserved names and trailing dot/space are rejected ----
    @Test
    fun `reserved device names and trailing dot or space are rejected`() {
        listOf("CON", "con", "Com1", "com1", "PRN", "nul", "LPT9").forEach {
            assertTrue("$it should be rejected", InstanceId.validate(it) is NameValidation.Invalid)
        }
        assertTrue(InstanceId.validate("world.") is NameValidation.Invalid)
        assertTrue(InstanceId.validate("world ") is NameValidation.Invalid)
        assertTrue(InstanceId.validate("CONSOLE") is NameValidation.Valid)
        assertTrue(InstanceId.validate("COM10") is NameValidation.Valid)
    }

    // ---- 3. Conflict increments -2, -3 and never overwrites ----
    @Test
    fun `conflicting instance ids increment and never overwrite`() {
        assertEquals("World-2", InstanceId.allocate("World", "1.20.1", setOf("World")))
        assertEquals(
            "World-3",
            InstanceId.allocate("World", "1.20.1", setOf("World", "World-2")),
        )
        assertEquals(
            "mc-1.20.1-2",
            InstanceId.allocate("整合包", "1.20.1", setOf("mc-1.20.1-1")),
        )
        val existing = setOf("World", "World-2")
        val allocated = InstanceId.allocate("World", "1.20.1", existing)
        assertFalse(allocated in existing)
    }

    private val root = "/srv/ymcl/game"

    // ---- 4. isolated vs non-isolated --gameDir / --assetsDir ----
    @Test
    fun `isolated mode targets the instance dir and non-isolated targets the root`() {
        val isolated = InstanceLayout.resolve(true, false, root, "inst-a")
        assertEquals(IsolationMode.ISOLATED, isolated.mode)
        assertEquals("/srv/ymcl/game/versions/inst-a", isolated.runDir)
        assertEquals("/srv/ymcl/game/assets", isolated.assetsDir)
        assertEquals("/srv/ymcl/game/libraries", isolated.librariesDir)
        assertTrue(isolated.describe().contains("--gameDir /srv/ymcl/game/versions/inst-a"))
        assertTrue(isolated.describe().contains("--assetsDir /srv/ymcl/game/assets"))
        assertEquals(null, isolated.notice)

        val shared = InstanceLayout.resolve(false, false, root, "inst-a")
        assertEquals(IsolationMode.SHARED_ROOT, shared.mode)
        assertEquals(root, shared.runDir)
        assertTrue(shared.describe().contains("--gameDir $root"))
        assertFalse(shared.describe().contains("--gameDir $root/versions"))
    }

    // ---- 5. non-empty root mods/ forces shared-root mode with a notice ----
    @Test
    fun `non-empty root mods forces shared-root mode`() {
        val forced = InstanceLayout.resolve(true, true, root, "inst-a")
        assertEquals(IsolationMode.SHARED_ROOT, forced.mode)
        assertEquals(root, forced.runDir)
        assertEquals(InstanceLayout.SHARED_MODS_NOTICE, forced.notice)
        // Honour the setting when root mods/ is empty.
        assertEquals(IsolationMode.ISOLATED, InstanceLayout.resolve(true, false, root, "inst-a").mode)
    }

    // ---- 6. second install of same version is entirely cached/skipped ----
    @Test
    fun `second same-version install downloads only the version json`() {
        val fixture = installerFixture()
        val s1 = fixture.installer.install("1.20.1", fixture.entry, "inst-a")
        val s2 = fixture.installer.install("1.20.1", fixture.entry, "inst-b")

        assertEquals(0, s1.failures)
        assertEquals(0, s2.failures)
        // All shared files are skipped; only the per-instance version json is fetched.
        assertEquals(s2.files - 1, s2.skipped)
        assertEquals(fixture.versionJson.size.toLong(), s2.downloadedBytes)
        // Exactly two GETs total (one version json per install); no shared file refetched.
        assertEquals(2, fixture.transport.calls.size)
        assertTrue(fixture.transport.calls.all { it == "http://vjson" })
        // The shared asset object bytes were never rewritten.
        assertEquals(
            fixture.assetBytes.toList(),
            fixture.fs.files[fixture.assetRel]!!.toList(),
        )
    }

    // ---- 7. missing settings fields fall back to defaults, no rewrite on load ----
    @Test
    fun `missing settings fields fall back to defaults and read never rewrites the file`() {
        val temp = Files.createTempDirectory("ymcl-p4-settings").toString()
        val settings = LauncherSettings(temp) { }
        java.io.File(settings.file).apply {
            parentFile.mkdirs()
            writeText("{\"schemaVersion\":1,\"preferBmclapi\":false}")
        }
        val loaded = settings.load()
        assertEquals("", loaded.gameRoot)
        assertTrue(loaded.isolatedInstances)
        assertEquals("$temp/data/game", settings.gameRootPath())
        assertTrue(settings.isolationEnabled())
        // A read must not rewrite the file (missing fields stay missing).
        assertEquals(
            "{\"schemaVersion\":1,\"preferBmclapi\":false}",
            java.io.File(settings.file).readText(),
        )

        settings.save(LauncherSettingsFile(gameRoot = "/custom/game", isolatedInstances = false))
        assertFalse(settings.load().isolatedInstances)
        assertEquals("/custom/game", settings.gameRootPath())
    }

    // ---- 8. instanceDir and nativesDir live under versions/<id> ----
    @Test
    fun `instance and natives paths are derived from the shared root`() {
        val paths = InstanceLayout.resolve(true, false, root, "World")
        assertEquals("/srv/ymcl/game/versions/World", paths.instanceDir)
        assertEquals("/srv/ymcl/game/versions/World/natives", paths.nativesDir)
        assertTrue(paths.describe().contains("-Djava.library.path=/srv/ymcl/game/versions/World/natives"))
        assertTrue(InstanceId.pathTooLong(root, "x".repeat(300)))
        assertFalse(InstanceId.pathTooLong(root, "World"))
    }

    // ------------------------------------------------------------------
    // Offline GameInstaller fixture: everything shared is pre-seeded, and the
    // only network-visible file is the tiny per-instance version json.
    // ------------------------------------------------------------------
    private class InstallerFixture(
        val fs: FakeFs,
        val transport: FakeTransport,
        val installer: GameInstaller,
        val entry: VersionEntry,
        val versionJson: ByteArray,
        val assetBytes: ByteArray,
        val assetRel: String,
    )

    private fun installerFixture(): InstallerFixture {
        val jarBytes = "client-jar".toByteArray()
        val libBytes = "lib".toByteArray()
        val assetBytes = "asset".toByteArray()
        val assetHash = sha1(assetBytes)
        val indexText = (
            "{\"objects\":{\"sounds/x.ogg\":{\"hash\":\"$assetHash\"," +
                "\"size\":${assetBytes.size}}}}"
            ).toByteArray()
        val assetRel = "assets/objects/${assetHash.take(2)}/$assetHash"

        // The version json must describe the exact pre-seeded index bytes, so the
        // downloader skips it via sha1/size verification.
        val versionJsonText = buildString {
            append("{")
            append("\"id\":\"1.20.1\",")
            append("\"assetIndex\":{\"id\":\"5\",\"url\":\"http://idx/5.json\",")
            append("\"sha1\":\"${sha1(indexText)}\",\"size\":${indexText.size}},")
            append("\"downloads\":{\"client\":{\"url\":\"http://jar/client.jar\",")
            append("\"sha1\":\"${sha1(jarBytes)}\",\"size\":${jarBytes.size}}},")
            append("\"libraries\":[{\"name\":\"g:a:1\",\"downloads\":{\"artifact\":")
            append("{\"path\":\"g/a/1/a-1.jar\",\"sha1\":\"${sha1(libBytes)}\",")
            append("\"size\":${libBytes.size}}}}]")
            append("}")
        }.toByteArray()

        val fs = FakeFs().apply {
            files["assets/indexes/5.json"] = indexText
            files["libraries/ymcl-clientjar/1.20.1.jar"] = jarBytes
            files["libraries/g/a/1/a-1.jar"] = libBytes
            files[assetRel] = assetBytes
        }
        val transport = FakeTransport(versionJsonText)
        val source = object : DownloadSource {
            override val id = "fake"
            override fun versionManifest(): List<String> = listOf("http://manifest")
            override fun versionJson(id: String, url: String): List<String> = listOf("http://vjson")
            override fun library(path: String): List<String> = listOf("http://lib/$path")
            override fun asset(hash: String): List<String> = listOf("http://asset/$hash")
            override fun clientJar(versionId: String, url: String): List<String> = listOf("http://client")
            override fun loaderMaven(group: String, artifact: String, version: String, classifier: String?, ext: String): List<String> =
                listOf("http://maven")
            override fun fabricLoaderMetadata(mcVersion: String): List<String> = listOf("http://fabric-meta")
            override fun fabricProfile(mcVersion: String, loaderVersion: String): List<String> = listOf("http://profile")
        }
        val downloader = GameDownloader(transport, fs) { }
        val installer = GameInstaller(source, downloader, fs) { }
        val entry = VersionEntry("1.20.1", url = "http://vjson-entry", sha1 = sha1(versionJsonText))
        return InstallerFixture(fs, transport, installer, entry, versionJsonText, assetBytes, assetRel)
    }

    private class FakeFs : DownloadFileSystem {
        val files = linkedMapOf<String, ByteArray>()
        override fun exists(p: String): Boolean = files.containsKey(p)
        override fun size(p: String): Long = files[p]?.size?.toLong() ?: 0L
        override fun sha1Of(p: String): String = sha1Of(files[p] ?: byteArrayOf())
        override fun readAll(p: String): ByteArray = files[p] ?: byteArrayOf()
        override fun writeNew(p: String, data: ByteArray) { files[p] = data }
        override fun append(p: String, data: ByteArray) { files[p] = (files[p] ?: byteArrayOf()) + data }
        override fun delete(p: String) { files.remove(p) }
        override fun move(from: String, to: String) { files[to] = files.remove(from)!! }
        override fun unpackNatives(jarBytes: ByteArray, destDir: String) {}
        // linkOrCopy uses the interface default (plain copy) — fine for a fake.
        private fun sha1Of(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes)
                .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }

    private class FakeTransport(private val versionJson: ByteArray) : DownloadTransport {
        val calls = mutableListOf<String>()
        override fun get(url: String, rangeStart: Long): DownloadPayload {
            calls += url
            if (url != "http://vjson") {
                throw IllegalStateException("unexpected GET of a shared file: $url")
            }
            val slice = if (rangeStart > 0) {
                versionJson.copyOfRange(minOf(rangeStart.toInt(), versionJson.size), versionJson.size)
            } else {
                versionJson
            }
            return DownloadPayload(
                statusCode = if (rangeStart > 0) 206 else 200,
                finalUrl = url,
                bmclapiHash = null,
                bytesWritten = slice.size.toLong(),
                bytes = slice,
                rangeHonored = true,
            )
        }
    }
}
