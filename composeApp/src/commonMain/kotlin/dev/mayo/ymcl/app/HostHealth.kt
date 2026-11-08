package dev.mayo.ymcl.app

/** Host of a URL, extracted without java.net (pure Kotlin). */
fun hostOf(url: String): String =
    url.substringAfter("://", url).substringBefore("/").substringBefore(":").substringBefore("@")

/**
 * Session-scoped view of CDN health, at two levels:
 *
 *  - **host level** (original): two consecutive failures mark a host as degraded
 *    (moved to the back of candidate lists, never banned permanently). Hosts that
 *    served successful files rank first. [observe] records which final hosts a
 *    candidate has used, so a degraded 302 target penalises that candidate.
 *  - **source level** (new): a mirror such as BMCLAPI may 302 every file to a
 *    DIFFERENT CDN node, so host-level degradation never accumulates — each file
 *    pays a fresh 30s stall on a "new" host. Three consecutive failed file
 *    attempts on the same source (regardless of host) demote the whole source;
 *    six mark it a session-long last resort. A success from that source breaks
 *    the consecutive streak, but demotion / last-resort stay sticky for the
 *    session — re-enabling a flapping source would just re-pay the stall.
 */
class HostHealth(private val log: (String) -> Unit = ::println) {
    private val lock = Any()
    private val consecutiveFailures = mutableMapOf<String, Int>()      // host
    private val degraded = mutableSetOf<String>()                      // host
    private val successes = mutableMapOf<String, Long>()               // host
    private val observed = mutableMapOf<String, MutableSet<String>>()  // candidate host -> final hosts

    private val sourceFailures = mutableMapOf<String, Int>()           // consecutive failures
    private val sourceHosts = mutableMapOf<String, MutableSet<String>>() // distinct failing hosts
    private val demotedSources = mutableSetOf<String>()
    private val lastResortSources = mutableSetOf<String>()

    fun recordFailure(host: String, sourceId: String? = null) {
        val events = mutableListOf<String>()
        synchronized(lock) {
            // ---- host level (unchanged) ----
            val n = (consecutiveFailures[host] ?: 0) + 1
            consecutiveFailures[host] = n
            if (n >= 2 && degraded.add(host)) {
                events += "host=$host degraded after 2 failures"
            }

            // ---- source level (new) ----
            if (sourceId != null) {
                val hosts = sourceHosts.getOrPut(sourceId) { mutableSetOf() }
                hosts.add(host)
                val sf = (sourceFailures[sourceId] ?: 0) + 1
                sourceFailures[sourceId] = sf
                if (sf == 3 && demotedSources.add(sourceId)) {
                    events += "source=$sourceId demoted after 3 failures (hosts=${hosts.size} distinct)"
                }
                if (sf == 6 && lastResortSources.add(sourceId)) {
                    events += "source=$sourceId last-resort after 6 failures (hosts=${hosts.size} distinct)"
                }
            }
        }
        events.forEach { log(it) }
    }

    fun recordSuccess(host: String, sourceId: String? = null) {
        synchronized(lock) {
            consecutiveFailures[host] = 0
            successes[host] = (successes[host] ?: 0L) + 1
            degraded.remove(host)
            if (sourceId != null) {
                // Break the consecutive streak; demotion itself is session-sticky.
                sourceFailures[sourceId] = 0
            }
        }
    }

    fun observe(candidateHost: String, finalHost: String) {
        synchronized(lock) {
            observed.getOrPut(candidateHost) { mutableSetOf() }.add(finalHost)
        }
    }

    fun isDegraded(host: String): Boolean = synchronized(lock) { host in degraded }

    fun successCount(host: String): Long = synchronized(lock) { successes[host] ?: 0L }

    fun isSourceDemoted(sourceId: String): Boolean = synchronized(lock) { sourceId in demotedSources }

    fun isSourceLastResort(sourceId: String): Boolean = synchronized(lock) { sourceId in lastResortSources }

    private fun effectiveHosts(candidate: CandidateUrl): Set<String> = synchronized(lock) {
        val primary = hostOf(candidate.url)
        setOf(primary) + (observed[primary] ?: emptySet())
    }

    /** 0 = healthy, 1 = demoted, 2 = session last resort. */
    private fun sourcePenalty(sourceId: String): Int = synchronized(lock) {
        when {
            sourceId in lastResortSources -> 2
            sourceId in demotedSources -> 1
            else -> 0
        }
    }

    /** Healthy sources first, then successful hosts; degraded hosts / sources last. */
    fun rank(candidates: List<CandidateUrl>): List<CandidateUrl> {
        val scored = candidates.mapIndexed { index, candidate ->
            Triple(index, candidate, effectiveHosts(candidate))
        }
        if (scored.all { (_, _, hosts) -> hosts.all { isDegraded(it) } }) {
            return candidates
        }
        return scored
            .sortedWith(
                compareBy<Triple<Int, CandidateUrl, Set<String>>> { (_, c, _) -> sourcePenalty(c.sourceId) }
                    .thenByDescending { successCount(hostOf(it.second.url)) }
                    .thenBy { (_, _, hosts) -> hosts.count { isDegraded(it) } }
                    .thenBy { it.first },
            )
            .map { it.second }
    }
}
