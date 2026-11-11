package dev.mayo.ymcl

import dev.mayo.ymcl.app.CandidateUrl
import dev.mayo.ymcl.app.DownloadSpec
import dev.mayo.ymcl.app.GameDownloader
import dev.mayo.ymcl.platform.HttpDownloadTransport
import dev.mayo.ymcl.platform.NativeDownloadFileSystem
import dev.mayo.ymcl.platform.PathUtil
import java.io.File
import kotlin.system.exitProcess

/**
 * P3 v2 acceptance #8: cross-restart resume. Two SEPARATE JVM processes:
 *
 *   -Dymcl.resume.phase=kill   download the real file, persist its first N bytes
 *                              into `<target>.part` + `.part.meta`, then
 *                              Runtime.halt(137) (equivalent to kill -9: no
 *                              shutdown hooks, no cleanup, partial file stays).
 *   -Dymcl.resume.phase=resume a fresh JVM + fresh transport; GameDownloader
 *                              sees the .part, logs `resume from N bytes`,
 *                              issues Range: bytes=N-, appends the rest and
 *                              verifies the final sha1.
 */
fun main() {
    val phase = System.getProperty("ymcl.resume.phase") ?: "kill"
    val gameRoot = System.getProperty("ymcl.resume.root")
        ?: PathUtil.join(System.getProperty("user.home"), "ymcl-test", "p3-resume")
    // Supplied by the shell from the version manifest (no URL literal in src).
    val url = System.getProperty("ymcl.resume.url")
        ?: run { println("RESUME_ABORT missing -Dymcl.resume.url"); exitProcess(1) }
    val sha1 = System.getProperty("ymcl.resume.sha1")
        ?: "0c3ec587af28e5a785c0b4a7b8a30f9a8f78f838"
    val size = System.getProperty("ymcl.resume.size")?.toLongOrNull() ?: 23_028_853L
    val killAt = System.getProperty("ymcl.resume.killAt")?.toIntOrNull() ?: 5_000_000
    val target = "libraries/ymcl-resume/client.jar"
    val fs = NativeDownloadFileSystem(gameRoot)

    val spec = DownloadSpec(
        relativePath = target,
        candidates = listOf(CandidateUrl(url, "official")),
        expectedSha1 = sha1,
        expectedSize = size,
    )

    if (phase == "kill") {
        File(gameRoot).mkdirs()
        val payload = HttpDownloadTransport().get(url, 0)
        check(payload.statusCode in 200..299) { "seed GET failed: ${payload.statusCode}" }
        val prefix = payload.bytes.copyOfRange(0, killAt.coerceAtMost(payload.bytes.size))
        fs.writeNew("$target.part", prefix)
        fs.writeNew(
            "$target.part.meta",
            "size=${prefix.size}\nsha1Prefix=${sha1.take(12)}\n".toByteArray(Charsets.UTF_8),
        )
        println("RESUME_KILL_SIMULATED persisted=${prefix.size} target=$target.part sha1Prefix=${sha1.take(12)}")
        println("RESUME_KILL_SIMULATED halting with 137 (equivalent to kill -9: partial .part survives)")
        System.out.flush()
        // Hard stop: no shutdown hooks, no cleanup — exactly what SIGKILL does
        // to a process that had already flushed N bytes to disk.
        Runtime.getRuntime().halt(137)
    }

    // ---- phase=resume: this is a genuinely separate JVM process ----
    require(fs.exists("$target.part")) { "no .part to resume from; run phase=kill first" }
    println("RESUME_BEFORE partBytes=${fs.size("$target.part")} expectedSha1=$sha1")
    val logs = mutableListOf<String>()
    val downloader = GameDownloader(HttpDownloadTransport(), fs) { line ->
        logs += line
        println(line)
    }
    val outcome = try {
        downloader.download(spec)
    } catch (e: Exception) {
        println("RESUME_FAILED ${e.javaClass.simpleName}: ${e.message}")
        exitProcess(1)
    }
    val finalSha1 = fs.sha1Of(target)
    val finalSize = fs.size(target)
    val resumed = logs.any { it.contains("resume from") }
    println("RESUME_LOG_HAS_RESUME=$resumed")
    println("RESUME_FINAL sha1=$finalSha1 size=$finalSize bytes=${outcome.bytes} redownloaded=${outcome.redownloaded}")
    if (!resumed || finalSha1 != sha1 || finalSize != size) {
        println("RESUME_ABORT")
        exitProcess(1)
    }
    println("RESUME_OK resumed=true finalSha1=$finalSha1 finalSize=$finalSize")
}
