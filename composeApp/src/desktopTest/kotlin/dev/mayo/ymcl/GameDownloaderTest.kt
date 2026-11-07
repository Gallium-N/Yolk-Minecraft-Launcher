package dev.mayo.ymcl

import dev.mayo.ymcl.app.CandidateUrl
import dev.mayo.ymcl.app.DownloadCancelledException
import dev.mayo.ymcl.app.DownloadFailedException
import dev.mayo.ymcl.app.DownloadFileSystem
import dev.mayo.ymcl.app.DownloadPayload
import dev.mayo.ymcl.app.DownloadSpec
import dev.mayo.ymcl.app.DownloadStallException
import dev.mayo.ymcl.app.DownloadTransport
import dev.mayo.ymcl.app.GameDownloader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

class GameDownloaderTest {
    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    private class FakeFs : DownloadFileSystem {
        val files = linkedMapOf<String, ByteArray>()
        var unpacked = 0
        override fun exists(p: String): Boolean = files.containsKey(p)
        override fun size(p: String): Long = files[p]?.size?.toLong() ?: 0L
        override fun sha1Of(p: String): String = digest(files[p] ?: byteArrayOf())
        override fun readAll(p: String): ByteArray = files[p] ?: byteArrayOf()
        override fun writeNew(p: String, data: ByteArray) { files[p] = data }
        override fun append(p: String, data: ByteArray) { files[p] = (files[p] ?: byteArrayOf()) + data }
        override fun delete(p: String) { files.remove(p) }
        override fun move(from: String, to: String) { files[to] = files.remove(from)!! }
        override fun unpackNatives(jarBytes: ByteArray, destDir: String) { unpacked++ }
        private fun digest(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes)
                .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }

    private class FakeTransport(
        private val script: (String, Long) -> DownloadPayload,
    ) : DownloadTransport {
        val calls = mutableListOf<Pair<String, Long>>()
        override fun get(url: String, rangeStart: Long): DownloadPayload {
            calls += url to rangeStart
            return script(url, rangeStart)
        }
    }

    private fun payload(url: String, data: ByteArray, status: Int = 200) =
        DownloadPayload(status, url, null, data.size.toLong(), data)

    private fun spec(vararg candidates: CandidateUrl, sha1: String? = null, size: Long? = null) =
        DownloadSpec("f.bin", candidates.toList(), sha1, size)

    @Test
    fun `sha1 mismatch deletes and redownloads exactly once`() {
        val good = "good-content".toByteArray()
        val bad = "bad".toByteArray()
        var attempt = 0
        val transport = FakeTransport { url, _ ->
            attempt++
            payload(url, if (attempt == 1) bad else good)
        }
        val fs = FakeFs()
        val outcome = GameDownloader(transport, fs) { }
            .download(spec(CandidateUrl("url", "bmclapi"), sha1 = sha1(good)))
        assertTrue(outcome.redownloaded)
        assertArrayEquals(good, fs.files["f.bin"])
    }

    @Test
    fun `part file resumes from its existing length`() {
        val expected = "abcdef".toByteArray()
        val fs = FakeFs()
        fs.files["f.bin.part"] = "abc".toByteArray()
        val transport = FakeTransport { url, _ -> payload(url, "def".toByteArray(), status = 206) }
        GameDownloader(transport, fs) { }
            .download(spec(CandidateUrl("url", "bmclapi"), sha1 = sha1(expected), size = 6L))
        assertEquals(3L, transport.calls[0].second)
        assertArrayEquals(expected, fs.files["f.bin"])
    }

    @Test
    fun `cancellation stops immediately and removes the part file`() {
        val transport = FakeTransport { url, _ -> payload(url, "abc".toByteArray()) }
        val fs = FakeFs()
        fs.files["f.bin.part"] = "abc".toByteArray()
        val downloader = GameDownloader(transport, fs) { }
        downloader.cancel()
        try {
            downloader.download(spec(CandidateUrl("url", "bmclapi")))
            fail("expected DownloadCancelledException")
        } catch (_: DownloadCancelledException) {
        }
        assertFalse(fs.files.containsKey("f.bin.part"))
    }

    @Test
    fun `failed first candidate switches to the next one`() {
        val good = "payload".toByteArray()
        val transport = FakeTransport { url, _ ->
            if (url.contains("bad")) throw RuntimeException("connection reset")
            payload(url, good)
        }
        val fs = FakeFs()
        val outcome = GameDownloader(transport, fs) { }
            .download(spec(CandidateUrl("bad-url", "bmclapi"), CandidateUrl("good-url", "official"), sha1 = sha1(good)))
        assertEquals("official", outcome.sourceId)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun `bmclapi hash only skips when it is a valid 40-char sha1`() {
        val good = "same-bytes".toByteArray()
        val valid = sha1(good)
        val fsValid = FakeFs()
        fsValid.files["f.bin"] = good
        val skipped = GameDownloader(FakeTransport { url, _ ->
            DownloadPayload(200, url, valid, good.size.toLong(), good)
        }, fsValid) { }.download(spec(CandidateUrl("url", "bmclapi")))
        assertTrue(skipped.skipped)

        val fsInvalid = FakeFs()
        fsInvalid.files["f.bin"] = good
        val notSkipped = GameDownloader(FakeTransport { url, _ ->
            DownloadPayload(200, url, "not-a-sha1", good.size.toLong(), good)
        }, fsInvalid) { }.download(spec(CandidateUrl("url", "bmclapi")))
        assertFalse(notSkipped.skipped)
    }

    @Test
    fun `stall logs STALL and switches to next candidate`() {
        val logs = mutableListOf<String>()
        val good = "payload".toByteArray()
        val transport = FakeTransport { url, _ ->
            if (url.contains("slow")) throw DownloadStallException("cdn-slow.example", 30_000L)
            payload(url, good)
        }
        val fs = FakeFs()
        val outcome = GameDownloader(transport, fs) { logs += it }
            .download(spec(CandidateUrl("https://slow.example/f", "bmclapi"),
                CandidateUrl("https://fast.example/f", "official"), sha1 = sha1(good)))
        assertEquals("official", outcome.sourceId)
        assertTrue(logs.any { it.contains("cdn-slow.exampleSTALL@30s") })
        assertTrue(logs.any { it.contains("-> official") })
    }

    @Test
    fun `two stalls from one cdn degrade that host`() {
        val logs = mutableListOf<String>()
        val transport = FakeTransport { _, _ -> throw DownloadStallException("cdn-slow.example", 30_000L) }
        val fs = FakeFs()
        val downloader = GameDownloader(transport, fs) { logs += it }
        repeat(2) {
            try {
                downloader.download(spec(CandidateUrl("https://slow.example/f", "bmclapi")))
            } catch (_: DownloadFailedException) {
            }
        }
        assertTrue(logs.any { it.contains("host=cdn-slow.example degraded after 2 failures") })
    }

    @Test
    fun `all candidates failing reports every candidate reason`() {
        val transport = FakeTransport { _, _ -> throw RuntimeException("connection refused") }
        val fs = FakeFs()
        try {
            GameDownloader(transport, fs) { }.download(
                spec(CandidateUrl("https://a.example/f", "bmclapi"),
                    CandidateUrl("https://b.example/f", "official")),
            )
            fail("expected DownloadFailedException")
        } catch (e: DownloadFailedException) {
            assertTrue(e.message!!.contains("a.example"))
            assertTrue(e.message!!.contains("b.example"))
            assertTrue(e.message!!.contains("connection refused"))
        }
    }

    @Test
    fun `failed file appears in failure list with its name`() {
        val transport = FakeTransport { _, _ -> throw RuntimeException("down") }
        val fs = FakeFs()
        val downloader = GameDownloader(transport, fs) { }
        val failures = mutableListOf<String>()
        listOf("ok-ish.bin", "broken.bin").forEach { name ->
            try {
                downloader.download(DownloadSpec(name, listOf(CandidateUrl("https://x.example/f", "bmclapi"))))
            } catch (e: DownloadFailedException) {
                failures += "$name: ${e.message}"
            }
        }
        assertEquals(2, failures.size)
        assertTrue(failures.any { it.startsWith("broken.bin:") && it.contains("所有候选失败") })
    }
}
