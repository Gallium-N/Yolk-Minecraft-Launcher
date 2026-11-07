package dev.mayo.ymcl.app

class MetaFetchException(message: String) : Exception(message)

class MetaUnavailableException(message: String) : Exception(message)

data class MetaResult<T>(
    val value: T,
    val offline: Boolean,
    val sourceId: String,
    val fetchedAt: Long,
)

object MetaCachePolicy {
    const val MANIFEST_TTL_MS: Long = 6L * 60 * 60 * 1000
    const val FABRIC_PROFILE_TTL_MS: Long = 24L * 60 * 60 * 1000

    /** Boundary is inclusive: a cache exactly ttl old counts as expired. */
    fun isExpired(cachedAtMillis: Long, nowMillis: Long, ttlMillis: Long): Boolean =
        nowMillis - cachedAtMillis >= ttlMillis
}
