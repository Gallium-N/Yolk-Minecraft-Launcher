package dev.mayo.ymcl.platform

import dev.mayo.ymcl.app.CfFetcher
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Raw GET for CurseForge file metadata. The official API needs the key in the
 * `x-api-key` header (never in the URL); the curse.tools proxy needs none.
 * A non-2xx response is an error so the resolver can fall through levels.
 */
class HttpCfFetcher(
    private val log: (String) -> Unit = ::println,
) : CfFetcher {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun get(url: String, apiKey: String?): String {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", MODRINTH_USER_AGENT)
            .header("Accept", "application/json")
            .GET()
        if (!apiKey.isNullOrBlank()) builder.header("x-api-key", apiKey)
        log("CF GET $url (x-api-key=${if (apiKey.isNullOrBlank()) "none" else "set"})")
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("HTTP ${response.statusCode()} for $url")
        }
        return response.body()
    }
}
