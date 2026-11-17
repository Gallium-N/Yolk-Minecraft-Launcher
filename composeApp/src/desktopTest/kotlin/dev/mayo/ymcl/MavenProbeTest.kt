package dev.mayo.ymcl

import dev.mayo.ymcl.app.BmclapiDownloadSource
import dev.mayo.ymcl.app.InstallerRun
import dev.mayo.ymcl.app.LoaderProcess
import dev.mayo.ymcl.app.MavenProbe
import dev.mayo.ymcl.app.MetaRepository
import dev.mayo.ymcl.app.MetaStore
import dev.mayo.ymcl.app.OfficialDownloadSource
import dev.mayo.ymcl.app.Reachability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * p0b fix-neoforge: forge/neoforge maven must go through BMCLAPI candidates,
 * and reachability must be decided by a candidate chain, not a single host.
 */
class MavenProbeTest {

    private class FakeProcess(
        private val reachableFor: (String) -> Boolean,
    ) : LoaderProcess {
        val probed = mutableListOf<String>()
        override fun probe(url: String, attempts: Int): Reachability {
            probed += url
            val ok = reachableFor(url)
            return Reachability(ok, attempts, List(attempts) { if (ok) 200 else 0 })
        }
        override fun runInstaller(
            jarPath: String, args: List<String>, workDir: String, timeoutMinutes: Long,
        ): InstallerRun = InstallerRun(0, emptyList(), 0L)
    }

    private class MemStore : MetaStore {
        val files = mutableMapOf<String, String>()
        override fun read(path: String) = files[path]
        override fun write(path: String, content: String) { files[path] = content }
        override fun lastModified(path: String) = 0L
        override fun sha1Hex(content: String) = ""
    }

    @Test
    fun `candidate order follows the mirror toggle`() {
        // Mirror ON: BMCLAPI first, official fallback for all four methods.
        for (candidates in listOf(
            BmclapiDownloadSource.forgeInstaller("1.20.1", "47.2.0"),
            BmclapiDownloadSource.neoforgeInstaller("21.1.80"),
            BmclapiDownloadSource.forgeVersionMetadata(),
            BmclapiDownloadSource.neoforgeVersionMetadata(),
            BmclapiDownloadSource.neoforgeProbeUrls(),
        )) {
            assertEquals(2, candidates.size)
            assertTrue(candidates[0].contains("bmclapi2.bangbang93.com/maven/"))
            assertTrue(candidates[1].contains("maven.neoforged.net") || candidates[1].contains("maven.minecraftforge.net"))
        }
        // Mirror OFF: official only (single candidate).
        for (candidates in listOf(
            OfficialDownloadSource.forgeVersionMetadata(),
            OfficialDownloadSource.neoforgeVersionMetadata(),
            OfficialDownloadSource.neoforgeInstaller("21.1.80"),
            OfficialDownloadSource.forgeInstaller("1.20.1", "47.2.0"),
            OfficialDownloadSource.neoforgeProbeUrls(),
        )) {
            assertEquals("official-only candidate count", 1, candidates.size)
            assertTrue(candidates[0].contains("maven.neoforged.net") || candidates[0].contains("maven.minecraftforge.net"))
        }
    }

    @Test
    fun `all candidates failing is the only unreachable verdict, message names sources`() {
        val candidates = listOf(
            "http://bmclapi2.bangbang93.com/maven/x/maven-metadata.xml",
            "http://maven.neoforged.net/releases/x/maven-metadata.xml",
        )
        val process = FakeProcess { false }
        val logs = mutableListOf<String>()
        val result = MavenProbe.probeCandidates("neoforge", candidates, process, logs::add)

        assertFalse(result.reachable)
        assertEquals(listOf("bmclapi", "official"), result.tried)
        assertEquals(2, process.probed.size) // BOTH tried before giving up
        assertTrue(result.note.contains("bmclapi"))
        assertTrue(result.note.contains("official"))
        assertTrue(logs.any { it.contains("neoforge probe: bmclapi") })
        assertTrue(logs.any { it.contains("neoforge probe: official") })
    }

    @Test
    fun `first answering candidate short circuits and reports via`() {
        val candidates = listOf(
            "http://bmclapi2.bangbang93.com/maven/x",
            "http://maven.neoforged.net/releases/x",
        )
        val process = FakeProcess { it.contains("bmclapi") }
        val logs = mutableListOf<String>()
        val result = MavenProbe.probeCandidates("neoforge", candidates, process, logs::add)

        assertTrue(result.reachable)
        assertEquals("bmclapi", result.via)
        assertEquals(1, process.probed.size) // stopped at first success
        assertTrue(logs.any { it.contains("neoforge reachable via bmclapi") })
    }

    @Test
    fun `probe cache is reused within TTL and expires after it`() {
        val store = MemStore()
        var now = 1_000L
        val clock = { now }
        val repo = MetaRepository("/root", BmclapiDownloadSource, store, { throw IllegalStateException() }, clock)

        assertTrue(repo.loadProbe("neoforge", clock) == null)
        val result = MavenProbe.Result(reachable = true, via = "bmclapi", tried = listOf("bmclapi"))
        repo.saveProbe("neoforge", result, clock)

        assertEquals(result, repo.loadProbe("neoforge", clock)) // fresh hit
        now += 5 * 60 * 1000L - 1
        assertEquals(result, repo.loadProbe("neoforge", clock)) // still fresh
        now += 1
        assertTrue("expired at exactly 5 min", repo.loadProbe("neoforge", clock) == null)
    }
}
