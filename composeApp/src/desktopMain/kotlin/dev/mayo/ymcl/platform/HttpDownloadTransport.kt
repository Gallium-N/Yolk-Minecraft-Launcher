package dev.mayo.ymcl.platform

import dev.mayo.ymcl.app.DownloadPayload
import dev.mayo.ymcl.app.DownloadStallException
import dev.mayo.ymcl.app.DownloadTotalTimeoutException
import dev.mayo.ymcl.app.DownloadTransport
import dev.mayo.ymcl.app.hostOf
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Structural fix: the body is buffered in memory (a single MC file is <= ~25MB,
 * 8 concurrent files peak at <= ~200MB). Every individual read runs on a
 * dedicated cached pool and is awaited with its own [stallMs] deadline, so a
 * server that stops sending bytes cannot hold a concurrency slot; [totalMs]
 * caps the whole transfer. No blocking read loop with an after-the-fact check.
 */
class HttpDownloadTransport(
    private val stallMs: Long = 30_000L,
    private val totalMs: Long = 300_000L,
) : DownloadTransport {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    // A dedicated cached pool: nested tasks must never share ForkJoinPool.commonPool,
    // otherwise the outer body task can starve the inner read task.
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ymcl-download").apply { isDaemon = true }
    }

    override fun get(url: String, rangeStart: Long): DownloadPayload {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", "YMCL/0.1 (+local)")
            .GET()
        if (rangeStart > 0) builder.header("Range", "bytes=$rangeStart-")

        val responseFuture = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        val response = try {
            responseFuture.orTimeout(totalMs, TimeUnit.MILLISECONDS).get()
        } catch (e: ExecutionException) {
            responseFuture.cancel(true)
            if (e.cause is TimeoutException) {
                throw DownloadTotalTimeoutException(hostOf(url), totalMs)
            }
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            responseFuture.cancel(true)
            throw DownloadTotalTimeoutException(hostOf(url), totalMs)
        }
        val code = response.statusCode()
        val finalUrl = response.uri().toString()
        val finalHost = hostOf(finalUrl)
        val hash = response.headers().firstValue("x-bmclapi-hash").orElse(null)

        if (code !in 200..299) {
            response.body().close()
            return DownloadPayload(code, finalUrl, hash, 0L, byteArrayOf())
        }
        if (rangeStart > 0 && code == 200) {
            response.body().close()
            return DownloadPayload(code, finalUrl, hash, 0L, byteArrayOf(), rangeHonored = false)
        }

        val bodyFuture = pool.submit(Callable {
            val out = ByteArrayOutputStream()
            response.body().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val readFuture = pool.submit(Callable { input.read(buffer) })
                    val n = try {
                        readFuture.get(stallMs, TimeUnit.MILLISECONDS)
                    } catch (e: Exception) {
                        readFuture.cancel(true)
                        input.close()
                        throw DownloadStallException(finalHost, stallMs)
                    }
                    if (n < 0) break
                    if (n > 0) out.write(buffer, 0, n)
                }
            }
            out.toByteArray()
        })

        val bytes = try {
            bodyFuture.get(totalMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            bodyFuture.cancel(true)
            val cause = e.cause
            if (cause is DownloadStallException) throw cause
            throw DownloadTotalTimeoutException(finalHost, totalMs)
        } catch (e: TimeoutException) {
            bodyFuture.cancel(true)
            throw DownloadTotalTimeoutException(finalHost, totalMs)
        }
        return DownloadPayload(code, finalUrl, hash, bytes.size.toLong(), bytes)
    }
}
