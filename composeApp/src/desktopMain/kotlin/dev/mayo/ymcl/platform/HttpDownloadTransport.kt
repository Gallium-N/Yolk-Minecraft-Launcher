package dev.mayo.ymcl.platform

import dev.mayo.ymcl.app.DownloadResponseMeta
import dev.mayo.ymcl.app.DownloadTransport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Connect 20s / read 60s / per-file total 120s; 302 followed, Range honoured. */
class HttpDownloadTransport : DownloadTransport {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun get(url: String, rangeStart: Long, write: (ByteArray) -> Unit): DownloadResponseMeta {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", "YMCL/0.1 (+local)")
            .GET()
        if (rangeStart > 0) builder.header("Range", "bytes=$rangeStart-")

        val response = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            .orTimeout(120, TimeUnit.SECONDS).get()
        val code = response.statusCode()
        val finalUrl = response.uri().toString()
        val hash = response.headers().firstValue("x-bmclapi-hash").orElse(null)

        if (code !in 200..299) {
            response.body().close()
            return DownloadResponseMeta(code, finalUrl, hash, 0L)
        }
        // Server ignored our Range: tell the orchestrator to restart from zero.
        if (rangeStart > 0 && code == 200) {
            response.body().close()
            return DownloadResponseMeta(code, finalUrl, hash, 0L, rangeHonored = false)
        }

        var written = 0L
        response.body().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) {
                    write(buffer.copyOf(n))
                    written += n
                }
            }
        }
        return DownloadResponseMeta(code, finalUrl, hash, written)
    }
}
