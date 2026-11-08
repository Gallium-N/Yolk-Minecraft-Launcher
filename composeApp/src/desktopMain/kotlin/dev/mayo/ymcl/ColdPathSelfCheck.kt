package dev.mayo.ymcl

import dev.mayo.ymcl.app.GameDownloader
import dev.mayo.ymcl.app.GameInstaller
import dev.mayo.ymcl.app.LauncherSettings
import dev.mayo.ymcl.app.MetaRepository
import dev.mayo.ymcl.app.OfficialDownloadSource
import dev.mayo.ymcl.domain.InstanceLayout
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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

private const val MC_VERSION = "1.20.1"
private val MARKERS = listOf("Setting user", "LWJGL Version", "Datafixer Bootstrap", "Render thread")

/**
 * P4 step-0 cold-path gap: a fresh shared root with ONLY assets pre-warmed
 * (`cp -al ~/ymcl-test/mc/assets`), so libraries + client jar + version json
 * must go through the REAL network downloader while the pre-warmed assets are
 * skipped. Proves the shared layout also holds on a cold path: the second
 * 1.20.1 does not re-fetch 740MB, only the ~90 missing files.
 */
fun main() {
    val appRoot = appDataDirectory()
    val home = System.getProperty("user.home")
    val gameRoot = System.getProperty("ymcl.cold.root")
        ?: PathUtil.join(home, "ymcl-test", "p4-cold2")
    val instanceId = System.getProperty("ymcl.cold.instance") ?: "Cold-One"
    val shotPath = System.getProperty("ymcl.cold.shot") ?: "build/p4-cold-menu.png"
    val startedAt = System.currentTimeMillis()

    println("COLD start gameRoot=$gameRoot instanceId=$instanceId")
    // -Dymcl.cold.official=true uses libraries.minecraft.net directly. BMCLAPI's
    // /maven/ 302s to rotating CDN nodes that sometimes stall the full 30s; going
    // official keeps the cold-path measurement about layout, not CDN jitter.
    val official = System.getProperty("ymcl.cold.official") == "true"
    val source = if (official) OfficialDownloadSource else LauncherSettings(appRoot).source()
    println("COLD source=${source.id}")
    // now=0 keeps the P3 meta caches fresh -> zero meta network calls, all
    // network traffic below is the game-file downloader under test.
    val meta = MetaRepository(appRoot, source, FileMetaStore(), HttpMetaFetcher()) { 0L }
    val manifest = meta.loadManifest()
    val entry = manifest.value.versions.firstOrNull { it.id == MC_VERSION }
        ?: run { System.err.println("COLD_ABORT: $MC_VERSION 不在清单"); exitProcess(1) }

    val fs = NativeDownloadFileSystem(gameRoot)
    val downloader = GameDownloader(HttpDownloadTransport(), fs, ::println)
    val summary = try {
        GameInstaller(source, downloader, fs, ::println).install(MC_VERSION, entry, instanceId)
    } catch (e: Exception) {
        println("COLD_INSTALL_EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
        exitProcess(1)
    }
    val downloaded = summary.files - summary.skipped
    println(
        "COLD_INSTALL files=${summary.files} downloaded=$downloaded skipped=${summary.skipped} " +
            "downloadedBytes=${summary.downloadedBytes} failures=${summary.failures} " +
            "elapsedMs=${summary.elapsedMs}",
    )
    summary.errors.forEach { println("COLD_ERROR $it") }
    if (summary.failures > 0) exitProcess(1)

    val launch = launchToMenu(appRoot, gameRoot, instanceId, shotPath)
    val elapsedMs = System.currentTimeMillis() - startedAt
    println(
        "COLD_RESULT ok=$launch downloaded=$downloaded skipped=${summary.skipped} " +
            "downloadedBytes=${summary.downloadedBytes} elapsedMs=$elapsedMs",
    )
    if (!launch) exitProcess(1)
}

private fun launchToMenu(appRoot: String, gameRoot: String, instanceId: String, shotPath: String): Boolean {
    val runtimes = JavaRegistry(appRoot).loadOrDetect(detectJavaRuntimes())
    val java = runtimes.firstOrNull { it.major == 21 }
        ?: runtimes.firstOrNull { it.major in 17..21 }
        ?: run { println("COLD no Java 17+ runtime"); return false }
    val engine = HmclEngine(appRoot)
    val runDir = InstanceLayout.resolve(true, false, gameRoot, instanceId).runDir
    File(runDir).mkdirs()
    File(runDir, "options.txt").writeText("onboardAccessibility:false\nnarrator:0\n")
    return when (val result = engine.launch(
        LaunchRequest(instanceId, gameRoot, java, 2048, isolated = true, mcVersion = MC_VERSION),
    )) {
        is LaunchResult.Failed -> {
            println("COLD_LAUNCH_FAILED ${result.message}")
            false
        }
        is LaunchResult.Started -> {
            val hit = AtomicBoolean(false)
            val crashed = AtomicBoolean(false)
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                result.handle.logs.collect { line ->
                    if (MARKERS.any { line.contains(it) }) hit.set(true)
                }
            }
            scope.launch {
                result.handle.exitCode.collect { code -> if (code != 0) crashed.set(true) }
            }
            val deadline = System.currentTimeMillis() + 60_000
            while (!hit.get() && !crashed.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
            }
            Thread.sleep(9000)
            val shot = File(shotPath)
            shot.parentFile?.mkdirs()
            val grim = ProcessBuilder("grim", shot.absolutePath).inheritIO().start().waitFor()
            println("COLD_LAUNCH marker=${hit.get()} crashed=${crashed.get()} grim=$grim shot=$shotPath")
            engine.stop(instanceId)
            engine.awaitExit(instanceId)
            scope.cancel()
            hit.get() && !crashed.get()
        }
    }
}
