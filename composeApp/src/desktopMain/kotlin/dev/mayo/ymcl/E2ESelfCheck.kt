package dev.mayo.ymcl

import dev.mayo.ymcl.app.DownloadSource
import dev.mayo.ymcl.app.GameDownloader
import dev.mayo.ymcl.app.GameInstaller
import dev.mayo.ymcl.app.LauncherSettings
import dev.mayo.ymcl.app.LoaderInstaller
import dev.mayo.ymcl.app.MetaRepository
import dev.mayo.ymcl.domain.VersionEntry
import dev.mayo.ymcl.domain.VersionManifest
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

data class StepOutcome(
    val name: String,
    val gameDir: String,
    val files: Int,
    val bytes: Long,
    val elapsedMs: Long,
    val sources: Map<String, Int>,
    val downloadFailures: Int,
    val launchMarker: Boolean,
    val exitCode: Int,
    val ok: Boolean,
    val reason: String,
)

/**
 * P3 step-5 end-to-end self-check. Three brand-new directories (no reuse of
 * mc / mc-fabric / mc-forge, no prewarming from the real game library): cold
 * vanilla install -> Fabric -> Forge, each launched and asserted. Prints one
 * STEP line per phase and one final E2E_RESULT line.
 */
fun main() {
    val appRoot = appDataDirectory()
    val root = PathUtil.join(
        System.getProperty("user.home"), "ymcl-test", "e2e-${System.currentTimeMillis()}",
    )
    val source = LauncherSettings(appRoot).source()
    val meta = MetaRepository(appRoot, source, FileMetaStore(), HttpMetaFetcher(), System::currentTimeMillis)
    val manifest = meta.loadManifest()
    val entry = manifest.value.versions.firstOrNull { it.id == "1.20.1" }
        ?: run { println("E2E_ABORT: 1.20.1 不在版本清单中"); exitProcess(1) }
    println("E2E start root=$root source=${source.id}")

    val outcomes = mutableListOf<StepOutcome>()
    for (step in listOf("vanilla", "fabric", "forge")) {
        val gameDir = PathUtil.join(root, step)
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val outcome = try {
            runStep(appRoot, gameDir, step, source, meta, entry, manifest.value, logs)
        } catch (e: Exception) {
            StepOutcome(
                step, gameDir, 0, 0L, 0L, emptyMap(), 0,
                false, -1, false, "${e.javaClass.simpleName}: ${e.message}",
            )
        }
        outcomes += outcome
        println(
            "STEP ${outcome.name}: dir=${outcome.gameDir} files=${outcome.files} bytes=${outcome.bytes} " +
                "elapsedMs=${outcome.elapsedMs} sources=${outcome.sources} " +
                "downloadFailures=${outcome.downloadFailures} launchMarker=${outcome.launchMarker} " +
                "exitCode=${outcome.exitCode} ok=${outcome.ok} reason=${outcome.reason}",
        )
    }

    val failed = outcomes.firstOrNull { !it.ok }
    val summary = outcomes.joinToString(" ") { "${it.name}=${if (it.ok) "OK" else "FAIL"}" }
    if (failed == null) {
        println("E2E_RESULT $summary overall=PASS")
    } else {
        println("E2E_RESULT $summary overall=FAIL firstFailed=${failed.name} reason=${failed.reason}")
        exitProcess(1)
    }
}

private fun runStep(
    appRoot: String,
    gameDir: String,
    step: String,
    source: DownloadSource,
    meta: MetaRepository,
    entry: VersionEntry,
    manifest: VersionManifest,
    logs: MutableList<String>,
): StepOutcome {
    val fs = NativeDownloadFileSystem(gameDir)
    val downloader = GameDownloader(HttpDownloadTransport(), fs) { logs += it }
    val start = System.currentTimeMillis()
    val summary = GameInstaller(source, downloader, fs) { logs += it }.install("1.20.1", entry)

    var versionId = "1.20.1"
    when (step) {
        "fabric" -> {
            val loader = LoaderInstaller(source, downloader, fs, meta) { logs += it }
                .installFabric("1.20.1", manifest)
            versionId = loader.versionId
        }
        "forge" -> {
            val forge = installForge(gameDir, "1.20.1", source, downloader, fs) { logs += it }
            versionId = forge.versionId
        }
    }

    val launch = launchAndAssert(appRoot, gameDir, versionId, markersFor(step), logs)
    val sources = countSources(logs)
    val elapsed = System.currentTimeMillis() - start
    val ok = launch.hit && !launch.crashed
    val reason = when {
        !ok && launch.crashed -> "游戏进程异常退出（exitCode=${launch.exitCode}）"
        !ok -> "启动标记未命中（${markersFor(step)}）"
        summary.failures > 0 -> "${summary.failures} 个文件下载失败但启动通过"
        else -> "OK"
    }
    return StepOutcome(
        step, gameDir, summary.files, summary.bytes, elapsed, sources, summary.failures,
        launch.hit, launch.exitCode, ok, reason,
    )
}

private data class LaunchAssert(val hit: Boolean, val crashed: Boolean, val exitCode: Int)

private fun markersFor(step: String): List<String> = when (step) {
    "fabric" -> listOf("Fabric Loader", "Loading Minecraft")
    "forge" -> listOf("forgeclient", "Forge")
    else -> listOf("Setting user", "LWJGL Version", "Datafixer Bootstrap", "Render thread")
}

private fun launchAndAssert(
    appRoot: String,
    gameDir: String,
    versionId: String,
    markers: List<String>,
    logs: MutableList<String>,
): LaunchAssert {
    val runtimes = JavaRegistry(appRoot).loadOrDetect(detectJavaRuntimes())
    val java = runtimes.firstOrNull { it.major == 21 }
        ?: runtimes.firstOrNull { it.major in 17..21 }
        ?: return LaunchAssert(false, true, -1)
    val engine = HmclEngine(appRoot)
    return when (val result = engine.launch(LaunchRequest(versionId, gameDir, java, 2048))) {
        is LaunchResult.Failed -> {
            logs += "LAUNCH FAILED: ${result.message}"
            LaunchAssert(false, true, -1)
        }
        is LaunchResult.Started -> {
            val hitMarker = AtomicBoolean(false)
            val crashed = AtomicBoolean(false)
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                result.handle.logs.collect { line ->
                    logs += "[game] $line"
                    if (markers.any { line.contains(it) }) hitMarker.set(true)
                }
            }
            scope.launch {
                result.handle.exitCode.collect { code -> if (code != 0) crashed.set(true) }
            }
            val deadline = System.currentTimeMillis() + 60_000
            while (!hitMarker.get() && !crashed.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
            }
            engine.stop(versionId)
            val code = engine.awaitExit(versionId)
            scope.cancel()
            LaunchAssert(hitMarker.get(), crashed.get(), code)
        }
    }
}

private fun countSources(logs: List<String>): Map<String, Int> {
    val re = Regex("<- (bmclapi|official|cached) ")
    val counts = linkedMapOf<String, Int>()
    logs.forEach { line ->
        re.find(line)?.let { m ->
            val key = m.groupValues[1]
            counts[key] = (counts[key] ?: 0) + 1
        }
    }
    return counts
}
