package dev.mayo.ymcl

import dev.mayo.ymcl.app.BmclapiDownloadSource
import dev.mayo.ymcl.app.MetaFetchException
import dev.mayo.ymcl.app.MetaFetcher
import dev.mayo.ymcl.app.MetaRepository
import dev.mayo.ymcl.app.MetaStore
import dev.mayo.ymcl.app.MetaUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FabricFallbackTest {
    private val metadataXml = """
        <metadata><versioning><versions>
        <version>0.19.0</version><version>0.20.0</version><version>0.20.1</version>
        </versions></versioning></metadata>
    """.trimIndent()

    private val profileJson = """
        {"id":"fabric-loader-0.20.0-1.20.1","inheritsFrom":"1.20.1",
         "mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
         "libraries":[{"name":"org.ow2.asm:asm:9.5"},{"name":"net.fabricmc:fabric-loader:0.20.0"}]}
    """.trimIndent()

    private class StubStore : MetaStore {
        val files = mutableMapOf<String, String>()
        override fun read(path: String): String? = files[path]
        override fun write(path: String, content: String) { files[path] = content }
        override fun lastModified(path: String): Long = 0L
        override fun sha1Hex(content: String): String = ""
    }

    private fun repository(profileUrlsThatFail: Set<String>): MetaRepository {
        val fetcher = MetaFetcher { candidates ->
            val url = candidates.first()
            when {
                url.contains("maven-metadata.xml") -> metadataXml
                profileUrlsThatFail.any { url.contains(it) } -> throw MetaFetchException("simulated profile failure")
                url.contains("/profile/json") -> profileJson
                else -> throw MetaFetchException("unexpected url: $url")
            }
        }
        return MetaRepository("/root", BmclapiDownloadSource, StubStore(), fetcher, { 0L })
    }

    @Test
    fun `newest loader fails and next newest is selected`() {
        val selection = repository(setOf("/0.20.1/profile/json"))
            .loadFabricProfileWithFallback("1.20.1")
        assertEquals("0.20.0", selection.loaderVersion)
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", selection.profile.mainClass)
        assertEquals(1, selection.attempts.size)
        assertTrue(selection.attempts[0].startsWith("0.20.1:"))
    }

    @Test
    fun `all candidates failing throws MetaUnavailableException`() {
        try {
            repository(setOf("/profile/json")).loadFabricProfileWithFallback("1.20.1")
            fail("expected MetaUnavailableException")
        } catch (e: MetaUnavailableException) {
            assertTrue(e.message!!.contains("全部候选失败"))
        }
    }
}
