package dev.mayo.ymcl.app

/** Host of a URL, extracted without java.net (pure Kotlin). */
fun hostOf(url: String): String =
    url.substringAfter("://", url).substringBefore("/").substringBefore(":").substringBefore("@")

/**
 * Session-scoped view of CDN health. Two consecutive failures mark a host as
 * degraded (moved to the back of candidate lists, never banned permanently).
 * Hosts that have served successful files are ranked first.
 *
 * A candidate URL may redirect (302) to a rotating CDN node, so [observe]
 * records which final hosts a candidate has used; a degraded final host then
 * penalises the candidate that keeps reaching it.
 */
class HostHealth(private val log: (String) -> Unit = ::println) {
    private val lock = Any()
    private val consecutiveFailures = mutableMapOf<String, Int>()
    private val degraded = mutableSetOf<String>()
    private val successes = mutableMapOf<String, Long>()
    private val observed = mutableMapOf<String, MutableSet<String>>()

    fun recordFailure(host: String) {
        val newlyDegraded = synchronized(lock) {
            val n = (consecutiveFailures[host] ?: 0) + 1
            consecutiveFailures[host] = n
            n >= 2 && degraded.add(host)
        }
        if (newlyDegraded) log("host=$host degraded after 2 failures")
    }

    fun recordSuccess(host: String) {
        synchronized(lock) {
            consecutiveFailures[host] = 0
            successes[host] = (successes[host] ?: 0L) + 1
            degraded.remove(host)
        }
    }

    fun observe(candidateHost: String, finalHost: String) {
        synchronized(lock) {
            observed.getOrPut(candidateHost) { mutableSetOf() }.add(finalHost)
        }
    }

    fun isDegraded(host: String): Boolean = synchronized(lock) { host in degraded }

    fun successCount(host: String): Long = synchronized(lock) { successes[host] ?: 0L }

    private fun effectiveHosts(candidate: CandidateUrl): Set<String> = synchronized(lock) {
        val primary = hostOf(candidate.url)
        setOf(primary) + (observed[primary] ?: emptySet())
    }

    /** Successful hosts first, degraded last; original order if all hosts are degraded. */
    fun rank(candidates: List<CandidateUrl>): List<CandidateUrl> {
        val scored = candidates.mapIndexed { index, candidate ->
            Triple(index, candidate, effectiveHosts(candidate))
        }
        if (scored.all { (_, _, hosts) -> hosts.all { isDegraded(it) } }) {
            return candidates
        }
        return scored
            .sortedWith(
                compareByDescending<Triple<Int, CandidateUrl, Set<String>>> {
                    successCount(hostOf(it.second.url))
                }
                    .thenBy { (_, _, hosts) -> hosts.count { isDegraded(it) } }
                    .thenBy { it.first },
            )
            .map { it.second }
    }
}
