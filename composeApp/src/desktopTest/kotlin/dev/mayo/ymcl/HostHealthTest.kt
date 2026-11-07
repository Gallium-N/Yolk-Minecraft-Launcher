package dev.mayo.ymcl

import dev.mayo.ymcl.app.CandidateUrl
import dev.mayo.ymcl.app.HostHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostHealthTest {
    @Test
    fun `two consecutive failures degrade host and log it`() {
        val logs = mutableListOf<String>()
        val health = HostHealth { logs += it }
        health.recordFailure("cdn-a.example")
        health.recordFailure("cdn-a.example")
        assertTrue(health.isDegraded("cdn-a.example"))
        assertTrue(logs.any { it.contains("host=cdn-a.example degraded after 2 failures") })
    }

    @Test
    fun `success clears failures and ranks successful host first`() {
        val health = HostHealth { }
        health.recordSuccess("good.example")
        val ranked = health.rank(
            listOf(
                CandidateUrl("https://bad.example/f", "official"),
                CandidateUrl("https://good.example/f", "bmclapi"),
            ),
        )
        assertEquals("https://good.example/f", ranked[0].url)
        assertEquals(1L, health.successCount("good.example"))
    }

    @Test
    fun `degraded final host penalises the candidate that observed it`() {
        val health = HostHealth { }
        health.observe("bmclapi2.bangbang93.com", "cdn-bad.example")
        health.recordFailure("cdn-bad.example")
        health.recordFailure("cdn-bad.example")
        val ranked = health.rank(
            listOf(
                CandidateUrl("https://bmclapi2.bangbang93.com/f", "bmclapi"),
                CandidateUrl("https://libraries.minecraft.net/f", "official"),
            ),
        )
        assertEquals("https://libraries.minecraft.net/f", ranked[0].url)
    }

    @Test
    fun `all hosts degraded returns original order`() {
        val health = HostHealth { }
        health.recordFailure("a.example")
        health.recordFailure("a.example")
        health.recordFailure("b.example")
        health.recordFailure("b.example")
        val candidates = listOf(
            CandidateUrl("https://a.example/f", "x"),
            CandidateUrl("https://b.example/f", "y"),
        )
        assertEquals(candidates, health.rank(candidates))
    }
}
