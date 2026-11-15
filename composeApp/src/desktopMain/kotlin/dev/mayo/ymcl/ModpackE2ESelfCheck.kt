package dev.mayo.ymcl

import dev.mayo.ymcl.app.JavaMatrix
import dev.mayo.ymcl.app.LauncherSettings
import dev.mayo.ymcl.app.MetaRepository
import dev.mayo.ymcl.app.ModpackBackendImpl
import dev.mayo.ymcl.engine.HmclEngine
import dev.mayo.ymcl.engine.LaunchRequest
import dev.mayo.ymcl.engine.LaunchResult
import dev.mayo.ymcl.platform.FileMetaStore
import dev.mayo.ymcl.platform.HttpCfFetcher
import dev.mayo.ymcl.platform.HttpDownloadTransport
import dev.mayo.ymcl.platform.HttpMetaFetcher
import dev.mayo.ymcl.platform.JavaRegistry
import dev.mayo.ymcl.platform.NativeDownloadFileSystem
import dev.mayo.ymcl.platform.PathUtil
import dev.mayo.ymcl.platform.ZipModpackArchive
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
 * P5 wrap-up end-to-end check for a synthetic mrpack that pins Forge / NeoForge.
 * Runs the SAME production path the UI uses:
 *   ModpackBackendImpl.parse() -> install() (base / loader / files / overrides)
 *   -> instance.json -> HmclEngine.launch() -> game-window screenshot.
 *
 * -Dymcl.p5.pack=<file.mrpack>  -Dymcl.p5.root=<gameRoot>
 * -Dymcl.p5.shot=<menu.png>     -Dymcl.p5.expected=<47.2.0|21.1.266>
 */
fun main() {
    val pack = System.getProperty("ymcl.p5.pack")
        ?: run { println("P5_ABORT missing -Dymcl.p5.pack"); exitProcess(1) }
    val gameRoot = System.getProperty("ymcl.p5.root")
        ?: PathUtil.join(System.getProperty("user.home"), "ymcl-test", "p5-e2e")
    val shot = System.getProperty("ymcl.p5.shot") ?: "build/p5-e2e-menu.png"
    val expected = System.getProperty("ymcl.p5.expected") ?: "47.2.0"
    // QA-only: launch an already-installed instance instead of importing again.
    // Lets the final build reproduce the launch evidence when the loader
    // installer mirrors are unreachable (import would fall back to vanilla).
    val launchOnly = System.getProperty("ymcl.p5.launchOnly")

    val appRoot = appDataDirectory()
    val settings = LauncherSettings(appRoot)
    val source = settings.source()
    val meta = MetaRepository(appRoot, source, FileMetaStore(), HttpMetaFetcher()) { 0L }
    val fs = NativeDownloadFileSystem(gameRoot)
    val transport = HttpDownloadTransport()
    val backend = ModpackBackendImpl(
        gameRoot = gameRoot,
        source = source,
        fs = fs,
        meta = meta,
        transport = transport,
        archiveFactory = { ZipModpackArchive(it) },
        cfFetcher = HttpCfFetcher(::println),
        apiKey = { settings.curseForgeApiKey() },
        proxyEnabled = { settings.allowCurseToolsProxy() },
        log = ::println,
    )
    File(gameRoot).mkdirs()
    println("P5 start pack=$pack root=$gameRoot expected=$expected launchOnly=$launchOnly")

    val id: String
    val mcVersion: String
    if (launchOnly != null) {
        // Final-build re-verification: launch an instance imported by an earlier
        // run (loader installer mirrors may be unreachable on a later day).
        id = launchOnly
        mcVersion = System.getProperty("ymcl.p5.mc") ?: planMcFromInstance(gameRoot, id)
        println("P5_LAUNCH_ONLY id=$id mc=$mcVersion (import skipped, real Forge instance on disk)")
        val profile = File(PathUtil.toNative(PathUtil.join(gameRoot, "versions/$id/$id.json")))
        check(profile.isFile) { "launchOnly instance missing profile: ${profile.absolutePath}" }
    } else {
        // ---- parse: proves the mrpack index, dependency loader pin and real hashes.
        val plan = try {
            backend.parse(pack)
        } catch (e: Exception) {
            println("P5_ABORT phase=parse: ${e.message}")
            exitProcess(1)
        }
        println(
            "P5_PLAN name=${plan.name} source=${plan.source.id} mc=${plan.mcVersion} " +
                "loader=${plan.loaderKind}@${plan.loaderVersion} files=${plan.files.size} " +
                "sha1=${plan.files.firstOrNull()?.sha1} size=${plan.files.firstOrNull()?.size}",
        )

        // ---- import: the real base / loader / files / overrides chain.
        val summary = try {
            backend.install(plan) { p -> println("P5_PROGRESS ${p.stage} ${p.doneFiles}/${p.totalFiles} ${p.doneBytes}B") }
        } catch (e: Exception) {
            println("P5_ABORT phase=install(base/loader/files): ${e.message}")
            exitProcess(1)
        }
        println(
            "P5_IMPORT summary: instance=${summary.instanceId} files=${summary.files} " +
                "bytes=${summary.bytes} failures=${summary.failures.size} " +
                "warnings=${summary.warnings.size} cancelled=${summary.cancelled}",
        )
        summary.failures.forEach { println("P5_IMPORT ERROR: $it") }
        if (summary.failures.isNotEmpty()) {
            println("P5_ABORT phase=import-failures")
            exitProcess(1)
        }

        // ---- instance must exist on disk with a self-contained profile + the mod.
        id = summary.instanceId
        mcVersion = plan.mcVersion
        val profile = File(PathUtil.toNative(PathUtil.join(gameRoot, "versions/$id/$id.json")))
        val instanceJson = File(PathUtil.toNative(PathUtil.join(gameRoot, "versions/$id/instance.json")))
        val mod = plan.files.firstOrNull()?.path?.let {
            File(PathUtil.toNative(PathUtil.join(gameRoot, "versions/$id/$it")))
        }
        println("P5_INSTANCE id=$id profile=${profile.isFile} instanceJson=${instanceJson.isFile} mod=${mod?.isFile} modBytes=${mod?.length()}")
        if (!profile.isFile || !instanceJson.isFile || mod?.isFile != true) {
            println("P5_ABORT phase=instance-layout")
            exitProcess(1)
        }
    }

    // A brand-new instance otherwise shows the first-run accessibility dialog
    // covering the main menu; pre-seed the real options key (no game code change).
    val options = File(PathUtil.toNative(PathUtil.join(gameRoot, "versions/$id/options.txt")))
    options.writeText("version:3465\nonboardAccessibility:false\nnarrator:0\n")
    println("P5_OPTIONS ${options.absolutePath} onboardAccessibility=false")

    // ---- Java selection (production JavaMatrix, unchanged).
    val runtimes = JavaRegistry(appRoot).loadOrDetect(detectJavaRuntimes())
    val selection = JavaMatrix.select(mcVersion, runtimes)
    val java = selection.java ?: run {
        println("P5_ABORT phase=java: ${JavaMatrix.missingMessage(selection)}")
        exitProcess(1)
    }
    println("P5_JAVA_SELECTION path=${java.path} major=${java.major} required=${selection.required} mc=$mcVersion")

    // ---- launch: real HmclEngine, collect the game log stream.
    val engine = HmclEngine(appRoot)
    // Only a real Forge/NeoForge launch emits the forgeclient launch target; a
    // vanilla fallback (loader installer unreachable) must NOT satisfy this.
    val loaderMarker = "Launching target 'forgeclient'"
    val versionMarker = expected
    val sawLoader = AtomicBoolean(false)
    val sawVersion = AtomicBoolean(false)
    when (val r = engine.launch(LaunchRequest(id, gameRoot, java, 2048, isolated = true, mcVersion = mcVersion))) {
        is LaunchResult.Failed -> {
            println("P5_ABORT phase=launch: ${r.message}")
            exitProcess(1)
        }
        is LaunchResult.Started -> {
            val crashed = AtomicBoolean(false)
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                r.handle.logs.collect { line ->
                    if (line.contains("forgeclient", true) || line.contains("Launching target", true)) {
                        println("P5_LOG $line")
                        sawLoader.set(true)
                    }
                    if (line.contains(versionMarker, true)) {
                        println("P5_LOG $line")
                        sawVersion.set(true)
                    }
                    if (line.contains("Setting user", true)) println("P5_LOG $line")
                }
            }
            scope.launch { r.handle.exitCode.collect { code -> if (code != 0) crashed.set(true) } }
            val deadline = System.currentTimeMillis() + 120_000
            while (!(sawLoader.get() && sawVersion.get()) && !crashed.get() && System.currentTimeMillis() < deadline) Thread.sleep(500)
            Thread.sleep(12_000)
            val f = File(shot)
            f.parentFile?.mkdirs()
            val grim = captureGameWindow(f)
            val ok = sawLoader.get() && sawVersion.get()
            println("P5_RESULT loaderMarker=$loaderMarker seen=${sawLoader.get()} versionMarker=$versionMarker seen=${sawVersion.get()} crashed=${crashed.get()} grim=$grim shot=${f.absolutePath}")
            engine.stop(id)
            engine.awaitExit(id)
            scope.cancel()
            if (!ok || crashed.get()) {
                println("P5_ABORT phase=launch-marker")
                exitProcess(1)
            }
            println("P5_OK instance=$id expected=$expected")
        }
    }
}

/** Reads the MC version from an already-installed instance's self-contained profile. */
private fun planMcFromInstance(gameRoot: String, id: String): String {
    val json = File(PathUtil.toNative(PathUtil.join(gameRoot, "versions/$id/$id.json"))).readText()
    val m = Regex("\"minecraft\"\\s*:\\s*\"?([0-9.]+)\"?").find(json)
        ?: Regex("\"id\"\\s*:\\s*\"?([0-9.]+)\"?").find(json)
    return m?.groupValues?.get(1) ?: "1.20.1"
}
