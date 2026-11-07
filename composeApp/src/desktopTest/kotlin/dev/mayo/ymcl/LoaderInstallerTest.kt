package dev.mayo.ymcl

import dev.mayo.ymcl.app.CandidateUrl
import dev.mayo.ymcl.app.DownloadFileSystem
import dev.mayo.ymcl.app.DownloadSource
import dev.mayo.ymcl.app.GameDownloader
import dev.mayo.ymcl.app.LoaderInstallException
import dev.mayo.ymcl.app.LoaderInstaller
import dev.mayo.ymcl.app.MetaFetcher
import dev.mayo.ymcl.app.MetaRepository
import dev.mayo.ymcl.app.MetaStore
import dev.mayo.ymcl.domain.VersionEntry
import dev.mayo.ymcl.domain.VersionManifest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

class LoaderInstallerTest {
    private val profileJson = """
        {"id":"fabric-loader-0.20.1-1.20.1","inheritsFrom":"1.20.1",
         "mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
         "type":"release",
         "libraries":[
           {"name":"org.ow2.asm:asm:9.10.1","url":"upstream-base","sha1":"ada2141c0cc5","size":126151},
           {"name":"net.fabricmc:fabric-loader:0.20.1","url":"upstream-base"}
         ]}
    """.trimIndent()

    private val metadataXml = """
        <metadata><versioning><versions><version>0.20.1</version></versions></versioning></metadata>
    """.trimIndent()

    /** Fake source: loaderMaven is the only method the installer should use for URLs. */
    private class FakeSource : DownloadSource {
        override val id = "fake"
        override fun versionManifest(): List<String> = listOf("manifest")
        override fun versionJson(id: String, url: String): List<String> = listOf("version")
        override fun library(path: String): List<String> = listOf("library/$path")
        override fun asset(hash: String): List<String> = listOf("asset/$hash")
        override fun clientJar(versionId: String, url: String): List<String> = listOf("client")
        override fun loaderMaven(group: String, artifact: String, version: String, classifier: String?, ext: String): List<String> =
            listOf("mirror/${group.replace('.', '/')}/$artifact/$version/$artifact-$version.$ext")
        override fun fabricLoaderMetadata(mcVersion: String): List<String> = listOf("fabric-loader-maven-metadata.xml")
        override fun fabricProfile(mcVersion: String, loaderVersion: String): List<String> = listOf("profile")
        override fun assetIndex(id: String, url: String): List<String> = listOf(url)
    }

    private class FakeFs : DownloadFileSystem {
        val files = linkedMapOf<String, ByteArray>()
        override fun exists(p: String): Boolean = files.containsKey(p)
        override fun size(p: String): Long = files[p]?.size?.toLong() ?: 0L
        override fun sha1Of(p: String): String =
            MessageDigest.getInstance("SHA-1").digest(files[p] ?: byteArrayOf())
                .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        override fun readAll(p: String): ByteArray = files[p] ?: byteArrayOf()
        override fun writeNew(p: String, data: ByteArray) { files[p] = data }
        override fun append(p: String, data: ByteArray) { files[p] = (files[p] ?: byteArrayOf()) + data }
        override fun delete(p: String) { files.remove(p) }
        override fun move(from: String, to: String) { files[to] = files.remove(from)!! }
        override fun unpackNatives(jarBytes: ByteArray, destDir: String) {}
    }

    private class FakeMetaStore : MetaStore {
        val files = mutableMapOf<String, String>()
        override fun read(path: String): String? = files[path]
        override fun write(path: String, content: String) { files[path] = content }
        override fun lastModified(path: String): Long = 0L
        override fun sha1Hex(content: String): String = ""
    }

    private fun fixture(): Triple<LoaderInstaller, FakeFs, FakeSource> {
        val source = FakeSource()
        val fs = FakeFs()
        val store = FakeMetaStore()
        store.files["/root/data/meta/fabric/1.20.1-0.20.1.json"] = profileJson
        val fetcher = MetaFetcher { candidates ->
            if (candidates.any { it.contains("maven-metadata") }) metadataXml else profileJson
        }
        val meta = MetaRepository("/root", source, store, fetcher, { 0L })
        val downloader = GameDownloader({ _, _ -> error("no network in unit test") }, fs) { }
        return Triple(LoaderInstaller(source, downloader, fs, meta) { }, fs, source)
    }

    private fun manifest(): VersionManifest = VersionManifest(listOf(VersionEntry("1.20.1")))

    @Test
    fun `library urls are rewritten through DownloadSource and version json is correct`() {
        val (installer, fs, source) = fixture()
        // Pre-seed library files so no download is attempted (URL rewriting is the subject).
        fs.files["libraries/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar"] = "jar".toByteArray()
        fs.files["libraries/net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar"] = "jar".toByteArray()

        val result = installer.installFabric("1.20.1", manifest())
        assertEquals("fabric-loader-0.20.1-1.20.1", result.versionId)
        assertEquals(2, result.libraryCount)
        assertEquals(0, result.downloaded)
        assertEquals(2, result.skipped)

        val text = String(fs.files["versions/fabric-loader-0.20.1-1.20.1/fabric-loader-0.20.1-1.20.1.json"]!!)
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
        assertEquals("1.20.1", root["inheritsFrom"]!!.jsonPrimitive.content)
        val libs = root["libraries"]!!.jsonArray
        val firstUrl = libs[0].jsonObject["url"]!!.jsonPrimitive.content
        assertEquals(
            source.loaderMaven("org.ow2.asm", "asm", "9.10.1", null, "jar").first(),
            firstUrl,
        )
        assertTrue(firstUrl.startsWith("mirror/"))
    }

    @Test
    fun `version directory is named fabric-loader-loaderVersion-mcVersion`() {
        val (installer, fs, _) = fixture()
        fs.files["libraries/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar"] = "jar".toByteArray()
        fs.files["libraries/net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar"] = "jar".toByteArray()
        installer.installFabric("1.20.1", manifest())
        assertTrue(fs.files.containsKey("versions/fabric-loader-0.20.1-1.20.1/fabric-loader-0.20.1-1.20.1.json"))
    }

    @Test
    fun `unknown mc version gives readable error not NPE`() {
        val (installer, _, _) = fixture()
        try {
            installer.installFabric("1.99.9", manifest())
            fail("expected LoaderInstallException")
        } catch (e: LoaderInstallException) {
            assertTrue(e.message!!.contains("1.99.9"))
            assertTrue(e.message!!.contains("不在版本清单"))
        }
    }
}
