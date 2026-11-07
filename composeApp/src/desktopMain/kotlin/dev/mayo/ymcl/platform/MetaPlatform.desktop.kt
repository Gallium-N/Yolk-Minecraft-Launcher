package dev.mayo.ymcl.platform

import dev.mayo.ymcl.app.MetaFetchException
import dev.mayo.ymcl.app.MetaFetcher
import dev.mayo.ymcl.app.MetaStore
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

class FileMetaStore : MetaStore {
    override fun read(path: String): String? = try {
        readTextAuto(path)
    } catch (_: Exception) {
        null
    }

    override fun write(path: String, content: String) {
        val file = File(PathUtil.toNative(path))
        file.parentFile?.mkdirs()
        // Keep the payload byte-for-byte identical to the fetched body so its sha1
        // still matches the manifest entry; do not normalize line endings here.
        file.writeText(content, Charsets.UTF_8)
    }

    override fun lastModified(path: String): Long = File(PathUtil.toNative(path)).lastModified()

    override fun sha1Hex(content: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
}

/** Connect 20s / read 60s / per-file total 120s; 302 is followed as a normal path. */
class HttpMetaFetcher : MetaFetcher {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun fetch(candidates: List<String>): String {
        val errors = mutableListOf<String>()
        for (url in candidates) {
            var attempt = 0
            var retry = true
            while (retry) {
                attempt++
                try {
                    val request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("User-Agent", "YMCL/0.1 (+local)")
                        .GET()
                        .build()
                    val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    val response = future.orTimeout(120, TimeUnit.SECONDS).get()
                    val code = response.statusCode()
                    if (code in 200..299) return response.body()
                    errors += "$url#$attempt -> HTTP $code"
                    // Retry only transient failures; 404 switches to the next candidate.
                    retry = attempt < 2 && code >= 500
                } catch (e: Exception) {
                    errors += "$url#$attempt -> ${e.javaClass.simpleName}: ${e.message}"
                    retry = attempt < 2
                }
            }
        }
        throw MetaFetchException("所有候选均失败：" + errors.joinToString("; "))
    }
}
