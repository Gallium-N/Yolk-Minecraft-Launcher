package dev.mayo.ymcl

import dev.mayo.ymcl.app.CandidateUrl
import dev.mayo.ymcl.app.DownloadSpec
import dev.mayo.ymcl.app.GameDownloader
import dev.mayo.ymcl.app.LauncherSettings
import dev.mayo.ymcl.app.LoaderInstallException
import dev.mayo.ymcl.app.LoaderInstaller
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

data class ForgeResult(
    val versionId: String,
    val installerJar: String,
    val elapsedMs: Long,
    val helpText: String,
)

/** P3 step-4 self-check. -Dymcl.loader.mode=fabric|forge|idempotent|error. */
fun main() {
    val mode = System.getProperty("ymcl.loader.mode") ?: "fabric"
    val appRoot = appDataDirectory()
    val base = PathUtil.join(System.getProperty("user.home"), "ymcl-test")
    val gameDir = PathUtil.join(base, if (mode == "forge") "mc-forge" else "mc-fabric")
    val shotPath = System.getProperty("ymcl.shot.path") ?: "build/p3-loader-menu.png"

    println("P4 loader self-check | mode=$mode gameDir=$gameDir")
    val source = LauncherSettings(appRoot).source()
    println("source=${source.id}")
    val meta = MetaRepository(appRoot, source, FileMetaStore(), HttpMetaFetcher(), System::currentTimeMillis)
    val manifest = meta.loadManifest()
    val fs = NativeDownloadFileSystem(gameDir)
    val downloader = GameDownloader(HttpDownloadTransport(), fs, ::println)
    val installer = LoaderInstaller(source, downloader, fs, meta, ::println)

    when (mode) {
        "fabric" -> {
            val result = installer.installFabric("1.20.1", manifest.value)
            println("FABRIC_RESULT versionId=${result.versionId} loader=${result.loaderVersion} " +
                "libraries=${result.libraryCount} downloaded=${result.downloaded} skipped=${result.skipped} " +
                "sources=${result.sourceCounts} fallback=${result.fallbackAttempts}")
            if (!launchAndShoot(appRoot, gameDir, result.versionId, shotPath,
                    listOf("Fabric Loader", "Loading Minecraft"))) exitProcess(1)
            println("FABRIC_OK")
        }
        "forge" -> {
            val result = installForge(gameDir, "1.20.1", source, downloader, fs, ::println)
            println("FORGE_RESULT versionId=${result.versionId} elapsedMs=${result.elapsedMs} " +
                "installer=${result.installerJar}")
            if (!launchAndShoot(appRoot, gameDir, result.versionId, shotPath,
                    listOf("forgeclient", "Forge"))) exitProcess(1)
            println("FORGE_OK")
        }
        "idempotent" -> {
            val result = installer.installFabric("1.20.1", manifest.value)
            println("IDEMPOTENT libraries=${result.libraryCount} downloaded=${result.downloaded} " +
                "skipped=${result.skipped} sources=${result.sourceCounts}")
            if (result.downloaded != 0) exitProcess(1)
            println("IDEMPOTENT_OK")
        }
        "error" -> {
            try {
                installer.installFabric("1.99.9", manifest.value)
                println("ERROR_PATH_UNEXPECTED_SUCCESS")
                exitProcess(1)
            } catch (e: LoaderInstallException) {
                println("ERROR_PATH_OK: ${e.message}")
            }
        }
        else -> {
            println("unknown mode=$mode")
            exitProcess(1)
        }
    }
}

private fun installForge(
    gameDir: String,
    mcVersion: String,
    source: dev.mayo.ymcl.app.DownloadSource,
    downloader: GameDownloader,
    fs: dev.mayo.ymcl.app.DownloadFileSystem,
    log: (String) -> Unit,
): ForgeResult {
    val forgeVersion = "1.20.1-47.4.23"
    val javaBin = PathUtil.join(System.getProperty("java.home"), "bin", "java")
    val jarRel = "installer/forge-$forgeVersion-installer.jar"
    val candidates = source.loaderMaven("net.minecraftforge", "forge", forgeVersion, "installer", "jar")
    log("forge installer candidates=${candidates.size} (via DownloadSource.loaderMaven)")
    downloader.download(
        DownloadSpec(jarRel, candidates.map { CandidateUrl(it, source.id) }),
    )
    val jarAbs = File(PathUtil.toNative(PathUtil.join(gameDir, jarRel))).absolutePath
    log("forge installer jar=$jarAbs")

    // Real parameters come from the installer itself, not from memory.
    val helpProcess = ProcessBuilder(javaBin, "-jar", jarAbs, "--help")
        .redirectErrorStream(true).start()
    val helpText = helpProcess.inputStream.bufferedReader().readText()
    helpProcess.waitFor()
    println("FORGE_HELP_BEGIN\n$helpText\nFORGE_HELP_END")

    // Forge installer refuses to run without a vanilla launcher profile.
    if (!fs.exists("launcher_profiles.json")) {
        val profiles = """{"profiles":{"ymcl":{"name":"$mcVersion","lastVersionId":"$mcVersion","type":"custom"}}}"""
        fs.writeNew("launcher_profiles.json", profiles.toByteArray(Charsets.UTF_8))
        log("forge: 创建 launcher_profiles.json（installer 前置条件）")
    }

    val start = System.currentTimeMillis()
    val process = ProcessBuilder(javaBin, "-jar", jarAbs, "--installClient", PathUtil.toNative(gameDir))
        .directory(File(PathUtil.toNative(gameDir)))
        .redirectErrorStream(true)
        .start()
    val reader = Thread {
        process.inputStream.bufferedReader().forEachLine { println("[forge] $it") }
    }.apply { isDaemon = true; start() }
    val finished = process.waitFor(10, TimeUnit.MINUTES)
    reader.join(2000)
    val elapsed = System.currentTimeMillis() - start
    if (!finished) {
        process.destroyForcibly()
        throw LoaderInstallException("Forge installer 超过 10 分钟未结束（卡在联网拉取），耗时 ${elapsed}ms；阻塞在网络侧")
    }
    log("forge installer exit=${process.exitValue()} elapsedMs=$elapsed")
    if (process.exitValue() != 0) {
        throw LoaderInstallException("Forge installer 退出码 ${process.exitValue()}，耗时 ${elapsed}ms")
    }

    val versions = File(PathUtil.toNative(PathUtil.join(gameDir, "versions")))
    val versionId = versions.listFiles { f -> f.isDirectory && f.name.contains("forge", ignoreCase = true) }
        ?.minByOrNull { it.name }?.name
        ?: throw LoaderInstallException("Forge installer 未生成版本目录：$versions")

    // The installer emits an inheriting profile (29 Forge libs + inheritsFrom 1.20.1).
    // HMCL's module layer then resolves the vanilla jar twice (JPMS conflict), so
    // turn it into a self-contained profile like P2's working Forge install:
    // vanilla libs + Forge libs, no inheritsFrom, jar points at <versionId>.
    val versionRel = "versions/$versionId/$versionId.json"
    val forgeJson = Json.parseToJsonElement(String(fs.readAll(versionRel), Charsets.UTF_8)).jsonObject
    val vanillaJson = Json.parseToJsonElement(
        String(fs.readAll("versions/$mcVersion/$mcVersion.json"), Charsets.UTF_8),
    ).jsonObject
    val mergedLibs = (vanillaJson["libraries"]!!.jsonArray + forgeJson["libraries"]!!.jsonArray)
        .distinctBy { it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty() }
    val vanillaArgs = vanillaJson["arguments"]?.jsonObject
    val forgeArgs = forgeJson["arguments"]?.jsonObject
    fun mergeArgs(key: String): JsonArray = JsonArray(
        (vanillaArgs?.get(key)?.jsonArray ?: emptyList()) + (forgeArgs?.get(key)?.jsonArray ?: emptyList()),
    )
    val patched = JsonObject(forgeJson.toMutableMap().apply {
        remove("inheritsFrom")
        put("jar", JsonPrimitive(versionId))
        put("libraries", JsonArray(mergedLibs))
        put("arguments", JsonObject(mapOf("game" to mergeArgs("game"), "jvm" to mergeArgs("jvm"))))
        listOf("assetIndex", "assets", "javaVersion", "minimumLauncherVersion", "complianceLevel", "downloads")
            .forEach { key -> vanillaJson[key]?.let { put(key, it) } }
    })
    fs.writeNew(versionRel, patched.toString().toByteArray(Charsets.UTF_8))

    val srcJar = File(PathUtil.toNative(PathUtil.join(gameDir, "versions/$mcVersion/$mcVersion.jar"))).toPath()
    val dstJar = File(PathUtil.toNative(PathUtil.join(gameDir, "versions/$versionId/$versionId.jar"))).toPath()
    if (!Files.exists(dstJar)) Files.createLink(dstJar, srcJar)
    log("forge: 自包含 profile libraries=${mergedLibs.size} jar=$versionId（硬链接原版 jar）")
    return ForgeResult(versionId, jarAbs, elapsed, helpText)
}

private fun launchAndShoot(
    appRoot: String,
    gameDir: String,
    versionId: String,
    shotPath: String,
    markers: List<String>,
): Boolean {
    val runtimes = JavaRegistry(appRoot).loadOrDetect(detectJavaRuntimes())
    val java = runtimes.firstOrNull { it.major == 21 }
        ?: runtimes.firstOrNull { it.major in 17..21 }
        ?: run { println("ERROR: no Java 21 runtime"); return false }
    println("launch java=${java.vendor} ${java.version} versionId=$versionId")

    val engine = HmclEngine(appRoot)
    return when (val result = engine.launch(LaunchRequest(versionId, gameDir, java, 2048))) {
        is LaunchResult.Failed -> {
            println("LAUNCH FAILED: ${result.message}")
            false
        }
        is LaunchResult.Started -> {
            val marker = AtomicBoolean(false)
            val crashed = AtomicBoolean(false)
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                result.handle.logs.collect { line ->
                    println("[game] $line")
                    if (markers.any { line.contains(it) }) marker.set(true)
                }
            }
            scope.launch {
                result.handle.exitCode.collect { code -> crashed.set(code != 0) }
            }
            val deadline = System.currentTimeMillis() + 60_000
            while (!marker.get() && !crashed.get() && System.currentTimeMillis() < deadline) Thread.sleep(500)
            println("launch marker found=${marker.get()} markers=$markers crashed=${crashed.get()}")
            if (crashed.get()) {
                println("LAUNCH CRASHED: 游戏进程异常退出，截图无效")
                engine.stop(versionId)
                scope.cancel()
                false
            } else {
                Thread.sleep(12_000) // Forge needs longer to reach the main menu

                val shot = File(shotPath)
                shot.parentFile?.mkdirs()
                val grim = ProcessBuilder("grim", shot.absolutePath).inheritIO().start().waitFor()
                println("grim exit=$grim screenshot=${shot.absolutePath}")
                engine.stop(versionId)
                scope.cancel()
                grim == 0 && shot.length() > 0
            }
        }
    }
}
