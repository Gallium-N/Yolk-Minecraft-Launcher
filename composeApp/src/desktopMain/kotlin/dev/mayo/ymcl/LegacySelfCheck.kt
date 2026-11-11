package dev.mayo.ymcl

import dev.mayo.ymcl.app.GameDownloader
import dev.mayo.ymcl.app.GameInstaller
import dev.mayo.ymcl.app.LauncherSettings
import dev.mayo.ymcl.app.MetaRepository
import dev.mayo.ymcl.domain.JavaRuntime
import dev.mayo.ymcl.engine.HmclEngine
import dev.mayo.ymcl.engine.LaunchRequest
import dev.mayo.ymcl.engine.LaunchResult
import dev.mayo.ymcl.platform.FileMetaStore
import dev.mayo.ymcl.platform.HttpDownloadTransport
import dev.mayo.ymcl.platform.HttpMetaFetcher
import dev.mayo.ymcl.platform.NativeDownloadFileSystem
import dev.mayo.ymcl.platform.PathUtil
import dev.mayo.ymcl.platform.appDataDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * P3 v2 acceptance #6/#7: install 1.12.2 on a real machine (legacy virtual /
 * map_to_resources assets), then launch with an actual Java 8 and wait for the
 * main menu + sound/texture markers.
 */
fun main() {
    val appRoot = appDataDirectory()
    val gameRoot = System.getProperty("ymcl.v2.legacyRoot")
        ?: PathUtil.join(System.getProperty("user.home"), "ymcl-test", "p3-v2-legacy")
    val mc = System.getProperty("ymcl.v2.legacyMc") ?: "1.12.2"
    val java8 = System.getProperty("ymcl.v2.java8")
        ?: PathUtil.join(System.getProperty("user.dir"), "build", "jdks", "jdk8u512-b09-jre", "bin", "java")
    val shot = System.getProperty("ymcl.v2.shot") ?: "build/p3-v2-legacy.png"

    println("LEGACY start gameRoot=$gameRoot mc=$mc java8=$java8")
    if (!File(java8).isFile) {
        println("LEGACY_ABORT: Java 8 binary missing at $java8")
        exitProcess(1)
    }
    val source = LauncherSettings(appRoot).source()
    val meta = MetaRepository(appRoot, source, FileMetaStore(), HttpMetaFetcher()) { 0L }
    val manifest = meta.loadManifest().value
    val entry = manifest.versions.firstOrNull { it.id == mc }
        ?: run { println("LEGACY_ABORT: $mc 不在版本清单"); exitProcess(1) }
    val fs = NativeDownloadFileSystem(gameRoot)
    val downloader = GameDownloader(HttpDownloadTransport(), fs, ::println)
    val summary = GameInstaller(source, downloader, fs, ::println).install(mc, entry, mc)
    println("LEGACY install files=${summary.files} bytes=${summary.bytes} failures=${summary.failures}")

    val virtualDir = File(gameRoot, "assets/virtual/$mc")
    val resourcesDir = File(gameRoot, "resources")
    val virtualFiles = virtualDir.walkTopDown().count { it.isFile }
    val resourceFiles = if (resourcesDir.isDirectory) resourcesDir.walkTopDown().count { it.isFile } else 0
    println("LEGACY virtual assets/virtual/$mc files=$virtualFiles resources/ files=$resourceFiles")
    val flag = runCatching {
        String(fs.readAll("versions/$mc/instance.json"), Charsets.UTF_8).contains("\"assetsVirtual\":true")
    }.getOrDefault(false)
    println("LEGACY instance.json assetsVirtual=$flag")
    if (summary.failures > 0 || virtualFiles == 0 || !flag) {
        println("LEGACY_ABORT: install did not produce a complete virtual tree")
        exitProcess(1)
    }

    val java = JavaRuntime(
        id = "test-temurin-8",
        vendor = "Temurin",
        major = 8,
        version = "1.8.0_512",
        arch = "x64",
        os = "linux",
        path = java8,
        source = "detected",
    )
    println("LEGACY java path=${java.path} major=${java.major}")
    val engine = HmclEngine(appRoot)
    when (val r = engine.launch(
        LaunchRequest(mc, gameRoot, java, 1024, isolated = true, mcVersion = mc),
    )) {
        is LaunchResult.Failed -> {
            println("LEGACY_LAUNCH_FAILED ${r.message}")
            exitProcess(1)
        }
        is LaunchResult.Started -> {
            val hit = AtomicBoolean(false)
            val crashed = AtomicBoolean(false)
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                r.handle.logs.collect { line ->
                    if (line.contains("Setting user", true) || line.contains("LWJGL Version", true)) {
                        println("LEGACY_MARKER $line")
                        hit.set(true)
                    }
                }
            }
            scope.launch { r.handle.exitCode.collect { code -> if (code != 0) crashed.set(true) } }
            val deadline = System.currentTimeMillis() + 120_000
            while (!hit.get() && !crashed.get() && System.currentTimeMillis() < deadline) Thread.sleep(500)
            Thread.sleep(8_000)
            val f = File(shot)
            f.parentFile?.mkdirs()
            val grim = captureGameWindow(f)
            println("LEGACY_RESULT marker=${hit.get()} crashed=${crashed.get()} grim=$grim")
            engine.stop(mc)
            engine.awaitExit(mc)
            scope.cancel()
            if (!hit.get() || crashed.get()) exitProcess(1)
            println("LEGACY_OK mc=$mc java=8 virtual=$virtualFiles")
        }
    }
}
