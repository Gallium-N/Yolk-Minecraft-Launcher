package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.InstanceId
import dev.mayo.ymcl.platform.PathUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Host-side implementation of [ModpackBackend]. Reuses the P3/P4 installers
 * for the shared vanilla + Fabric base, and the P3 [GameDownloader] (sha1
 * verification, one redownload, .part cleanup, failure list) for pack files.
 *
 * Layout rule (task book): `assets/` and `libraries/` stay in the shared root;
 * every pack `files[]` and override goes under `versions/<instanceId>/`, never
 * into the shared root. A config file with `machine.local.json` is NOT written.
 */
class ModpackBackendImpl(
    private val gameRoot: String,
    private val source: DownloadSource,
    private val fs: DownloadFileSystem,
    private val meta: MetaRepository,
    private val transport: DownloadTransport,
    private val archiveFactory: (String) -> ModpackArchive,
    private val cfFetcher: CfFetcher,
    private val apiKey: () -> String?,
    private val proxyEnabled: () -> Boolean,
    private val log: (String) -> Unit = ::println,
) : ModpackBackend {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Volatile
    private var cancelled = false

    private var activeDownloader: GameDownloader? = null

    override fun existingIds(): Set<String> {
        val dir = File(PathUtil.toNative(PathUtil.join(gameRoot, "versions")))
        return dir.listFiles { f -> f.isDirectory }?.map { it.name }?.toSet() ?: emptySet()
    }

    override fun parse(path: String): ModpackPlan {
        val file = File(PathUtil.toNative(path))
        if (!file.isFile) throw ModpackParseException("找不到整合包文件：$path")
        val archive = archiveFactory(path)
        return try {
            ModpackParser.parse(archive, existingIds())
        } finally {
            archive.close()
        }
    }

    override fun cancel() {
        cancelled = true
        activeDownloader?.cancel()
        log("IMPORT cancel requested")
    }

    override fun install(plan: ModpackPlan, onProgress: (ImportProgress) -> Unit): ImportSummary {
        cancelled = false
        // NEVER silently overwrite: allocate again at install time.
        val id = InstanceId.allocate(plan.name, plan.mcVersion, existingIds())
        val failures = mutableListOf<String>()
        val warnings = plan.warnings.toMutableList()
        log(
            "IMPORT start id=$id source=${plan.source.id} name=${plan.name} mc=${plan.mcVersion} " +
                "loader=${plan.loaderKind} ${plan.loaderVersion ?: ""} " +
                "files=${plan.files.size} skipped(server-only)=${plan.skippedServerOnly} " +
                "overrides=${plan.overrides.size} bytes=${plan.totalBytes}",
        )

        // Resolve CurseForge urls FIRST: a missing-file failure must not leave a
        // half-created instance. Level 1/2/3 logging lives in the resolver.
        val (concrete, missing) = resolveFiles(plan)
        if (missing.isNotEmpty()) {
            val msg = CurseForgeFileResolver.missingMessage(missing.size)
            failures += msg
            missing.forEach { failures += "缺少文件：projectID=${it.first} fileID=${it.second}" }
            log("IMPORT ERROR: $msg")
            missing.forEach { log("IMPORT MISSING ${it.first}/${it.second}") }
            return finish(ImportSummary(id, plan.source, 0, 0L, plan.skippedServerOnly, 0, failures, warnings, false, 0))
        }

        // Phase 1: shared vanilla + loader (assets/libraries only, never instance dir).
        onProgress(ImportProgress("安装游戏版本与加载器", 0, concrete.size, 0L, plan.totalBytes, null))
        try {
            installBase(plan, id, warnings)
        } catch (e: Exception) {
            val msg = "基础游戏 / 加载器安装失败：${e.message}"
            log("IMPORT ERROR: $msg")
            return finish(
                ImportSummary(id, plan.source, 0, 0L, plan.skippedServerOnly, 0, failures + msg, warnings, false, 0),
            )
        }
        if (cancelled) return finish(cancelledSummary(id, plan, 0, 0L, 0, failures, warnings))

        // Phase 2: download files[] into the INSTANCE dir. Reuse the P3 downloader
        // so sha1 mismatch deletes .part, redownloads once, then joins the failure list.
        val downloader = GameDownloader(transport, fs, log)
        activeDownloader = downloader
        var doneFiles = 0
        var doneBytes = 0L
        var unverified = 0
        val parts = mutableListOf<String>()
        for (f in concrete) {
            if (cancelled) {
                downloader.cancel()
                break
            }
            val rel = "versions/$id/${f.path}"
            parts += rel
            if (f.sha1 == null) unverified++
            try {
                val spec = DownloadSpec(
                    relativePath = rel,
                    candidates = f.downloads.flatMap { source.modpackFile(it) }
                        .map { CandidateUrl(it, "modpack") },
                    expectedSha1 = f.sha1,
                    expectedSize = if (f.size > 0) f.size else null,
                    scope = DownloadScope.INSTANCE,
                )
                val outcome = downloader.download(spec)
                doneFiles++
                doneBytes += outcome.bytes
                onProgress(
                    ImportProgress("下载模组与资源", doneFiles, concrete.size, doneBytes, plan.totalBytes, f.path),
                )
            } catch (e: DownloadCancelledException) {
                break
            } catch (e: Exception) {
                val msg = "$rel：${e.message}"
                log("IMPORT ERROR: $msg")
                failures += msg
            }
        }
        if (cancelled) {
            // Stop downloads, clean every .part, but KEEP the extracted directory.
            downloader.cleanupParts(parts)
            return finish(cancelledSummary(id, plan, doneFiles, doneBytes, unverified, failures, warnings))
        }

        // Phase 3: overrides (already priority-resolved; client-overrides wins).
        plan.overrides.forEach { o -> fs.writeNew("versions/$id/${o.path}", o.bytes) }
        plan.warnings.forEach { log("IMPORT WARN: $it") }

        // Phase 4: instance.json with packSource/packVersion/schemaVersion. Our own
        // client-overrides/.launcher/instance.json convention is merged on top.
        writeInstanceJson(plan, id)

        return finish(
            ImportSummary(
                id, plan.source, doneFiles, doneBytes, plan.skippedServerOnly,
                plan.overrides.size, failures, warnings, false, unverified,
            ),
        )
    }

    private fun cancelledSummary(
        id: String,
        plan: ModpackPlan,
        files: Int,
        bytes: Long,
        unverified: Int,
        failures: List<String>,
        warnings: List<String>,
    ) = ImportSummary(id, plan.source, files, bytes, plan.skippedServerOnly, 0, failures, warnings, true, unverified)

    private fun finish(summary: ImportSummary): ImportSummary {
        log(
            "IMPORT summary: instance=${summary.instanceId} source=${summary.source.id} " +
                "files=${summary.files} bytes=${summary.bytes} " +
                "skipped(server-only)=${summary.skippedServerOnly} overrides=${summary.overrides} " +
                "unverified=${summary.unverified} failures=${summary.failures.size} " +
                "cancelled=${summary.cancelled}",
        )
        summary.failures.forEach { log("IMPORT ERROR: $it") }
        return summary
    }

    /** Resolves CurseForge files through the 3-level strategy; mrpack files pass through. */
    private fun resolveFiles(plan: ModpackPlan): Pair<List<PackFile>, List<Pair<String, String>>> {
        if (plan.source != PackSource.CURSEFORGE) return plan.files to emptyList()
        val resolver = CurseForgeFileResolver(source, cfFetcher, apiKey(), proxyEnabled(), log)
        val out = mutableListOf<PackFile>()
        val missing = mutableListOf<Pair<String, String>>()
        for (f in plan.files) {
            val pid = f.cfProjectId ?: "?"
            val fid = f.cfFileId ?: "?"
            val r = resolver.resolve(pid, fid)
            if (r == null) {
                missing += pid to fid
            } else {
                out += f.copy(
                    path = "mods/${r.filename}",
                    sha1 = r.sha1,
                    downloads = listOf(r.downloadUrl),
                    size = 0L,
                )
            }
        }
        return out to missing
    }

    private fun installBase(plan: ModpackPlan, id: String, warnings: MutableList<String>) {
        val mc = plan.mcVersion
        val parent = mc // shared vanilla instance, e.g. versions/1.20.1/
        if (!fs.exists("versions/$parent/$parent.json")) {
            val manifest = meta.loadManifest().value
            val entry = manifest.versions.firstOrNull { it.id == mc }
                ?: throw ModpackInstallException("版本清单里找不到 Minecraft $mc")
            val summary = GameInstaller(source, GameDownloader(transport, fs, log), fs, log)
                .install(mc, entry, parent)
            if (summary.failures > 0) {
                throw ModpackInstallException("原版安装失败：" + summary.errors.joinToString("; "))
            }
            log("IMPORT base: vanilla $mc installed into versions/$parent")
        } else {
            log("IMPORT base: 复用已存在的原版 versions/$parent")
        }

        when (plan.loaderKind.lowercase()) {
            "fabric", "quilt" -> {
                val installer = LoaderInstaller(source, GameDownloader(transport, fs, log), fs, meta, log)
                val manifest = meta.loadManifest().value
                val pinned = plan.loaderVersion?.takeIf { plan.loaderKind.lowercase() == "fabric" }
                val result = if (!pinned.isNullOrBlank()) {
                    try {
                        installer.installFabric(mc, manifest, id, parent, pinned)
                    } catch (e: Exception) {
                        log("IMPORT base: 指定加载器 $pinned 不可用（${e.message}），回退到最新 Fabric")
                        warnings += "整合包指定的 Fabric $pinned 不可用，已回退到最新 Fabric 加载器"
                        installer.installFabric(mc, manifest, id, parent)
                    }
                } else {
                    if (plan.loaderKind.lowercase() == "quilt") {
                        warnings += "Quilt 加载器自动安装暂未支持，已按 Fabric 处理"
                    }
                    installer.installFabric(mc, manifest, id, parent)
                }
                log(
                    "IMPORT base: fabric loader=${result.loaderVersion} libs=${result.libraryCount} " +
                        "downloaded=${result.downloaded} skipped=${result.skipped}",
                )
            }

            "forge", "neoforge" -> {
                writeInheritedVersionJson(id, parent)
                val w = "${plan.loaderKind} 加载器自动安装暂未支持（P3 只实现了 Fabric）；" +
                    "实例已创建，但需手动安装该加载器后才能启动。"
                warnings += w
                log("IMPORT base WARN: $w")
            }

            else -> {
                writeInheritedVersionJson(id, parent)
                log("IMPORT base: 原版整合包（无模组加载器）")
            }
        }
    }

    /** A version json that inherits the vanilla parent (used for Forge/vanilla packs). */
    private fun writeInheritedVersionJson(id: String, parent: String) {
        val parentText = String(fs.readAll("versions/$parent/$parent.json"), Charsets.UTF_8)
        val obj = json.parseToJsonElement(parentText).jsonObject.toMutableMap()
        obj["id"] = JsonPrimitive(id)
        obj["inheritsFrom"] = JsonPrimitive(parent)
        obj["jar"] = JsonPrimitive(parent)
        fs.writeNew("versions/$id/$id.json", JsonObject(obj).toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeInstanceJson(plan: ModpackPlan, id: String) {
        val base = buildJsonObject {
            put("schemaVersion", 1)
            put("id", id)
            put("displayName", plan.name)
            put("mcVersion", plan.mcVersion)
            put("loader", buildJsonObject {
                put("kind", plan.loaderKind)
                put("version", plan.loaderVersion ?: "")
            })
            put("packSource", plan.source.id)
            put("packVersion", plan.packVersion)
        }.toMutableMap()
        // Our own convention: client-overrides/.launcher/instance.json is merged
        // into instance.json. Pack-supplied keys win; schemaVersion stays 1.
        plan.instanceConfigJson?.let { text ->
            runCatching {
                json.parseToJsonElement(text).jsonObject.forEach { (k, v) ->
                    if (k != "schemaVersion") base[k] = v
                }
            }.onFailure { log("IMPORT WARN: .launcher/instance.json 无法解析，忽略：${it.message}") }
        }
        fs.writeNew("versions/$id/instance.json", JsonObject(base).toString().toByteArray(Charsets.UTF_8))
        log("IMPORT instance.json: versions/$id/instance.json packSource=${plan.source.id} packVersion=${plan.packVersion}")
    }
}
