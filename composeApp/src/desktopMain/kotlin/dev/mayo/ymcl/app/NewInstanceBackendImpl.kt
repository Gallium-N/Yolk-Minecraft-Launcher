package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.LoaderKind
import dev.mayo.ymcl.domain.VersionEntry
import dev.mayo.ymcl.platform.NativeDownloadFileSystem
import dev.mayo.ymcl.platform.PathUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Desktop implementation of [NewInstanceBackend]. Installs the shared vanilla
 * base with GameInstaller, then the chosen loader with the unified
 * LoaderInstaller, and writes the instance's portable metadata.
 */
private class InstallCancelledException : Exception()

class NewInstanceBackendImpl(
    private val gameRoot: String,
    private val source: DownloadSource,
    private val meta: MetaRepository,
    private val transport: DownloadTransport,
    private val process: LoaderProcess,
    private val log: (String) -> Unit = ::println,
) : NewInstanceBackend {
    private val fs = NativeDownloadFileSystem(gameRoot)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cancelled = false
    private var activeDownloader: GameDownloader? = null
    // QA-only: the auto-cancel hook fires once, so "继续安装" can complete.
    private var qaCancelFired = false

    override fun existingIds(): Set<String> =
        File(PathUtil.toNative(PathUtil.join(gameRoot, "versions")))
            .listFiles { f -> f.isDirectory }?.map { it.name }?.toSet() ?: emptySet()

    override fun loadVersions(): List<VersionEntry> = meta.loadManifest().value.versions

    override fun loaderVersions(kind: LoaderKind, mcVersion: String): List<String> = when (kind) {
        LoaderKind.VANILLA -> emptyList()
        // Fabric enumeration goes through the TTL cache (fresh hit = zero network,
        // stale = old value + background refresh). The QA "unreachable" switch is
        // applied at the fetcher layer in DesktopMain so cache hits still work.
        LoaderKind.FABRIC -> meta.loadFabricLoaderVersions(mcVersion)
        LoaderKind.QUILT -> fromQuiltJson(meta.fetch(source.quiltLoaderMetadata(mcVersion)))
        LoaderKind.FORGE -> fromMavenMetadata(meta.forgeVersions(), prefix = "$mcVersion-")
        LoaderKind.NEOFORGE -> fromMavenMetadata(meta.neoForgeVersions())
    }

    private fun MetaRepository.forgeVersions(): String = fetch(source.forgeVersionMetadata())
    private fun MetaRepository.neoForgeVersions(): String = fetch(source.neoforgeVersionMetadata())

    /** Newest stable first; falls back to including pre-releases. */
    private fun fromMavenMetadata(text: String, prefix: String = ""): List<String> {
        val all = Regex("<version>([^<]+)</version>").findAll(text)
            .map { it.groupValues[1] }
            .filter { prefix.isEmpty() || it.startsWith(prefix) }
            .map { if (prefix.isEmpty()) it else it.removePrefix(prefix) }
            .toList()
        val stable = all.filter { !it.contains("beta", true) && !it.contains("alpha", true) && !it.contains("pre", true) }
        return (stable.ifEmpty { all }).reversed().take(20)
    }

    private fun fromQuiltJson(text: String): List<String> {
        val all = json.parseToJsonElement(text).jsonArray.mapNotNull { el ->
            el.jsonObject["loader"]?.jsonObject?.get("version")?.jsonPrimitive?.content
        }
        val stable = all.filter { !it.contains("beta", true) && !it.contains("alpha", true) }
        return (stable.ifEmpty { all }).take(20)
    }

    @Volatile
    private var neoForgeNote: String = ""

    override fun neoForgeReachabilityNote(): String = neoForgeNote

    override fun neoForgeReachable(): Boolean {
        // QA-only: forces the unreachable branch so the greyed-out UI can be
        // verified even when a source happens to be up.
        if (System.getProperty("ymcl.qa.neoforge.unreachable") == "1") {
            // Force the unreachable branch with the REAL candidate labels so the
            // greyed-out UI and its "sources tried" message can be verified.
            neoForgeNote = MavenProbe.unreachableMessage(
                "NeoForge", source.neoforgeProbeUrls().map(MavenProbe::label),
            )
            return false
        }
        // Reuse a fresh (<=5 min) probe result — avoid re-probing every page entry.
        // Key by source id so switching mirror on/off cannot reuse a result from
        // the other source (bmclapi reachable != official reachable).
        val clock = System::currentTimeMillis
        val cacheKey = "neoforge-${source.id}"
        meta.loadProbe(cacheKey, clock)?.let {
            neoForgeNote = it.note
            log("neoforge probe cache hit (source=${source.id} via=${it.via})")
            return it.reachable
        }
        val result = MavenProbe.probeCandidates(
            "neoforge", source.neoforgeProbeUrls(), process, log,
        )
        neoForgeNote = result.note
        meta.saveProbe(cacheKey, result, clock)
        return result.reachable
    }

    override fun cancel() {
        cancelled = true
        activeDownloader?.cancel()
        log("NEW_INSTANCE cancel requested (downloaded files kept for reuse)")
    }

    /** A plain directory name: no separators, traversal, drive letter or absolute path. */
    private fun safeInstanceId(id: String): Boolean {
        if (id.isBlank() || id == "." || id == "..") return false
        if (id.contains('/') || id.contains('\\')) return false
        if (Regex("^[A-Za-z]:").containsMatchIn(id)) return false
        return true
    }

    private fun installStateOf(instanceId: String): Pair<String?, String?> {
        val rel = "versions/$instanceId/instance.json"
        if (!fs.exists(rel)) return null to null
        val obj = runCatching {
            json.parseToJsonElement(String(fs.readAll(rel), Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: return null to null
        return obj["installState"]?.jsonPrimitive?.content to obj["role"]?.jsonPrimitive?.content
    }

    override fun brokenInstalls(): List<BrokenInstall> {
        val versions = File(PathUtil.toNative(PathUtil.join(gameRoot, "versions")))
        val dirs = versions.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val (state, role) = installStateOf(dir.name)
            if (state != "broken" || role.equals("base", true)) return@mapNotNull null
            val displayName = runCatching {
                val rel = "versions/${dir.name}/instance.json"
                val obj = json.parseToJsonElement(String(fs.readAll(rel), Charsets.UTF_8)).jsonObject
                obj["displayName"]?.jsonPrimitive?.content
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: dir.name
            BrokenInstall(dir.name, displayName, PathUtil.join(gameRoot, "versions", dir.name))
        }.sortedBy { it.id }
    }

    override fun deleteBrokenInstall(instanceId: String): Boolean {
        if (!safeInstanceId(instanceId)) {
            log("DELETE_BROKEN refuse: unsafe id=$instanceId")
            return false
        }
        val (state, role) = installStateOf(instanceId)
        if (state != "broken" || role.equals("base", true)) {
            log("DELETE_BROKEN refuse: $instanceId is not a broken instance (state=$state role=$role)")
            return false
        }
        val dir = File(PathUtil.toNative(PathUtil.join(gameRoot, "versions", instanceId)))
        if (!dir.isDirectory) {
            log("DELETE_BROKEN refuse: no directory for $instanceId")
            return false
        }
        val removed = dir.deleteRecursively()
        log("DELETE_BROKEN id=$instanceId removed=$removed path=${dir.absolutePath}")
        return removed
    }

    override fun install(
        mcVersion: String,
        entry: VersionEntry,
        instanceId: String,
        displayName: String,
        kind: LoaderKind,
        loaderVersion: String?,
        onStage: (InstallStage) -> Unit,
    ): NewInstanceResult {
        cancelled = false
        val total = 7
        val failures = mutableListOf<String>()
        // QA-only: slows each stage so the staged-install screen can be captured
        // and cancelled deterministically (no effect in normal runs).
        val stageDelayMs = System.getProperty("ymcl.qa.newinstance.stageDelayMs")?.toLongOrNull() ?: 0L
        val cancelAtStage = System.getProperty("ymcl.qa.newinstance.cancelAtStage")?.toIntOrNull()
        // Latest full stage snapshot. Real downloaded bytes are merged into it by
        // the progress collector below, so a stage tick never resets the bar.
        val stageRef = AtomicReference(InstallStage("准备中", 0, total, 0L))
        fun stage(i: Int, name: String) {
            onStage(stageRef.updateAndGet { it.copy(name = name, done = i, total = total) })
            if (stageDelayMs > 0) Thread.sleep(stageDelayMs)
            // QA-only: deterministic mid-install cancellation for acceptance #15.
            // Fires once so a later "继续安装" run is allowed to complete.
            if (cancelAtStage == i && !qaCancelFired) {
                qaCancelFired = true
                cancel()
                throw InstallCancelledException()
            }
        }
        log("NEW_INSTANCE start id=$instanceId mc=$mcVersion loader=${kind.id} ${loaderVersion ?: ""}")
        val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var progressJob: Job? = null
        try {
            stage(1, "解析版本")
            val downloader = GameDownloader(transport, fs, log)
            activeDownloader = downloader
            // Forward the real byte stream (GameDownloader.progress) into the
            // stage snapshot. reset() starts a new batch (base, then loader), so
            // fold completed bytes/totals across batches to keep the bar monotonic.
            progressJob = progressScope.launch {
                var cumulativeBytes = 0L
                var grandTotal = 0L
                var lastCompleted = 0L
                var lastBatchTotal = 0L
                var lastEmitAt = 0L
                downloader.progress.collect { p ->
                    if (p.completedBytes < lastCompleted && lastCompleted > 0L) {
                        cumulativeBytes += lastCompleted
                        grandTotal += lastBatchTotal
                    }
                    lastCompleted = p.completedBytes
                    lastBatchTotal = p.totalBytes
                    val now = System.currentTimeMillis()
                    if (now - lastEmitAt >= 80L) {
                        lastEmitAt = now
                        onStage(
                            stageRef.updateAndGet {
                                it.copy(
                                    bytes = cumulativeBytes + p.completedBytes,
                                    totalBytes = grandTotal + p.totalBytes,
                                    currentFile = p.currentFile,
                                )
                            },
                        )
                    }
                }
            }
            val manifest = meta.loadManifest().value
            if (manifest.versions.none { it.id == mcVersion }) {
                failures += "版本清单里找不到 $mcVersion"
                return NewInstanceResult(instanceId, failures, false)
            }
            stage(2, "client jar")
            stage(3, "libraries")
            stage(4, "assets")
            stage(5, "原生库")
            if (!fs.exists("versions/$mcVersion/$mcVersion.json")) {
                val summary = GameInstaller(source, downloader, fs, log).install(mcVersion, entry, mcVersion)
                if (summary.failures > 0) failures += summary.errors
            } else {
                log("NEW_INSTANCE base reused: versions/$mcVersion")
            }
            if (cancelled) return finishBroken(instanceId, displayName, mcVersion, kind, loaderVersion, failures, true)

            stage(6, "加载器")
            if (failures.isEmpty()) {
                val loaderInstaller = LoaderInstaller(
                    source, downloader, fs, meta, log, process, gameRoot,
                )
                val result = loaderInstaller.install(
                    kind, mcVersion, loaderVersion, instanceId, mcVersion, manifest,
                )
                log("NEW_INSTANCE loader=${result.loaderVersion} libs=${result.libraryCount}")
            }
            stage(7, "完成")
            writeInstanceJson(instanceId, displayName, mcVersion, kind, loaderVersion, broken = false)
            if (cancelled || failures.isNotEmpty()) {
                writeInstanceJson(instanceId, displayName, mcVersion, kind, loaderVersion, broken = true)
            }
            return NewInstanceResult(instanceId, failures, cancelled)
        } catch (e: InstallCancelledException) {
            log("NEW_INSTANCE cancelled at stage=$cancelAtStage (downloaded files kept)")
            writeInstanceJson(instanceId, displayName, mcVersion, kind, loaderVersion, broken = true)
            return NewInstanceResult(instanceId, failures, true)
        } catch (e: Exception) {
            val msg = "安装失败：${e.message}"
            log("NEW_INSTANCE ERROR: $msg")
            failures += msg
            writeInstanceJson(instanceId, displayName, mcVersion, kind, loaderVersion, broken = true)
            return NewInstanceResult(instanceId, failures, cancelled)
        } finally {
            progressJob?.cancel()
            progressScope.cancel()
        }
    }

    private fun finishBroken(
        instanceId: String,
        displayName: String,
        mcVersion: String,
        kind: LoaderKind,
        loaderVersion: String?,
        failures: List<String>,
        cancelled: Boolean,
    ): NewInstanceResult {
        writeInstanceJson(instanceId, displayName, mcVersion, kind, loaderVersion, broken = true)
        return NewInstanceResult(instanceId, failures, cancelled)
    }

    private fun writeInstanceJson(
        instanceId: String,
        displayName: String,
        mcVersion: String,
        kind: LoaderKind,
        loaderVersion: String?,
        broken: Boolean,
    ) {
        val text = buildJsonObject {
            put("schemaVersion", 1)
            put("id", instanceId)
            put("displayName", displayName)
            put("mcVersion", mcVersion)
            put("loader", buildJsonObject {
                put("kind", kind.id)
                put("version", loaderVersion ?: "")
            })
            if (broken) put("installState", "broken")
        }.toString()
        fs.writeNew("versions/$instanceId/instance.json", text.toByteArray(Charsets.UTF_8))
    }
}
