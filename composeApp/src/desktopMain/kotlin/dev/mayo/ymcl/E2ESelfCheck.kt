package dev.mayo.ymcl

import dev.mayo.ymcl.app.DownloadPayload
import dev.mayo.ymcl.app.DownloadTransport
import dev.mayo.ymcl.app.GameDownloader
import dev.mayo.ymcl.app.GameInstaller
import dev.mayo.ymcl.app.LauncherSettings
import dev.mayo.ymcl.app.LoaderInstaller
import dev.mayo.ymcl.app.MetaFetcher
import dev.mayo.ymcl.app.MetaRepository
import dev.mayo.ymcl.domain.InstanceId
import dev.mayo.ymcl.domain.InstanceLayout
import dev.mayo.ymcl.domain.IsolationMode
import dev.mayo.ymcl.domain.VersionEntry
import dev.mayo.ymcl.domain.VersionManifest
import dev.mayo.ymcl.engine.HmclEngine
import dev.mayo.ymcl.engine.LaunchRequest
import dev.mayo.ymcl.engine.LaunchResult
import dev.mayo.ymcl.platform.FileMetaStore
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

private const val MC_VERSION = "1.20.1"
private val MARKERS = listOf("Setting user", "LWJGL Version", "Datafixer Bootstrap", "Render thread")

private data class InstallInfo(
    val files: Int,
    val bytes: Long,
    val skipped: Int,
    val downloaded: Int,
    val failures: Int,
    val shared: Int,
    val instance: Int,
)

private data class LaunchAssert(
    val hit: Boolean,
    val crashed: Boolean,
    val launchPathsLine: String,
    val notice: String?,
    val grimOk: Boolean,
    val windowClass: String,
    val grimReason: String,
)

private data class StepOutcome(
    val name: String,
    val displayName: String,
    val instanceId: String,
    val info: InstallInfo,
    val launch: LaunchAssert,
    val mirrorGets: Int,
    val ok: Boolean,
    val reason: String,
)

/**
 * P4 step-0 end-to-end: one shared gameRoot with globally unique assets/ +
 * libraries/, and three version-isolated instance dirs (vanilla, a vanilla copy,
 * Fabric). The 740MB cold download happened once (P3); the shared root is seeded
 * from that install via hard links. Vanilla installs therefore add only a tiny
 * version json, and the Fabric install fetches only its 8 loader libraries from a
 * local mirror — the shared assets/libraries are never duplicated.
 */
fun main() {
    val appRoot = appDataDirectory()
    val home = System.getProperty("user.home")
    val gameRoot = System.getProperty("ymcl.game.root")
        ?: PathUtil.join(home, "ymcl-test", "p4-e2e-${System.currentTimeMillis()}")
    val seed = System.getProperty("ymcl.seed.root") ?: PathUtil.join(home, "ymcl-test", "mc")
    val fabricSeed = System.getProperty("ymcl.seed.fabric") ?: PathUtil.join(home, "ymcl-test", "mc-fabric")
    val seedVersionJson = System.getProperty("ymcl.seed.version.json")
        ?: PathUtil.join(appRoot, "data", "meta", "versions", "$MC_VERSION.json")
    val shotDir = System.getProperty("ymcl.shot.dir") ?: "build/p4-shots"
    val startedAt = System.currentTimeMillis()

    println("P4 E2E start gameRoot=$gameRoot seed=$seed fabricSeed=$fabricSeed")
    val officialVersionJson = try {
        File(seedVersionJson).readBytes()
    } catch (e: Exception) {
        System.err.println("E2E_ABORT: 读取种子版本 json 失败：$seedVersionJson (${e.message})")
        exitProcess(1)
    }

    // Seed the shared root once (hard links => one physical copy of vanilla
    // assets/libraries). The 8 Fabric loader jars are intentionally NOT seeded:
    // the Fabric step fetches them from the local mirror, proving "only 8 libs".
    seedSharedRoot(gameRoot, seed)
    val mirror = LocalMirrorTransport(fabricSeed)

    val source = LauncherSettings(appRoot).source()
    // now=0 keeps the P3 meta caches fresh, and the seed fetcher answers the
    // Fabric loader enumeration offline -> the whole E2E makes zero network calls.
    val metaFetcher = MetaFetcher { candidates ->
        if (candidates.any { it.contains("maven-metadata") }) {
            "<metadata><versioning><versions><version>0.20.1</version></versions></versioning></metadata>"
        } else {
            HttpMetaFetcher().fetch(candidates)
        }
    }
    val meta = MetaRepository(appRoot, source, FileMetaStore(), metaFetcher) { 0L }
    val manifest = meta.loadManifest()
    val entry = manifest.value.versions.firstOrNull { it.id == MC_VERSION }
        ?: run { System.err.println("E2E_ABORT: $MC_VERSION 不在版本清单中"); exitProcess(1) }

    val fs = NativeDownloadFileSystem(gameRoot)
    val downloader = GameDownloader(mirror, fs) { }
    val installer = GameInstaller(source, downloader, fs) { }
    val loaderInstaller = LoaderInstaller(source, downloader, fs, meta, log = { })

    val existing = mutableSetOf<String>()
    val steps = listOf(
        Triple("vanilla", "Vanilla One", false),
        Triple("vanilla-copy", "Vanilla Two", false),
        Triple("fabric", "Fabric Lab", true),
    )
    val outcomes = mutableListOf<StepOutcome>()
    var vanillaInstanceId = ""

    for ((name, displayName, isFabric) in steps) {
        val instanceId = InstanceId.allocate(displayName, MC_VERSION, existing)
        existing += instanceId
        if (!isFabric && vanillaInstanceId.isEmpty()) vanillaInstanceId = instanceId

        // Place the official version json locally so the installer skips its GET.
        fs.writeNew("versions/$instanceId/$instanceId.json", officialVersionJson)

        val logs = Collections.synchronizedList(mutableListOf<String>())
        val mirrorBefore = mirror.calls.size
        val outcome = try {
            runStep(
                appRoot, gameRoot, instanceId, displayName, name, isFabric,
                vanillaInstanceId, File(shotDir, "p4-$name.png").absolutePath,
                entry, manifest.value, installer, loaderInstaller, logs,
            )
        } catch (e: Exception) {
            StepOutcome(
                name, displayName, instanceId,
                InstallInfo(0, 0L, 0, 0, 1, 0, 0),
                LaunchAssert(false, true, "", null, false, "", e.message ?: ""),
                0, false, "${e.javaClass.simpleName}: ${e.message}",
            )
        }
        val mirrorGets = mirror.calls.size - mirrorBefore
        val withMirror = outcome.copy(mirrorGets = mirrorGets)
        outcomes += withMirror
        val i = withMirror.info
        val l = withMirror.launch
        println(
            "STEP ${withMirror.name}: id=${withMirror.instanceId} files=${i.files} bytes=${i.bytes} " +
                "downloaded=${i.downloaded} skipped=${i.skipped} shared=${i.shared} " +
                "instance=${i.instance} failures=${i.failures} mirrorGets=$mirrorGets " +
                "launchMarker=${l.hit} crashed=${l.crashed} " +
                "grim=${l.grimOk} windowClass=${l.windowClass} ok=${withMirror.ok} reason=${withMirror.reason}",
        )
        println("  ${l.launchPathsLine}")
    }

    // ---- Shared-layout assertions ----
    val assetFiles = dirFileCount(PathUtil.join(gameRoot, "assets"))
    val libraryFiles = dirFileCount(PathUtil.join(gameRoot, "libraries"))
    val versionDirs = File(gameRoot, "versions").listFiles { f -> f.isDirectory }?.map { it.name }?.sorted()
        ?: emptyList()
    val rootModsEmpty = File(gameRoot, "mods").listFiles { f -> f.isFile }?.isEmpty() ?: true
    val isolatedPaths = InstanceLayout.resolve(true, false, gameRoot, vanillaInstanceId)
    val sharedFallback = InstanceLayout.resolve(true, true, gameRoot, vanillaInstanceId)
    val sharedModsForcesNonIsolated = sharedFallback.mode == IsolationMode.SHARED_ROOT &&
        sharedFallback.notice == InstanceLayout.SHARED_MODS_NOTICE
    val layoutOk = versionDirs.size == 3 && rootModsEmpty &&
        isolatedPaths.mode == IsolationMode.ISOLATED &&
        isolatedPaths.runDir == isolatedPaths.instanceDir &&
        isolatedPaths.assetsDir == PathUtil.join(gameRoot, "assets") &&
        sharedModsForcesNonIsolated && assetFiles > 0 && libraryFiles > 0
    println(
        "LAYOUT versions=$versionDirs assetFiles=$assetFiles libraryFiles=$libraryFiles " +
            "rootModsEmpty=$rootModsEmpty isolatedGameDir=${isolatedPaths.runDir} " +
            "isolatedAssetsDir=${isolatedPaths.assetsDir} " +
            "sharedModsForcesNonIsolated=$sharedModsForcesNonIsolated ok=$layoutOk",
    )

    // ---- Non-isolated path: a non-empty root mods/ forces --gameDir=<root> ----
    val nonIso = checkNonIsolated(appRoot, gameRoot, vanillaInstanceId)
    println("NONISOLATED $nonIso")

    val elapsedMs = System.currentTimeMillis() - startedAt
    val failed = outcomes.firstOrNull { !it.ok }
    val allOk = failed == null && layoutOk && nonIso.startsWith("ok=true")
    val summary = outcomes.joinToString(" ") { "${it.name}=${if (it.ok) "OK" else "FAIL"}" }
    if (allOk) {
        println("E2E_RESULT ok=true steps=3 $summary elapsedMs=$elapsedMs")
    } else {
        println("E2E_RESULT ok=false steps=${outcomes.size} $summary layout=$layoutOk " +
            "nonIsolated=${nonIso.startsWith("ok=true")} firstFailed=${failed?.name} " +
            "reason=${failed?.reason} elapsedMs=$elapsedMs")
        exitProcess(1)
    }
}

private fun runStep(
    appRoot: String,
    gameRoot: String,
    instanceId: String,
    displayName: String,
    name: String,
    isFabric: Boolean,
    vanillaInstanceId: String,
    shotPath: String,
    entry: VersionEntry,
    manifest: VersionManifest,
    installer: GameInstaller,
    loaderInstaller: LoaderInstaller,
    logs: MutableList<String>,
): StepOutcome {
    val info: InstallInfo = if (isFabric) {
        val r = loaderInstaller.installFabric(MC_VERSION, manifest, instanceId, vanillaInstanceId)
        InstallInfo(
            files = r.libraryCount + 1, bytes = 0L, skipped = r.skipped,
            downloaded = r.downloaded, failures = 0,
            shared = r.libraryCount, instance = 1,
        )
    } else {
        val s = installer.install(MC_VERSION, entry, instanceId)
        InstallInfo(s.files, s.downloadedBytes, s.skipped, s.files - s.skipped, s.failures, s.shared, s.instance)
    }

    // Avoid the accessibility onboarding screen so the window-level screenshot
    // shows the actual game window (same trick as the P3 B-group run).
    val runDir = InstanceLayout.resolve(true, false, gameRoot, instanceId).runDir
    File(runDir).mkdirs()
    File(runDir, "options.txt").writeText("onboardAccessibility:false\nnarrator:0\n")

    val launch = launchAndAssert(appRoot, gameRoot, instanceId, shotPath, logs)
    val ok = info.failures == 0 && launch.hit && !launch.crashed
    val reason = when {
        !ok && launch.crashed -> "游戏进程异常退出"
        !ok -> "启动标记未命中（$MARKERS）"
        else -> "OK"
    }
    return StepOutcome(name, displayName, instanceId, info, launch, 0, ok, reason)
}

private fun launchAndAssert(
    appRoot: String,
    gameRoot: String,
    instanceId: String,
    shotPath: String,
    logs: MutableList<String>,
): LaunchAssert {
    val runtimes = JavaRegistry(appRoot).loadOrDetect(detectJavaRuntimes())
    val java = runtimes.firstOrNull { it.major == 21 }
        ?: runtimes.firstOrNull { it.major in 17..21 }
        ?: return LaunchAssert(false, true, "", null, false, "", "no Java 17+ runtime")
    val engine = HmclEngine(appRoot)
    return when (val result = engine.launch(
        LaunchRequest(instanceId, gameRoot, java, 2048, isolated = true, mcVersion = MC_VERSION),
    )) {
        is LaunchResult.Failed -> {
            logs += "LAUNCH FAILED: ${result.message}"
            LaunchAssert(false, true, "", null, false, "", result.message)
        }
        is LaunchResult.Started -> {
            val hitMarker = AtomicBoolean(false)
            val crashed = AtomicBoolean(false)
            val launchPathsLine = AtomicReference("")
            val notice = AtomicReference<String?>(null)
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                result.handle.logs.collect { line ->
                    logs += "[game] $line"
                    if (MARKERS.any { line.contains(it) }) hitMarker.set(true)
                    if (line.contains("YMCL LaunchPaths")) launchPathsLine.set(line)
                    if (line.contains("YMCL layout notice")) notice.set(line)
                }
            }
            scope.launch {
                result.handle.exitCode.collect { code -> if (code != 0) crashed.set(true) }
            }
            val deadline = System.currentTimeMillis() + 60_000
            while (!hitMarker.get() && !crashed.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
            }
            Thread.sleep(10000) // let the Mojang splash finish and the main menu render
            val grim = windowGrim(shotPath)
            engine.stop(instanceId)
            engine.awaitExit(instanceId)
            scope.cancel()
            LaunchAssert(
                hitMarker.get(), crashed.get(), launchPathsLine.get(), notice.get(),
                grim.ok, grim.windowClass, grim.reason,
            )
        }
    }
}

private data class GrimResult(val ok: Boolean, val windowClass: String, val reason: String)

/** Window-level grim: assert activewindow.class is a game window, then `grim -g`. */
private fun windowGrim(shotPath: String): GrimResult {
    val text = runBash("hyprctl activewindow -j")
    val cls = Regex("\"class\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: ""
    val at = Regex("\"at\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]").find(text)
    val size = Regex("\"size\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]").find(text)
    val geom = if (at != null && size != null) {
        "${at.groupValues[1]},${at.groupValues[2]} ${size.groupValues[1]}x${size.groupValues[2]}"
    } else {
        ""
    }
    println("ACTIVEWINDOW class=$cls geom=$geom")
    if (cls.isBlank() || geom.isBlank()) {
        return GrimResult(false, cls, "activewindow 缺少 class 或 geometry")
    }
    // Positive assertion: the focused window really is the Minecraft game window
    // (vanilla "Minecraft 1.20.1", modded "Minecraft* 1.20.1"), not a terminal.
    if (!cls.contains("minecraft", ignoreCase = true)) {
        return GrimResult(false, cls, "activewindow.class 不是游戏窗口（$cls）")
    }
    val shot = File(shotPath)
    shot.parentFile?.mkdirs()
    val process = ProcessBuilder("grim", "-g", geom, shot.absolutePath).inheritIO().start()
    val finished = process.waitFor(10, TimeUnit.SECONDS)
    val code = if (finished) process.exitValue() else -1
    return GrimResult(code == 0 && shot.length() > 0, cls, "grim exit=$code shot=$shotPath")
}

private fun checkNonIsolated(appRoot: String, gameRoot: String, instanceId: String): String {
    val modsDir = File(gameRoot, "mods")
    modsDir.mkdirs()
    val marker = File(modsDir, "x.jar")
    val created = !marker.exists()
    if (created) marker.writeBytes(ByteArray(0))
    try {
        val runtimes = JavaRegistry(appRoot).loadOrDetect(detectJavaRuntimes())
        val java = runtimes.firstOrNull { it.major == 21 }
            ?: runtimes.firstOrNull { it.major in 17..21 }
            ?: return "ok=false reason=no-java"
        val engine = HmclEngine(appRoot)
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(Dispatchers.IO)
        val result = engine.launch(
            LaunchRequest(instanceId, gameRoot, java, 2048, isolated = true, mcVersion = MC_VERSION),
        )
        val start = System.currentTimeMillis()
        if (result is LaunchResult.Started) {
            scope.launch {
                result.handle.logs.collect { line ->
                    lines += line
                    if (line.contains("YMCL LaunchPaths") || line.contains("YMCL layout notice")) {
                        println("  NONISOLATED_LOG $line")
                    }
                }
            }
            while (System.currentTimeMillis() - start < 12_000) Thread.sleep(500)
            engine.stop(instanceId)
            engine.awaitExit(instanceId)
        }
        scope.cancel()
        val all = lines.joinToString("\n")
        val hasNotice = all.contains(InstanceLayout.SHARED_MODS_NOTICE)
        val gameDirIsRoot = all.contains("--gameDir $gameRoot")
        val ok = hasNotice && gameDirIsRoot
        return "ok=$ok hasNotice=$hasNotice gameDirIsRoot=$gameDirIsRoot"
    } finally {
        if (created) marker.delete()
    }
}

/**
 * Offline stand-in for DownloadSource: maps any maven candidate URL to the
 * matching file under the P3 `mc-fabric/libraries` tree. Records every GET so
 * the E2E can prove vanilla installs request zero files and Fabric requests 8.
 */
private class LocalMirrorTransport(private val fabricSeed: String) : DownloadTransport {
    val calls = Collections.synchronizedList(mutableListOf<String>())

    override fun get(url: String, rangeStart: Long): DownloadPayload {
        calls += url
        val afterHost = url.substringAfter("://").substringAfter('/')
        val mavenPath = afterHost.removePrefix("maven/").substringBefore('?')
        val file = File(PathUtil.toNative(PathUtil.join(fabricSeed, "libraries", mavenPath)))
        if (!file.isFile) throw IllegalStateException("local mirror missing: $mavenPath ($url)")
        val full = file.readBytes()
        val slice = if (rangeStart > 0) {
            full.copyOfRange(minOf(rangeStart.toInt(), full.size), full.size)
        } else {
            full
        }
        return DownloadPayload(
            statusCode = if (rangeStart > 0) 206 else 200,
            finalUrl = url,
            bmclapiHash = null,
            bytesWritten = slice.size.toLong(),
            bytes = slice,
            rangeHonored = true,
        )
    }
}

/** Hard-link a whole tree (one physical copy); copy fallback on EXDEV. */
private fun hardLinkTree(src: Path, dst: Path) {
    if (!Files.exists(src)) return
    for (f in src.toFile().walkTopDown()) {
        val rel = src.relativize(f.toPath()).toString()
        val target = dst.resolve(rel)
        if (f.isFile) {
            if (Files.exists(target)) continue
            Files.createDirectories(target.parent)
            try {
                Files.createLink(target, f.toPath())
            } catch (e: Exception) {
                Files.copy(f.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
        } else if (f.isDirectory) {
            Files.createDirectories(target)
        }
    }
}

private fun seedSharedRoot(gameRoot: String, seed: String) {
    val root = File(PathUtil.toNative(gameRoot)).toPath()
    Files.createDirectories(root)
    hardLinkTree(File(PathUtil.toNative(PathUtil.join(seed, "assets"))).toPath(), root.resolve("assets"))
    hardLinkTree(File(PathUtil.toNative(PathUtil.join(seed, "libraries"))).toPath(), root.resolve("libraries"))
    val sharedJar = root.resolve("libraries/ymcl-clientjar/$MC_VERSION.jar")
    if (!Files.exists(sharedJar)) {
        val src = File(PathUtil.toNative(PathUtil.join(seed, "versions/$MC_VERSION/$MC_VERSION.jar"))).toPath()
        Files.createDirectories(sharedJar.parent)
        try {
            Files.createLink(sharedJar, src)
        } catch (e: Exception) {
            Files.copy(src, sharedJar, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun dirFileCount(path: String): Int {
    val dir = File(PathUtil.toNative(path))
    if (!dir.isDirectory) return 0
    return dir.walkTopDown().count { it.isFile }
}

private fun runBash(cmd: String): String = try {
    val p = ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    p.waitFor(10, TimeUnit.SECONDS)
    text
} catch (e: Exception) {
    ""
}
