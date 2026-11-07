package dev.mayo.ymcl

import dev.mayo.ymcl.app.CandidateUrl
import dev.mayo.ymcl.app.DownloadFailedException
import dev.mayo.ymcl.app.DownloadSpec
import dev.mayo.ymcl.app.DownloadStallException
import dev.mayo.ymcl.app.GameDownloader
import dev.mayo.ymcl.platform.HttpDownloadTransport
import dev.mayo.ymcl.platform.NativeDownloadFileSystem
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * A-group structural proof. Default mode proves 3 real stalls + candidate
 * failure reasons. With -Dymcl.a3=true it proves the failure list and exits
 * with a non-zero status on purpose.
 */
fun main() {
    val a3 = System.getProperty("ymcl.a3") == "true"
    val server = startHangingServer()
    val stallUrl = "http://127.0.0.1:${server.localPort}/stall"
    val transport = HttpDownloadTransport(stallMs = 30_000L, totalMs = 300_000L)
    val gameDir = Files.createTempDirectory("ymcl-stall").toString()
    val fs = NativeDownloadFileSystem(gameDir)
    val dead = "http://127.0.0.1:9/dead"

    if (a3) {
        println("=== A3: one file with all candidates failing ===")
        val downloader = GameDownloader(HttpDownloadTransport(), fs, ::println)
        val failures = mutableListOf<String>()
        try {
            downloader.download(DownloadSpec("broken.bin", listOf(CandidateUrl(dead, "bmclapi"))))
        } catch (e: DownloadFailedException) {
            failures += "broken.bin: ${e.message}"
        }
        println("SUMMARY failed=${failures.size}")
        failures.forEach { println("FAILED $it") }
        println("A3 EXPECTED_NONZERO_EXIT=1")
        exitProcess(1)
    }

    println("=== A1: 3 consecutive stalls on the same target ===")
    repeat(3) { i ->
        val t = System.currentTimeMillis()
        try {
            transport.get(stallUrl, 0)
            println("attempt ${i + 1}: UNEXPECTED SUCCESS")
        } catch (e: DownloadStallException) {
            val ms = System.currentTimeMillis() - t
            println("attempt ${i + 1}: ${e.label} elapsedMs=$ms host=${e.host} within35s=${ms <= 35_000}")
        }
    }

    println("=== A2: all candidates fail -> error lists every reason ===")
    val downloader = GameDownloader(HttpDownloadTransport(), fs, ::println)
    try {
        downloader.download(
            DownloadSpec(
                "f.bin",
                listOf(CandidateUrl(dead, "bmclapi"), CandidateUrl(dead, "official")),
            ),
        )
        println("UNEXPECTED SUCCESS")
    } catch (e: DownloadFailedException) {
        println("DownloadFailedException: ${e.message}")
    }
    println("A_GROUP_A1_A2_OK")
}

/** Sends HTTP headers with a large Content-Length, then never sends the body. */
private fun startHangingServer(): ServerSocket {
    val server = ServerSocket(0)
    thread(isDaemon = true) {
        while (true) {
            val socket = server.accept()
            thread(isDaemon = true) {
                try {
                    val out = socket.getOutputStream()
                    val headers = "HTTP/1.1 200 OK\r\nContent-Length: 999999\r\n" +
                        "Connection: keep-alive\r\n\r\n"
                    out.write(headers.toByteArray())
                    out.flush()
                    // Intentionally never send the body and never close.
                } catch (_: Exception) {
                }
            }
        }
    }
    return server
}
