package dev.mayo.ymcl.platform

import dev.mayo.ymcl.app.MetaFetchException
import dev.mayo.ymcl.app.MetaFetcher
import dev.mayo.ymcl.app.ModrinthRateLimit
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

const val MODRINTH_USER_AGENT = "YMCL/0.1 (local)"

/** Raw response, kept injectable so the 429 branch is testable offline. */
data class ModrinthRawResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

private fun httpGet(url: String, log: (String) -> Unit): ModrinthRawResponse {
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    log("MR headers: User-Agent: $MODRINTH_USER_AGENT")
    val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(60))
        .header("User-Agent", MODRINTH_USER_AGENT)
        .GET()
        .build()
    val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
    val response = future.orTimeout(120, TimeUnit.SECONDS).get()
    val headers = response.headers().map().entries.associate { (k, v) ->
        k.lowercase() to v.firstOrNull().orEmpty()
    }
    return ModrinthRawResponse(response.statusCode(), headers, response.body())
}

/**
 * MetaFetcher for Modrinth v2. Sends the mandatory User-Agent, and on 429 reads
 * `Retry-After` and sleeps BEFORE retrying — never an immediate retry.
 */
class ModrinthMetaFetcher(
    private val get: (String) -> ModrinthRawResponse = { httpGet(it, ::println) },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = ::println,
    private val maxAttempts: Int = 3,
) : MetaFetcher {
    override fun fetch(candidates: List<String>): String {
        val errors = mutableListOf<String>()
        for (url in candidates) {
            var attempt = 0
            while (attempt < maxAttempts) {
                attempt++
                val response = try {
                    get(url)
                } catch (e: Exception) {
                    errors += "$url#$attempt -> ${e.javaClass.simpleName}: ${e.message}"
                    break
                }
                when {
                    response.status in 200..299 -> return response.body
                    response.status == 429 -> {
                        val retryAfter = response.headers["retry-after"]
                        val waitMs = ModrinthRateLimit.backoffMillis(retryAfter, attempt)
                        log("MR 429 Retry-After=$retryAfter -> wait ${waitMs}ms before retry (attempt $attempt/$maxAttempts)")
                        if (attempt >= maxAttempts) {
                            errors += "$url#$attempt -> HTTP 429 (retries exhausted)"
                            break
                        }
                        sleep(waitMs)
                    }
                    else -> {
                        errors += "$url#$attempt -> HTTP ${response.status}"
                        break // 404 / 5xx: try the next candidate (only one here)
                    }
                }
            }
        }
        throw MetaFetchException("Modrinth 请求失败：" + errors.joinToString("; "))
    }
}
