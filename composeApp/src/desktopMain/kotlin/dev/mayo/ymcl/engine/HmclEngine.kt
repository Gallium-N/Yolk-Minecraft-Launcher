package dev.mayo.ymcl.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.mayo.ymcl.domain.Instance
import dev.mayo.ymcl.domain.InstanceStatus
import dev.mayo.ymcl.domain.JavaRuntime
import dev.mayo.ymcl.domain.Loader
import dev.mayo.ymcl.domain.LoaderKind
import dev.mayo.ymcl.domain.ModEntry
import dev.mayo.ymcl.platform.JavaRegistry
import dev.mayo.ymcl.platform.appDataDirectory
import dev.mayo.ymcl.platform.detectJavaRuntimes
import dev.mayo.ymcl.platform.nowMillis
import dev.mayo.ymcl.platform.readTextAuto
import dev.mayo.ymcl.platform.writeTextUtf8Lf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jackhuang.hmcl.auth.AuthInfo
import org.jackhuang.hmcl.game.AssetIndex
import org.jackhuang.hmcl.game.DefaultGameRepositoryLayout
import org.jackhuang.hmcl.game.GameComponentAnalyzer
import org.jackhuang.hmcl.game.GameInstance
import org.jackhuang.hmcl.game.GameInstanceID
import org.jackhuang.hmcl.game.GameInstanceManifest
import org.jackhuang.hmcl.game.GameRepository
import org.jackhuang.hmcl.util.versioning.GameVersionNumber
import org.jackhuang.hmcl.game.LaunchOptions
import org.jackhuang.hmcl.java.JavaInfo
import org.jackhuang.hmcl.java.JavaRuntime as HmclJavaRuntime
import org.jackhuang.hmcl.launch.DefaultLauncher
import org.jackhuang.hmcl.launch.ProcessListener
import org.jackhuang.hmcl.util.gson.JsonUtils
import org.jackhuang.hmcl.util.platform.Platform
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HMCLCore-backed engine. All HMCL types stay in this desktop class; UI/app only
 * see domain models and the GameEngine interface.
 */
class HmclEngine(
    private val appRoot: String = appDataDirectory(),
) : GameEngine {
    private val registry = JavaRegistry(appRoot)
    private val running = ConcurrentHashMap<String, Process>()
    private val runningIds = ConcurrentHashMap.newKeySet<String>()
    private val exitCodes = ConcurrentHashMap<String, Int>()
    private val lastLaunchTimes = ConcurrentHashMap<String, Long>()
    private val historyFile = File(File(appRoot, "data"), "launch-history.json")

    init {
        loadHistory()
    }

    override fun detectJava(): List<JavaRuntime> =
        registry.loadOrDetect(detectJavaRuntimes())

    override fun listInstances(gameDir: String): List<Instance> {
        val base = File(gameDir)
        val versions = File(base, "versions")
        if (!versions.isDirectory) return emptyList()

        val mods = File(base, "mods").listFiles { file -> file.isFile && file.extension == "jar" }
            .orEmpty()
            .map { ModEntry(fileName = it.name) }

        return versions.listFiles { file -> file.isDirectory }.orEmpty().mapNotNull { dir ->
            try {
                val id = dir.name
                val (_, resolved) = loadManifests(base.toPath(), id)
                val mcVersion = findMcVersion(id, resolved)
                val loader = detectLoader(id, resolved)
                Instance(
                    id = id,
                    displayName = id,
                    mcVersion = mcVersion,
                    loader = loader,
                    mods = mods,
                    totalPlayTimeMs = 0L,
                    lastLaunchedAt = lastLaunchTimes[id],
                    status = if (id in runningIds) InstanceStatus.RUNNING else InstanceStatus.READY,
                )
            } catch (e: Exception) {
                println("YMCL versions: skip $dir: ${e.message}")
                null
            }
        }.sortedWith(compareByDescending<Instance> { it.lastLaunchedAt ?: Long.MIN_VALUE }.thenBy { it.id })
    }

    override fun launch(request: LaunchRequest): LaunchResult {
        val base = File(request.gameDir)
        return try {
            val (raw, resolved) = loadManifests(base.toPath(), request.instanceId)
            val version = findMcVersion(request.instanceId, resolved)
            val required = requiredJavaMajor(version)
            if (request.java.major in 1 until required) {
                return LaunchResult.Failed(
                    "Java 版本不匹配：$version 需要 Java $required 或更高，当前是 " +
                        "${request.java.vendor} ${request.java.version}。请到设置 → 游戏切换运行时。",
                )
            }

            val javaInfo = JavaInfo(Platform.CURRENT_PLATFORM, request.java.version, request.java.vendor)
            val javaRuntime = HmclJavaRuntime.of(Path.of(request.java.path), javaInfo, false)
            val auth = AuthInfo(
                "YMCL",
                UUID.nameUUIDFromBytes("ymcl-offline".toByteArray()),
                "0",
                AuthInfo.USER_TYPE_MSA,
                "{}",
            )

            val options = LaunchOptions.Builder()
                .setInstanceId(resolved.id())
                .setGameDir(base.toPath())
                .setJava(javaRuntime)
                .setMaxMemory(request.xmxMb)
                .setWidth(854)
                .setHeight(480)
                .setDaemon(true)
                .setNoGeneratedJVMArgs(false)
                .create()

            val logFile = File(File(File(appRoot, "data"), "logs"), "${safeName(request.instanceId)}-${nowMillis()}.log")
            logFile.parentFile?.mkdirs()
            val writer = logFile.bufferedWriter(StandardCharsets.UTF_8)
            val logs = MutableSharedFlow<String>(
                replay = 512,
                extraBufferCapacity = 4096,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            val exits = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 1)
            val gameInstance = HmclGameInstance(base.toPath(), resolved, raw, version)

            val finished = AtomicBoolean(false)
            val listener = object : ProcessListener {
                override fun onLog(log: String, isErrorStream: Boolean) {
                    synchronized(writer) {
                        try {
                            writer.write(log)
                            writer.newLine()
                            writer.flush()
                        } catch (_: IOException) {
                        }
                    }
                    logs.tryEmit(log)
                }

                override fun onExit(exitCode: Int, exitType: ProcessListener.ExitType) {
                    finish(exitCode)
                }

                private fun finish(exitCode: Int) {
                    if (!finished.compareAndSet(false, true)) return
                    synchronized(writer) {
                        try {
                            writer.flush()
                            writer.close()
                        } catch (_: IOException) {
                        }
                    }
                    running.remove(request.instanceId)
                    runningIds.remove(request.instanceId)
                    exitCodes[request.instanceId] = exitCode
                    exits.tryEmit(exitCode)
                }
            }

            val launcher = DefaultLauncher(gameInstance, resolved, auth, options, listener, true)
            val managed = launcher.launch()
            running[request.instanceId] = managed.process
            runningIds += request.instanceId
            lastLaunchTimes[request.instanceId] = nowMillis()
            saveHistory()

            // Defensive: do not rely only on HMCL's daemon exit waiter. If the
            // process is killed while a pump thread is stuck, the UI must still
            // leave the "running" state.
            Thread {
                try {
                    val code = managed.process.waitFor()
                    Thread.sleep(250)
                    listener.onExit(code, ProcessListener.ExitType.NORMAL)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }.apply {
                isDaemon = true
                name = "ymcl-exit-${request.instanceId}"
                start()
            }

            LaunchResult.Started(
                LaunchHandle(
                    pid = managed.process.pid(),
                    logFile = logFile.absolutePath,
                    logs = logs.asSharedFlow(),
                    exitCode = exits.asSharedFlow(),
                ),
            )
        } catch (e: Throwable) {
            LaunchResult.Failed(
                "启动失败：${e.message ?: e.javaClass.simpleName}\n" +
                    "请确认游戏目录、Java 与 libraries 完整；日志目录：${File(appRoot, "data/logs").absolutePath}",
            )
        }
    }

    override fun awaitExit(instanceId: String): Int {
        val process = running[instanceId]
        return if (process != null) {
            val code = process.waitFor()
            exitCodes[instanceId] ?: code
        } else {
            exitCodes[instanceId] ?: 0
        }
    }

    override fun stop(instanceId: String): Boolean {
        val process = running.remove(instanceId) ?: return false
        process.toHandle().descendants().forEach { it.destroy() }
        process.destroy()
        Thread {
            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (process.isAlive) {
                process.toHandle().descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }.apply {
            isDaemon = true
            name = "ymcl-stop-$instanceId"
            start()
        }
        return true
    }

    private fun loadManifests(gameDir: Path, id: String): Pair<GameInstanceManifest, GameInstanceManifest> =
        loadManifests(gameDir, id, HashSet())

    private fun loadManifests(
        gameDir: Path,
        id: String,
        seen: MutableSet<String>,
    ): Pair<GameInstanceManifest, GameInstanceManifest> {
        if (!seen.add(id)) throw IOException("版本继承成环：$id")
        val dir = gameDir.resolve("versions").resolve(id)
        val json = conventionalJson(dir, id) ?: throw IOException("找不到版本 JSON：$dir")
        val obj = JsonParser.parseString(Files.readString(json)).asJsonObject
        patchLegacyManifest(obj)
        val raw = GameInstanceManifest.fromJson(obj, false)
        val resolved = raw.inheritsFrom()?.let { parent ->
            val parentResolved = loadManifests(gameDir, parent.id(), seen).second
            mergeManifest(raw, parentResolved)
        } ?: raw
        return raw to resolved
    }

    private fun conventionalJson(dir: Path, id: String): Path? {
        val conventional = dir.resolve("$id.json")
        if (Files.isRegularFile(conventional)) return conventional
        return dir.toFile().listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedBy { it.name }
            ?.firstOrNull()
            ?.toPath()
    }

    /**
     * Old Mojang manifests have `logging.<name>.file` without the `id` field that
     * HMCL 3.7's IdDownloadInfo requires. The launcher extracts its own log4j
     * config, so a stable synthetic id is enough to parse the instance.
     */
    private fun patchLegacyManifest(obj: JsonObject) {
        val logging = obj.getAsJsonObject("logging") ?: return
        logging.entrySet().forEach { (_, value) ->
            val file = value.asJsonObject.getAsJsonObject("file") ?: return@forEach
            if (file.get("id") == null) {
                file.addProperty("id", file.get("sha1")?.asString ?: "legacy-log4j")
            }
        }
    }

    /** {@code GameInstanceManifest.merge} is package-private in HMCLCore. */
    private fun mergeManifest(child: GameInstanceManifest, parent: GameInstanceManifest): GameInstanceManifest {
        val method = GameInstanceManifest::class.java.getDeclaredMethod(
            "merge",
            GameInstanceManifest::class.java,
        )
        method.isAccessible = true
        return method.invoke(child, parent) as GameInstanceManifest
    }

    private fun findMcVersion(id: String, manifest: GameInstanceManifest): String {
        val match = Regex("""(?:^|[^0-9])(\d{1,2}\.\d{1,2}(?:\.\d{1,2})?)(?:[^0-9]|$)""")
            .find(id)
        if (match != null) return match.groupValues[1]
        return manifest.id().id()
    }

    private fun detectLoader(id: String, manifest: GameInstanceManifest): Loader {
        val haystack = buildString {
            append(id.lowercase()).append(' ')
            append(manifest.mainClass().orEmpty().lowercase()).append(' ')
            manifest.libraries().orEmpty().forEach { append(it.artifact().getGroup()).append(' ') }
        }
        val kind = when {
            "neoforge" in haystack || "net.neoforged" in haystack -> LoaderKind.NEOFORGE
            "forge" in haystack || "net.minecraftforge" in haystack -> LoaderKind.FORGE
            "quilt" in haystack || "org.quiltmc" in haystack -> LoaderKind.QUILT
            "fabric" in haystack || "net.fabricmc" in haystack -> LoaderKind.FABRIC
            else -> LoaderKind.VANILLA
        }
        if (kind == LoaderKind.VANILLA) return Loader.Vanilla
        val library = manifest.libraries().orEmpty().firstOrNull {
            val artifact = it.artifact()
            when (kind) {
                LoaderKind.FORGE ->
                    artifact.getGroup() == "net.minecraftforge" &&
                        artifact.getName() in setOf("fmlloader", "forge")
                LoaderKind.NEOFORGE ->
                    artifact.getGroup() == "net.neoforged" &&
                        artifact.getName() in setOf("neoforge", "fmlloader")
                LoaderKind.FABRIC ->
                    artifact.getGroup() == "net.fabricmc" && artifact.getName() == "fabric-loader"
                LoaderKind.QUILT ->
                    artifact.getGroup() == "org.quiltmc" && artifact.getName() == "quilt-loader"
                else -> false
            }
        }
        val rawVersion = library?.artifact()?.getVersion().orEmpty()
        // Forge/NeoForge versions look like "1.20.1-47.4.23"; show the loader part.
        val version = if (kind == LoaderKind.FORGE || kind == LoaderKind.NEOFORGE) {
            rawVersion.substringAfterLast('-')
        } else {
            rawVersion
        }
        return Loader(kind, version)
    }

    private fun requiredJavaMajor(mcVersion: String): Int {
        val parts = mcVersion.split('.').mapNotNull { it.toIntOrNull() }
        val major = parts.getOrNull(0) ?: return 8
        val minor = parts.getOrNull(1) ?: 0
        val patch = parts.getOrNull(2) ?: 0
        return when {
            major < 1 -> 8
            major == 1 && minor <= 16 -> 8
            major == 1 && (minor < 20 || (minor == 20 && patch <= 4)) -> 17
            else -> 21
        }
    }

    private fun safeName(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .take(80)
            .ifBlank { "instance" }

    private fun loadHistory() {
        try {
            val text = readTextAuto(historyFile.absolutePath)
            val obj = Json.parseToJsonElement(text).jsonObject
            obj["lastLaunchedAt"]?.jsonObject?.forEach { (key, value) ->
                value.jsonPrimitive.content.toLongOrNull()?.let { lastLaunchTimes[key] = it }
            }
        } catch (_: Exception) {
            // Missing/corrupt history is not fatal: just start with no recents.
        }
    }

    private fun saveHistory() {
        try {
            val text = buildJsonObject {
                put("schemaVersion", 1)
                put("lastLaunchedAt", buildJsonObject {
                    lastLaunchTimes.forEach { (key, value) -> put(key, value) }
                })
            }.toString()
            writeTextUtf8Lf(historyFile.absolutePath, text + "\n")
        } catch (e: Exception) {
            println("YMCL history: ${e.message}")
        }
    }
}

private class HmclGameInstance(
    private val baseDir: Path,
    private val resolved: GameInstanceManifest,
    private val stored: GameInstanceManifest,
    private val mcVersion: String,
) : GameInstance {
    private val layout = DefaultGameRepositoryLayout(baseDir)
    private val versionNumber = GameVersionNumber.asGameVersion(mcVersion)
    private val componentAnalyzer by lazy { GameComponentAnalyzer.analyze(resolved, versionNumber) }

    override fun getRepository(): GameRepository =
        throw UnsupportedOperationException("YMCL launch path does not expose a repository")

    override fun getLayout() = layout

    override fun getId(): GameInstanceID = resolved.id()

    override fun getManifest(): GameInstanceManifest = stored

    override fun getResolvedManifest(): GameInstanceManifest = resolved

    override fun getAnalyzer(): GameComponentAnalyzer = componentAnalyzer

    override fun getVersion(): GameVersionNumber = versionNumber

    override fun getInstanceRoot(): Path = layout.getInstanceRoot(resolved.id())

    override fun getManifestFile(): Path = layout.getInstanceJson(resolved.id())

    override fun getModpackConfigurationFile(): Path =
        layout.getInstanceRoot(resolved.id()).resolve("modpack.cfg")

    override fun getInstanceJarFile(): Path =
        resolved.jar()?.let { layout.getInstanceJarFile(it) }
            ?: layout.getInstanceJarFile(resolved.id())

    override fun getRunDirectory(): Path = baseDir

    override fun getAssetIndex(assetId: String): AssetIndex =
        JsonUtils.fromJsonFile(layout.getAssetIndexFile(assetId), AssetIndex::class.java)
            ?: throw IOException("Asset index missing: $assetId")

    override fun getActualAssetDirectory(assetId: String): Path = layout.getAssetDirectory()

    override fun getAssetObject(assetId: String, name: String): Optional<Path> {
        val objectInfo = getAssetIndex(assetId).objects[name] ?: return Optional.empty()
        return Optional.of(layout.getAssetObject(objectInfo))
    }
}
