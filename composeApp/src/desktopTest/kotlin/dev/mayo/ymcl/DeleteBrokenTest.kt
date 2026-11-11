package dev.mayo.ymcl

import dev.mayo.ymcl.app.DownloadTransport
import dev.mayo.ymcl.app.MetaFetcher
import dev.mayo.ymcl.app.MetaRepository
import dev.mayo.ymcl.app.MetaStore
import dev.mayo.ymcl.app.NewInstanceBackendImpl
import dev.mayo.ymcl.app.OfficialDownloadSource
import dev.mayo.ymcl.app.UnavailableLoaderProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Deleting unfinished installs: only `broken` dirs go, everything else stays. */
class DeleteBrokenTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun backend(): Pair<NewInstanceBackendImpl, File> {
        val root = tmp.newFolder("game")
        val store = object : MetaStore {
            override fun read(path: String): String? = null
            override fun write(path: String, content: String) {}
            override fun lastModified(path: String): Long = 0L
            override fun sha1Hex(content: String): String = ""
        }
        val fetcher = MetaFetcher { throw IllegalStateException("no network in test") }
        val transport = DownloadTransport { _, _ -> throw IllegalStateException("no network in test") }
        val meta = MetaRepository(tmp.newFolder("app").absolutePath, OfficialDownloadSource, store, fetcher) { 0L }
        return NewInstanceBackendImpl(
            gameRoot = root.absolutePath,
            source = OfficialDownloadSource,
            meta = meta,
            transport = transport,
            process = UnavailableLoaderProcess,
            log = { },
        ) to root
    }

    private fun instance(root: File, id: String, json: String) {
        val dir = File(root, "versions/$id")
        dir.mkdirs()
        File(dir, "instance.json").writeText(json)
        File(dir, "$id.jar").writeText("partial download") // broken installs hold files
    }

    private fun broken(id: String) =
        """{"schemaVersion":1,"id":"$id","displayName":"$id 未完成","mcVersion":"1.20.1","installState":"broken"}"""
    private fun healthy(id: String) =
        """{"schemaVersion":1,"id":"$id","displayName":"$id","mcVersion":"1.20.1"}"""
    private fun base(id: String) =
        """{"schemaVersion":1,"id":"$id","mcVersion":"1.20.1","role":"base"}"""

    @Test
    fun `brokenInstalls lists only broken instances with their paths`() {
        val (b, root) = backend()
        instance(root, "Broken-A", broken("Broken-A"))
        instance(root, "Healthy", healthy("Healthy"))
        instance(root, "1.20.1", base("1.20.1"))
        instance(root, "Broken-B", broken("Broken-B"))

        val list = b.brokenInstalls()
        assertEquals(listOf("Broken-A", "Broken-B"), list.map { it.id })
        assertEquals("Broken-A 未完成", list[0].displayName)
        assertTrue(list[0].path.endsWith("versions/Broken-A"))
    }

    @Test
    fun `deleteBrokenInstall removes the whole broken dir and its files`() {
        val (b, root) = backend()
        instance(root, "Broken-A", broken("Broken-A"))
        val dir = File(root, "versions/Broken-A")

        assertTrue(b.deleteBrokenInstall("Broken-A"))
        assertFalse(dir.exists())
        assertTrue(b.brokenInstalls().isEmpty())
    }

    @Test
    fun `deleteBrokenInstall refuses healthy base and missing instances`() {
        val (b, root) = backend()
        instance(root, "Healthy", healthy("Healthy"))
        instance(root, "1.20.1", base("1.20.1"))
        instance(root, "Broken-Base", """{"schemaVersion":1,"installState":"broken","role":"base"}""")

        assertFalse(b.deleteBrokenInstall("Healthy"))
        assertFalse(b.deleteBrokenInstall("1.20.1"))
        assertFalse(b.deleteBrokenInstall("Broken-Base"))
        assertFalse(b.deleteBrokenInstall("Does-Not-Exist"))
        assertTrue(File(root, "versions/Healthy").exists())
        assertTrue(File(root, "versions/1.20.1").exists())
        assertTrue(File(root, "versions/Broken-Base").exists())
    }

    @Test
    fun `deleteBrokenInstall refuses path traversal absolute and drive ids`() {
        val (b, root) = backend()
        instance(root, "Broken-A", broken("Broken-A"))
        // Even if a sibling looked deletable, these ids must never escape versions/.
        listOf("../assets", "..", "a/b", "a\\b", "/etc", "C:\\tmp", "", ".").forEach { id ->
            assertFalse("should refuse '$id'", b.deleteBrokenInstall(id))
        }
        assertTrue(File(root, "versions/Broken-A").exists())
    }

    @Test
    fun `broken flag case and unknown fields are tolerated`() {
        val (b, root) = backend()
        instance(
            root, "Extra", """{"schemaVersion":1,"installState":"broken","futureField":123,"displayName":"Extra"}""",
        )
        assertEquals(listOf("Extra"), b.brokenInstalls().map { it.id })
        assertTrue(b.deleteBrokenInstall("Extra"))
    }
}
