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
import java.io.File

/**
 * Desktop implementation of [NewInstanceBackend]. Installs the shared vanilla
 * base with GameInstaller, then the chosen loader with the unified
 * LoaderInstaller, and writes the instance's portable metadata.
 */
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

    override fun existingIds(): Set<String> =
        File(PathUtil.toNative(PathUtil.join(gameRoot, "versions")))
            .listFiles { f -> f.isDirectory }?.map { it.name }?.toSet() ?: emptySet()

    override fun loadVersions(): List<VersionEntry> = meta.loadManifest().value.versions

    override fun loaderVersions(kind: LoaderKind, mcVersion: String): List<String> = when (kind) {
        LoaderKind.VANILLA -> emptyList()
        LoaderKind.FABRIC -> fromMavenMetadata(meta.fetch(source.fabricLoaderMetadata(mcVersion)))
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

    override fun neoForgeReachable(): Boolean = process.probe(source.neoforgeProbeUrl(), 1).reachable

    override fun cancel() {
        cancelled = true
        activeDownloader?.cancel()
        log("NEW_INSTANCE cancel requested (downloaded files kept for reuse)")
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
        fun stage(i: Int, name: String, bytes: Long = 0L) =
            onStage(InstallStage(name, i, total, bytes))
        val failures = mutableListOf<String>()
        log("NEW_INSTANCE start id=$instanceId mc=$mcVersion loader=${kind.id} ${loaderVersion ?: ""}")
        try {
            stage(1, "解析版本")
            val downloader = GameDownloader(transport, fs, log)
            activeDownloader = downloader
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
        } catch (e: Exception) {
            val msg = "安装失败：${e.message}"
            log("NEW_INSTANCE ERROR: $msg")
            failures += msg
            writeInstanceJson(instanceId, displayName, mcVersion, kind, loaderVersion, broken = true)
            return NewInstanceResult(instanceId, failures, cancelled)
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
