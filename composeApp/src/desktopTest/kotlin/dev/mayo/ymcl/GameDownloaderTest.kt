package dev.mayo.ymcl

import dev.mayo.ymcl.app.CandidateUrl
import dev.mayo.ymcl.app.DownloadCancelledException
import dev.mayo.ymcl.app.DownloadDeadlineException
import dev.mayo.ymcl.app.DownloadFailedException
import dev.mayo.ymcl.app.DownloadFileSystem
import dev.mayo.ymcl.app.DownloadResponseMeta
import dev.mayo.ymcl.app.DownloadSpec
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
        override fun sha1Of(p: String): String = sha1(files[p] ?: byteArrayOf())
        override fun readAll(p: String): ByteArray = files[p] ?: byteArrayOf()
        override fun writeNew(p: String, data: ByteArray) { files[p] = data }
        override fun append(p: String, data: ByteArray) { files[p] = (files[p] ?: byteArrayOf()) + data }
        override fun delete(p: String) { files.remove(p) }
        override fun move(from: String, to: String) { files[to] = files.remove(from)!! }
        override fun unpackNatives(jarBytes: ByteArray, destDir: String) { unpacked++ }

        private fun sha1(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes)
                .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }

    private class FakeTransport(
        private val script: (String, Long, (ByteArray) -> Unit) -> DownloadResponseMeta,
    ) : DownloadTransport {
        val calls = mutableListOf<Pair<String, Long>>()
        override fun get(url: String, rangeStart: Long, write: (ByteArray) -> Unit): DownloadResponseMeta {
            calls += url to rangeStart
            return script(url, rangeStart, write)
        }
    }

    private fun spec(vararg candidates: CandidateUrl, sha1: String? = null, size: Long? = null) =
        DownloadSpec("f.bin", candidates.toList(), sha1, size)

    @Test
    fun `sha1 mismatch deletes and redownloads exactly once`() {
        val good = "good-content".toByteArray()
        val bad = "bad".toByteArray()
        var attempt = 0
        val transport = FakeTransport { url, _, write ->
            attempt++
            val data = if (attempt == 1) bad else good
            write(data)
            DownloadResponseMeta(200, url, null, data.size.toLong())
        }
        val fs = FakeFs()
        val downloader = GameDownloader(transport, fs) { }
        val outcome = downloader.download(spec(CandidateUrl("url", "bmclapi"), sha1 = sha1(good)))
        assertTrue(outcome.redownloaded)
        assertArrayEquals(good, fs.files["f.bin"])
    }

    @Test
    fun `part file resumes from its existing length`() {
        val expected = "abcdef".toByteArray()
        val fs = FakeFs()
        fs.files["f.bin.part"] = "abc".toByteArray()
        val transport = FakeTransport { url, _, write ->
            write("def".toByteArray())
            DownloadResponseMeta(206, url, null, 3L)
        }
        val downloader = GameDownloader(transport, fs) { }
        downloader.download(spec(CandidateUrl("url", "bmclapi"), sha1 = sha1(expected), size = 6L))
        assertEquals(3L, transport.calls[0].second)
        assertArrayEquals(expected, fs.files["f.bin"])
    }

    @Test
    fun `cancellation stops immediately and removes the part file`() {
        lateinit var downloader: GameDownloader
        val transport = FakeTransport { url, _, write ->
            write("abc".toByteArray())
            downloader.cancel()
            write("def".toByteArray())
            DownloadResponseMeta(200, url, null, 6L)
        }
        val fs = FakeFs()
        downloader = GameDownloader(transport, fs) { }
        try {
            downloader.download(spec(CandidateUrl("url", "bmclapi")))
            fail("expected DownloadCancelledException")
        } catch (_: DownloadCancelledException) {
            // expected
        }
        assertFalse(fs.files.containsKey("f.bin.part"))
    }

    @Test
    fun `failed first candidate switches to the next one`() {
        val good = "payload".toByteArray()
        val transport = FakeTransport { url, _, write ->
            if (url.contains("bad")) throw RuntimeException("connection reset")
            write(good)
            DownloadResponseMeta(200, url, null, good.size.toLong())
        }
        val fs = FakeFs()
        val downloader = GameDownloader(transport, fs) { }
        val outcome = downloader.download(
            spec(
                CandidateUrl("bad-url", "bmclapi"),
                CandidateUrl("good-url", "official"),
                sha1 = sha1(good),
            ),
        )
        assertEquals("official", outcome.sourceId)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun `bmclapi hash only skips when it is a valid 40-char sha1`() {
        val good = "same-bytes".toByteArray()
        val valid = sha1(good)

        val fsValid = FakeFs()
        fsValid.files["f.bin"] = good
        val transportValid = FakeTransport { url, _, write ->
            write(good)
            DownloadResponseMeta(200, url, valid, good.size.toLong())
        }
        val skipped = GameDownloader(transportValid, fsValid) { }
            .download(spec(CandidateUrl("url", "bmclapi")))
        assertTrue(skipped.skipped)

        val fsInvalid = FakeFs()
        fsInvalid.files["f.bin"] = good
        val transportInvalid = FakeTransport { url, _, write ->
            write(good)
            DownloadResponseMeta(200, url, "not-a-sha1", good.size.toLong())
        }
        val notSkipped = GameDownloader(transportInvalid, fsInvalid) { }
            .download(spec(CandidateUrl("url", "bmclapi")))
        assertFalse(notSkipped.skipped)
    }

    @Test
    fun `body deadline logs TIMEOUT and switches to next candidate`() {
        val logs = mutableListOf<String>()
        val good = "payload".toByteArray()
        val transport = FakeTransport { url, _, write ->
            if (url.contains("slow")) throw DownloadDeadlineException("cdn-slow.example", 90_001L)
            write(good)
            DownloadResponseMeta(200, url, null, good.size.toLong())
        }
        val fs = FakeFs()
        val downloader = GameDownloader(transport, fs) { logs += it }
        val outcome = downloader.download(
            spec(
                CandidateUrl("https://slow.example/f", "bmclapi"),
                CandidateUrl("https://fast.example/f", "official"),
                sha1 = sha1(good),
            ),
        )
        assertEquals("official", outcome.sourceId)
        assertTrue(logs.any { it.contains("cdn-slow.exampleTIMEOUT@90s") })
        assertTrue(logs.any { it.contains("-> official") })
    }

    @Test
    fun `two deadline failures from one cdn degrade that host`() {
        val logs = mutableListOf<String>()
        val transport = FakeTransport { _, _, _ ->
            throw DownloadDeadlineException("cdn-slow.example", 90_001L)
        }
        val fs = FakeFs()
        val downloader = GameDownloader(transport, fs) { logs += it }
        repeat(2) {
            try {
                downloader.download(spec(CandidateUrl("https://slow.example/f", "bmclapi")))
            } catch (_: DownloadFailedException) {
                // no usable candidate, expected
            }
        }
        assertTrue(logs.any { it.contains("host=cdn-slow.example degraded after 2 failures") })
    }
}
