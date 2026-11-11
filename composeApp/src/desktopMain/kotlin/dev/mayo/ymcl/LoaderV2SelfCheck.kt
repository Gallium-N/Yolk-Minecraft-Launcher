package dev.mayo.ymcl

import dev.mayo.ymcl.app.DesktopLoaderProcess
import dev.mayo.ymcl.app.GameDownloader
import dev.mayo.ymcl.app.GameInstaller
import dev.mayo.ymcl.app.JavaMatrix
import dev.mayo.ymcl.app.LauncherSettings
import dev.mayo.ymcl.app.LoaderInstaller
import dev.mayo.ymcl.app.MetaRepository
import dev.mayo.ymcl.app.OfficialDownloadSource
import dev.mayo.ymcl.domain.LoaderKind
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

/**
 * Captures only the Minecraft window (hyprctl geometry + grim -g) so other
 * tiled/floating windows do not overlap the evidence. Falls back to a full
 * screen grab when Hyprland/jq is unavailable.
 */
internal fun captureGameWindow(target: File): Int {
    val d = "${'$'}"
    val script = """
        addr=${d}(hyprctl clients -j | jq -r '.[] | select(.class|test("Minecraft";"i")) | .address' | head -1)
        if [ -n "${d}addr" ] && [ "${d}addr" != "null" ]; then
          hyprctl dispatch "hl.dsp.focus({window=\"address:${d}addr\"})" >/dev/null 2>&1
          sleep 1
          geom=${d}(hyprctl activewindow -j | jq -r '(.at[0]|tostring)+","+(.at[1]|tostring)+" "+(.size[0]|tostring)+"x"+(.size[1]|tostring)')
          echo "V2_SHOT window=${d}addr geom=${d}geom"
          grim -g "${d}geom" "${target.absolutePath}"
        else
          grim "${target.absolutePath}"
        fi
    """.trimIndent()
    return ProcessBuilder("bash", "-c", script).inheritIO().start().waitFor()
}

/**
 * P3 v2 real-machine loader check: installs the shared vanilla base, runs the
 * unified LoaderInstaller.install(kind, ...), then launches and waits for the
 * loader marker. -Dymcl.v2.mode=fabric|forge|quilt and -Dymcl.v2.gameRoot=...
 */
fun main() {
    val mode = System.getProperty("ymcl.v2.mode") ?: "fabric"
    val appRoot = appDataDirectory()
    val gameRoot = System.getProperty("ymcl.v2.gameRoot")
        ?: PathUtil.join(System.getProperty("user.home"), "ymcl-test", "p3-v2-$mode")
    val mc = System.getProperty("ymcl.v2.mc") ?: "1.20.1"
    val kind = LoaderKind.fromId(mode) ?: LoaderKind.FABRIC
    val loaderVersion = System.getProperty("ymcl.v2.loaderVersion")
    val instanceId = System.getProperty("ymcl.v2.instanceId") ?: "V2-${kind.displayName}"
    val official = System.getProperty("ymcl.v2.official") == "true"
    val shot = System.getProperty("ymcl.v2.shot") ?: "build/p3-v2-$mode.png"

    println("V2 start mode=$mode gameRoot=$gameRoot mc=$mc kind=${kind.id} loader=$loaderVersion official=$official")
    val source = if (official) OfficialDownloadSource else LauncherSettings(appRoot).source()
    val meta = MetaRepository(appRoot, source, FileMetaStore(), HttpMetaFetcher()) { 0L }
    val manifest = meta.loadManifest().value
    val entry = manifest.versions.firstOrNull { it.id == mc }
        ?: run { println("V2_ABORT: $mc 不在版本清单"); exitProcess(1) }
    val fs = NativeDownloadFileSystem(gameRoot)
    val transport = HttpDownloadTransport()
    val downloader = GameDownloader(transport, fs, ::println)
    val process = DesktopLoaderProcess(::println)

    if (!fs.exists("versions/$mc/$mc.json")) {
        val s = GameInstaller(source, downloader, fs, ::println).install(mc, entry, mc)
        if (s.failures > 0) { println("V2_ABORT base failures=${s.failures}"); exitProcess(1) }
    }
    val installer = LoaderInstaller(source, downloader, fs, meta, ::println, process, gameRoot)
    val result = installer.install(kind, mc, loaderVersion, instanceId, mc, manifest)
    println("V2_LOADER kind=${kind.id} version=${result.loaderVersion} libs=${result.libraryCount} " +
        "downloaded=${result.downloaded} skipped=${result.skipped}")

    val runtimes = JavaRegistry(appRoot).loadOrDetect(detectJavaRuntimes())
    val selection = JavaMatrix.select(mc, runtimes)
    val java = selection.java ?: run { println("V2_ABORT java: ${JavaMatrix.missingMessage(selection)}"); exitProcess(1) }
    println("V2 java path=${java.path} major=${java.major} required=${selection.required}")
    val engine = HmclEngine(appRoot)
    val markers = when (kind) {
        LoaderKind.FORGE -> listOf("forgeclient", "Forge")
        LoaderKind.QUILT -> listOf("Quilt Loader", "quilt")
        LoaderKind.NEOFORGE -> listOf("NeoForge", "neoforge")
        else -> listOf("Fabric Loader", "Loading Minecraft")
    }
    when (val r = engine.launch(
        LaunchRequest(instanceId, gameRoot, java, 2048, isolated = true, mcVersion = mc),
    )) {
        is LaunchResult.Failed -> {
            println("V2_LAUNCH_FAILED ${r.message}")
            exitProcess(1)
        }
        is LaunchResult.Started -> {
            val hit = AtomicBoolean(false)
            val crashed = AtomicBoolean(false)
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                r.handle.logs.collect { line ->
                    if (markers.any { line.contains(it, true) }) {
                        println("V2_MARKER $line")
                        hit.set(true)
                    }
                }
            }
            scope.launch { r.handle.exitCode.collect { code -> if (code != 0) crashed.set(true) } }
            val deadline = System.currentTimeMillis() + 90_000
            while (!hit.get() && !crashed.get() && System.currentTimeMillis() < deadline) Thread.sleep(500)
            Thread.sleep(10_000)
            val f = File(shot)
            f.parentFile?.mkdirs()
            val grim = captureGameWindow(f)
            println("V2_RESULT marker=${hit.get()} crashed=${crashed.get()} grim=$grim")
            engine.stop(instanceId)
            engine.awaitExit(instanceId)
            scope.cancel()
            if (!hit.get() || crashed.get()) exitProcess(1)
            println("V2_OK mode=$mode")
        }
    }
}
