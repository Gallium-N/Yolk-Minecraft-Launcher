package dev.mayo.ymcl.app

/**
 * Modrinth is 300 req/min. On 429 we honour `Retry-After` (seconds) and never
 * retry immediately. Pure so the 429 branch can be tested offline.
 */
object ModrinthRateLimit {
    private const val MIN_WAIT_MS = 1000L
    private const val MAX_WAIT_MS = 60_000L

    /** Seconds from Retry-After, else exponential 1s/2s/4s/8s; always >= 1s. */
    fun backoffMillis(retryAfter: String?, attempt: Int): Long {
        val headerSeconds = retryAfter?.trim()?.toLongOrNull()
        val headerWait = headerSeconds?.times(1000L)
        val fallback = MIN_WAIT_MS shl (attempt - 1).coerceIn(0, 3)
        return (headerWait ?: fallback).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
    }
}
