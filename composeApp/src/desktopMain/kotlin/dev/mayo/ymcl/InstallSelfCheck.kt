package dev.mayo.ymcl

import dev.mayo.ymcl.app.GameDownloader
import dev.mayo.ymcl.app.GameInstaller
import dev.mayo.ymcl.app.LauncherSettings
import dev.mayo.ymcl.app.MetaRepository
import dev.mayo.ymcl.engine.HmclEngine
import dev.mayo.ymcl.engine.LaunchRequest
import dev.mayo.ymcl.engine.LaunchResult
import dev.mayo.ymcl.platform.FileMetaStore
import dev.mayo.ymcl.platform.HttpDownloadTransport
import dev.mayo.ymcl.platform.HttpMetaFetcher
import dev.mayo.ymcl.platform.JavaRegistry
import dev.mayo.ymcl.platform.NativeDownloadFileSystem
import dev.mayo.ymcl.platform.PathUtil
import dev.mayo.ymcl.platform.appDataDirectory
import dev.mayo.ymcl.platform.detectJavaRuntimes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** P3 step-3 self-check: install vanilla 1.20.1 into an isolated dir, then launch it. */
fun main() {
    val appRoot = appDataDirectory()
    val gameDir = System.getProperty("ymcl.install.dir")
        ?: PathUtil.join(System.getProperty("user.home"), "ymcl-test", "mc")
    val shotPath = System.getProperty("ymcl.shot.path") ?: "build/p3-vanilla-menu.png"

    println("P3 install self-check | gameDir=$gameDir")
    val source = LauncherSettings(appRoot).source()
    println("source=${source.id}")

    val meta = MetaRepository(appRoot, source, FileMetaStore(), HttpMetaFetcher(), System::currentTimeMillis)
    val manifest = meta.loadManifest()
    val entry = manifest.value.versions.firstOrNull { it.id == "1.20.1" }
        ?: run { println("ERROR: 1.20.1 not in manifest"); exitProcess(1) }

    val fs = NativeDownloadFileSystem(gameDir)
    val downloader = GameDownloader(HttpDownloadTransport(), fs, ::println)
    Runtime.getRuntime().addShutdownHook(Thread { downloader.cancel() })

    val summary = try {
        GameInstaller(source, downloader, fs, ::println).install("1.20.1", entry)
    } catch (e: Exception) {
        println("INSTALL FAILED: ${e.javaClass.simpleName}: ${e.message}")
        exitProcess(1)
    }
    println("SUMMARY files=${summary.files} bytes=${summary.bytes} " +
        "elapsedMs=${summary.elapsedMs} failures=${summary.failures} " +
        "skipped=${summary.skipped} redownloaded=${summary.redownloaded}")
    if (summary.failures > 0) {
        println("INSTALL FAILED: ${summary.failures} files failed; 停止，不重试")
        exitProcess(1)
    }

    if (!launchGame(appRoot, gameDir, shotPath)) exitProcess(1)
    println("INSTALL+LAUNCH OK")
}

private fun launchGame(appRoot: String, gameDir: String, shotPath: String): Boolean {
    val java = JavaRegistry(appRoot).loadOrDetect(detectJavaRuntimes())
        .firstOrNull { it.major >= 17 }
        ?: run { println("ERROR: no Java 17+ runtime"); return false }
    println("launch java=${java.vendor} ${java.version} path=${java.path}")

    val engine = HmclEngine(appRoot)
    return when (val result = engine.launch(LaunchRequest("1.20.1", gameDir, java, 2048))) {
        is LaunchResult.Failed -> {
            println("LAUNCH FAILED: ${result.message}")
            false
        }
        is LaunchResult.Started -> {
            val handle = result.handle
            val marker = AtomicBoolean(false)
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                handle.logs.collect { line ->
                    println("[game] $line")
                    if (line.contains("Setting user") || line.contains("LWJGL Version")) {
                        marker.set(true)
                    }
                }
            }
            val deadline = System.currentTimeMillis() + 120_000
            while (!marker.get() && System.currentTimeMillis() < deadline) Thread.sleep(500)
            println("launch marker found=${marker.get()}")
            Thread.sleep(6000) // let the main menu render

            val shot = File(shotPath)
            shot.parentFile?.mkdirs()
            val grim = ProcessBuilder("grim", shot.absolutePath).inheritIO().start().waitFor()
            println("grim exit=$grim screenshot=${shot.absolutePath}")

            engine.stop("1.20.1")
            scope.cancel()
            marker.get() && grim == 0
        }
    }
}
