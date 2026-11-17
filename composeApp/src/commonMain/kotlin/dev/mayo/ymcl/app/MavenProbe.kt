package dev.mayo.ymcl.app

/**
 * Candidate-chain maven reachability probe (p0b fix-neoforge).
 *
 * Tries each candidate URL in order via [LoaderProcess.probe]; ANY 2xx/3xx
 * means reachable. Only when EVERY candidate fails is the source considered
 * unreachable. One log line per candidate, plus a final `reachable via <x>`
 * line — mirrors the format required by the task book. This lives in
 * commonMain (no java.io); file/TTL caching is done by MetaRepository.
 */
object MavenProbe {
    data class Result(
        val reachable: Boolean,
        val via: String = "",
        val tried: List<String> = emptyList(),
        val note: String = "",
    )

    /** Short source label used in logs and "tried sources" messages. */
    fun label(url: String): String = when {
        url.contains("bmclapi") -> "bmclapi"
        url.contains("neoforged.net") || url.contains("minecraftforge.net") -> "official"
        url.contains("127.0.0.1") -> "unreachable"
        else -> url.substringAfter("//").substringBefore("/").ifBlank { url }
    }

    /**
     * Probe [candidates] in order, one attempt each. Returns the first source
     * that answers; if all fail, [Result.reachable] is false and [Result.tried]
     * lists every label attempted (for the unreachable message).
     */
    fun probeCandidates(
        loader: String,
        candidates: List<String>,
        process: LoaderProcess,
        log: (String) -> Unit = ::println,
    ): Result {
        val tried = mutableListOf<String>()
        for (url in candidates) {
            val tag = label(url)
            tried += tag
            val r = process.probe(url, 1)
            val code = r.httpCodes.firstOrNull() ?: 0
            log("$loader probe: $tag/$url -> ${if (r.reachable) "HTTP $code" else "timeout/unreachable"}")
            if (r.reachable) {
                log("$loader reachable via $tag")
                return Result(reachable = true, via = tag, tried = tried.toList())
            }
        }
        val note = unreachableMessage(loader, tried)
        log(note)
        return Result(reachable = false, tried = tried.toList(), note = note)
    }

    /** Unreachable text that names every source that was tried. */
    fun unreachableMessage(loader: String, tried: List<String>): String {
        val sources = tried.distinct().joinToString("、").ifBlank { "（无候选）" }
        return "$loader 官方源不可达（已尝试：$sources），请检查网络或代理"
    }
}
