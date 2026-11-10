package dev.mayo.ymcl.app

import dev.mayo.ymcl.platform.PathUtil
import java.io.File
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Host-side [LoaderProcess]: real `curl -sI --max-time 20` reachability probe
 * and `java -jar <installer> ...` execution for Forge / NeoForge.
 */
class DesktopLoaderProcess(
    private val log: (String) -> Unit = ::println,
) : LoaderProcess {

    override fun probe(url: String, attempts: Int): Reachability {
        val codes = mutableListOf<Int>()
        for (i in 1..attempts) {
            val code = try {
                val process = ProcessBuilder(
                    "curl", "-sI", "--max-time", "20",
                    "-o", "/dev/null", "-w", "%{http_code}", url,
                ).redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().readText().trim()
                process.waitFor(25, TimeUnit.SECONDS)
                text.toIntOrNull() ?: 0
            } catch (e: Exception) {
                log("probe attempt $i/$attempts -> ${e.javaClass.simpleName}: ${e.message}")
                0
            }
            codes += code
            log("reachability probe attempt $i/$attempts -> http=$code url=$url")
        }
        return Reachability(codes.any { it in 200..399 }, attempts, codes)
    }

    override fun runInstaller(
        jarPath: String,
        args: List<String>,
        workDir: String,
        timeoutMinutes: Long,
    ): InstallerRun {
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = listOf(javaBin, "-jar", jarPath) + args
        val process = ProcessBuilder(command)
            .directory(File(PathUtil.toNative(workDir)))
            .redirectErrorStream(true)
            .start()
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val reader = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                lines += line
                log("[installer] $line")
            }
        }.apply { isDaemon = true; start() }
        val start = System.currentTimeMillis()
        val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        reader.join(2000)
        val elapsed = System.currentTimeMillis() - start
        if (!finished) {
            process.destroyForcibly()
            throw LoaderInstallException(
                "installer 超过 $timeoutMinutes 分钟未结束（卡在联网拉取），耗时 ${elapsed}ms；阻塞在网络侧",
            )
        }
        return InstallerRun(process.exitValue(), lines.toList(), elapsed)
    }
}
