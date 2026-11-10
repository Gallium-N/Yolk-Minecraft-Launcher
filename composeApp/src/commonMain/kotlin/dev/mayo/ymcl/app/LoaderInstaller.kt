package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.LoaderKind
import dev.mayo.ymcl.domain.VersionManifest
import dev.mayo.ymcl.platform.PathUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LoaderInstallException(message: String) : Exception(message)

data class LoaderInstallResult(
    val versionId: String,
    val loaderVersion: String,
    val libraryCount: Int,
    val downloaded: Int,
    val skipped: Int,
    val sourceCounts: Map<String, Int>,
    val fallbackAttempts: List<String>,
)

/**
 * Production loader installer for all four kinds. Fabric is the original P3
 * path; Quilt mirrors it via meta.quiltmc.org v3; Forge and NeoForge run the
 * official installer jar and turn its inheriting profile into a self-contained
 * one (vanilla + loader libraries, no `inheritsFrom`) to avoid HMCL's JPMS
 * duplicate-module failure. All URLs come from [DownloadSource]; process
 * execution is injected via [LoaderProcess] so this stays commonMain/pure.
 */
class LoaderInstaller(
    private val source: DownloadSource,
    private val downloader: GameDownloader,
    private val fs: DownloadFileSystem,
    private val meta: MetaRepository,
    private val log: (String) -> Unit = ::println,
    private val process: LoaderProcess = UnavailableLoaderProcess,
    /** Absolute game root, needed for the Forge/NeoForge --installClient work dir. */
    private val gameRoot: String = "",
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Unified entry point (task book section A). */
    fun install(
        kind: LoaderKind,
        mcVersion: String,
        loaderVersion: String?,
        instanceId: String,
        parentInstanceId: String = mcVersion,
        manifest: VersionManifest? = null,
    ): LoaderInstallResult = when (kind) {
        LoaderKind.VANILLA -> installVanilla(mcVersion, instanceId, parentInstanceId)
        LoaderKind.FABRIC -> installFabric(
            mcVersion, manifest ?: meta.loadManifest().value,
            instanceId, parentInstanceId, loaderVersion,
        )
        LoaderKind.QUILT -> installQuilt(mcVersion, loaderVersion, instanceId, parentInstanceId)
        LoaderKind.FORGE -> installForge(mcVersion, loaderVersion, instanceId, parentInstanceId)
        LoaderKind.NEOFORGE -> installNeoForge(mcVersion, loaderVersion, instanceId, parentInstanceId)
    }

    // ---- Fabric (original P3 path) ----
    fun installFabric(
        mcVersion: String,
        manifest: VersionManifest,
        instanceId: String? = null,
        parentInstanceId: String = mcVersion,
        /** When a modpack pins a loader version, use it instead of the newest. */
        requestedLoaderVersion: String? = null,
    ): LoaderInstallResult {
        if (manifest.versions.none { it.id == mcVersion }) {
            throw LoaderInstallException(
                "MC 版本 $mcVersion 不在版本清单中（清单共 ${manifest.versions.size} 个版本，示例可用：1.20.1）",
            )
        }
        val selection = if (requestedLoaderVersion != null) {
            FabricProfileSelection(
                requestedLoaderVersion,
                meta.loadFabricProfile(mcVersion, requestedLoaderVersion).value,
                emptyList(),
            )
        } else {
            meta.loadFabricProfileWithFallback(mcVersion)
        }
        val versionId = instanceId
            ?: selection.profile.id.ifBlank { "fabric-loader-${selection.loaderVersion}-$mcVersion" }
        val profileText = meta.fabricProfileText(mcVersion, selection.loaderVersion)
        return writeInheritingProfile(
            profileText, versionId, parentInstanceId, "fabric",
            selection.loaderVersion, selection.attempts,
        )
    }

    // ---- Quilt (meta.quiltmc.org v3, same shape as Fabric) ----
    fun installQuilt(
        mcVersion: String,
        requestedLoaderVersion: String?,
        instanceId: String,
        parentInstanceId: String = mcVersion,
    ): LoaderInstallResult {
        val versions = quiltLoaderVersions(mcVersion)
        if (versions.isEmpty()) throw LoaderInstallException("Quilt loader 列表为空：$mcVersion")
        val loaderVersion = requestedLoaderVersion?.takeIf { it.isNotBlank() }
            ?: (versions.firstOrNull { !it.contains("beta", true) && !it.contains("alpha", true) }
                ?: versions.first())
        val profileText = meta.fetch(source.quiltProfile(mcVersion, loaderVersion))
        log("quilt: loader=$loaderVersion profile=${source.quiltProfile(mcVersion, loaderVersion).first()}")
        return writeInheritingProfile(profileText, instanceId, parentInstanceId, "quilt", loaderVersion, emptyList())
    }

    private fun quiltLoaderVersions(mcVersion: String): List<String> {
        val text = meta.fetch(source.quiltLoaderMetadata(mcVersion))
        return json.parseToJsonElement(text).jsonArray.mapNotNull { el ->
            el.jsonObject["loader"]?.jsonObject?.get("version")?.jsonPrimitive?.content
        }
    }

    // ---- Forge / NeoForge: official installer jar -> self-contained profile ----
    fun installForge(
        mcVersion: String,
        loaderVersion: String?,
        instanceId: String,
        parentInstanceId: String = mcVersion,
    ): LoaderInstallResult {
        val forge = forgeNumber(mcVersion, loaderVersion)
        val full = "$mcVersion-$forge"
        val jarRel = "installer/forge-$full-installer.jar"
        val candidates = source.forgeInstaller(mcVersion, forge)
        log("forge installer candidates=${candidates.size} (via DownloadSource.forgeInstaller)")
        downloader.download(DownloadSpec(jarRel, candidates.map { CandidateUrl(it, source.id) }))
        return runInstallerJar(jarRel, "forge", parentInstanceId, instanceId, forge)
    }

    fun installNeoForge(
        mcVersion: String,
        loaderVersion: String?,
        instanceId: String,
        parentInstanceId: String = mcVersion,
    ): LoaderInstallResult {
        val probe = process.probe(source.neoforgeProbeUrl(), 3)
        log("NeoForge probe reachable=${probe.reachable} attempts=${probe.attempts} codes=${probe.httpCodes}")
        if (!probe.reachable) {
            throw LoaderInstallException(
                "NeoForge 官方源不可达（已尝试 ${probe.attempts} 次，HTTP=${probe.httpCodes}），请检查网络或代理",
            )
        }
        val version = loaderVersion?.takeIf { it.isNotBlank() }
            ?: throw LoaderInstallException("NeoForge 需要 loaderVersion（如 20.4.237）")
        val jarRel = "installer/neoforge-$version-installer.jar"
        val candidates = source.neoforgeInstaller(version)
        log("neoforge installer candidates=${candidates.size} (via DownloadSource.neoforgeInstaller)")
        downloader.download(DownloadSpec(jarRel, candidates.map { CandidateUrl(it, source.id) }))
        return runInstallerJar(jarRel, "neoforge", parentInstanceId, instanceId, version)
    }

    private fun runInstallerJar(
        jarRel: String,
        kind: String,
        parentInstanceId: String,
        instanceId: String,
        loaderVersion: String,
    ): LoaderInstallResult {
        val jarAbs = PathUtil.toNative(PathUtil.join(gameRoot, jarRel))
        val workDir = PathUtil.toNative(gameRoot)
        // Real parameters come from the installer itself, not from memory.
        val help = process.runInstaller(jarAbs, listOf("--help"), workDir, 2)
        log("$kind --help exit=${help.exitCode} lines=${help.output.size}")
        help.output.filter { it.contains("installClient", true) }.forEach { log("$kind help: $it") }
        if (help.exitCode != 0 || help.output.none { it.contains("installClient", true) }) {
            log("$kind WARN: --help 未确认 --installClient 参数，仍按原参数尝试")
        }
        // The installer refuses to run without a vanilla launcher profile.
        if (!fs.exists("launcher_profiles.json")) {
            val profiles = """{"profiles":{"ymcl":{"name":"$parentInstanceId","lastVersionId":"$parentInstanceId","type":"custom"}}}"""
            fs.writeNew("launcher_profiles.json", profiles.toByteArray(Charsets.UTF_8))
            log("$kind: 创建 launcher_profiles.json（installer 前置条件）")
        }
        val before = fs.listDirs("versions").toSet()
        val run = process.runInstaller(jarAbs, listOf("--installClient", workDir), workDir, 10)
        run.output.takeLast(30).forEach { log("$kind installer: $it") }
        log("$kind installer exit=${run.exitCode} elapsedMs=${run.elapsedMs}")
        if (run.exitCode != 0) {
            throw LoaderInstallException("$kind installer 退出码 ${run.exitCode}，耗时 ${run.elapsedMs}ms")
        }
        val after = fs.listDirs("versions")
        val generated = after.firstOrNull { it !in before && it.contains(kind, true) }
            ?: after.firstOrNull { it.contains(kind, true) }
            ?: throw LoaderInstallException("$kind installer 未生成版本目录：$after")
        return mergeSelfContained(generated, instanceId, parentInstanceId, kind, loaderVersion)
    }

    // ---- Vanilla: version-isolated profile inheriting the shared base ----
    fun installVanilla(
        mcVersion: String,
        instanceId: String,
        parentInstanceId: String = mcVersion,
    ): LoaderInstallResult {
        val parentRel = "versions/$parentInstanceId/$parentInstanceId.json"
        if (!fs.exists(parentRel)) throw LoaderInstallException("原版基座不存在：$parentRel")
        val obj = json.parseToJsonElement(String(fs.readAll(parentRel), Charsets.UTF_8)).jsonObject.toMutableMap()
        val libs = obj["libraries"]?.jsonArray?.size ?: 0
        obj["id"] = JsonPrimitive(instanceId)
        obj["inheritsFrom"] = JsonPrimitive(parentInstanceId)
        obj["jar"] = JsonPrimitive(parentInstanceId)
        fs.writeNew("versions/$instanceId/$instanceId.json", JsonObject(obj).toString().toByteArray(Charsets.UTF_8))
        if (!fs.exists("versions/$instanceId/$instanceId.jar")) {
            fs.linkOrCopy("versions/$parentInstanceId/$parentInstanceId.jar", "versions/$instanceId/$instanceId.jar")
        }
        log("vanilla instance $instanceId inherits $parentInstanceId (libraries=$libs)")
        return LoaderInstallResult(instanceId, "", libs, 0, 0, emptyMap(), emptyList())
    }

    /** Fabric/Quilt profile: rewrite library URLs through DownloadSource, inherit the vanilla parent. */
    private fun writeInheritingProfile(
        profileText: String,
        versionId: String,
        parentInstanceId: String,
        sourceId: String,
        loaderVersion: String,
        attempts: List<String>,
    ): LoaderInstallResult {
        val root = json.parseToJsonElement(profileText).jsonObject
        val libs = root["libraries"]?.jsonArray
            ?: throw LoaderInstallException("$sourceId profile 缺少 libraries 字段")
        val patchedLibs = libs.map { el ->
            val lib = el.jsonObject
            val name = lib["name"]?.jsonPrimitive?.content
                ?: throw LoaderInstallException("$sourceId library 缺少 name 字段")
            val (_, candidates) = mavenPathOf(name)
            JsonObject(lib.toMutableMap().apply { put("url", JsonPrimitive(candidates.first())) })
        }
        val versionJson = JsonObject(root.toMutableMap().apply {
            put("id", JsonPrimitive(versionId))
            put("inheritsFrom", JsonPrimitive(parentInstanceId))
            put("jar", JsonPrimitive(parentInstanceId))
            put("libraries", JsonArray(patchedLibs))
        })
        val versionRel = "versions/$versionId/$versionId.json"
        fs.writeNew(versionRel, versionJson.toString().toByteArray(Charsets.UTF_8))
        log("$sourceId versionJson: $versionRel (id=$versionId, inheritsFrom=$parentInstanceId, " +
            "libraries=${libs.size}, mainClass=${root["mainClass"]?.jsonPrimitive?.content})")

        val specs = mutableListOf<DownloadSpec>()
        var skipped = 0
        libs.forEach { el ->
            val name = el.jsonObject["name"]?.jsonPrimitive?.content ?: return@forEach
            val (path, candidates) = mavenPathOf(name)
            val rel = "libraries/$path"
            if (fs.exists(rel)) {
                skipped++
                return@forEach
            }
            specs += DownloadSpec(
                relativePath = rel,
                candidates = candidates.map { CandidateUrl(it, source.id) },
                expectedSha1 = el.jsonObject["sha1"]?.jsonPrimitive?.content,
                expectedSize = el.jsonObject["size"]?.jsonPrimitive?.content?.toLongOrNull(),
            )
        }
        downloader.reset(specs.size, specs.sumOf { it.expectedSize ?: 0L })
        val sourceCounts = mutableMapOf<String, Int>()
        var downloaded = 0
        specs.forEach { spec ->
            val outcome = try {
                downloader.download(spec)
            } catch (e: Exception) {
                throw LoaderInstallException("$sourceId 库下载失败：${spec.relativePath}；${e.message}")
            }
            downloaded++
            sourceCounts[outcome.sourceId] = (sourceCounts[outcome.sourceId] ?: 0) + 1
        }
        log("$sourceId libraries: total=${libs.size} downloaded=$downloaded skipped=$skipped sources=$sourceCounts")
        return LoaderInstallResult(versionId, loaderVersion, libs.size, downloaded, skipped, sourceCounts, attempts)
    }

    /**
     * The installer emits an inheriting profile (Forge/NeoForge libs + inheritsFrom
     * the vanilla). HMCL's module layer then resolves the vanilla jar twice (JPMS
     * conflict), so merge into one self-contained profile: vanilla + loader libs,
     * merged arguments, no inheritsFrom, jar points at itself (hard-linked).
     */
    private fun mergeSelfContained(
        generated: String,
        instanceId: String,
        parentInstanceId: String,
        kind: String,
        loaderVersion: String,
    ): LoaderInstallResult {
        val genRel = "versions/$generated/$generated.json"
        val vanRel = "versions/$parentInstanceId/$parentInstanceId.json"
        val gen = json.parseToJsonElement(String(fs.readAll(genRel), Charsets.UTF_8)).jsonObject
        val van = json.parseToJsonElement(String(fs.readAll(vanRel), Charsets.UTF_8)).jsonObject
        val mergedLibs = (van["libraries"]!!.jsonArray + gen["libraries"]!!.jsonArray)
            .distinctBy { it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty() }
        val vArgs = van["arguments"]?.jsonObject
        val gArgs = gen["arguments"]?.jsonObject
        fun mergeArgs(key: String): JsonArray = JsonArray(
            (vArgs?.get(key)?.jsonArray ?: emptyList()) + (gArgs?.get(key)?.jsonArray ?: emptyList()),
        )
        val patched = JsonObject(gen.toMutableMap().apply {
            remove("inheritsFrom")
            put("id", JsonPrimitive(instanceId))
            put("jar", JsonPrimitive(instanceId))
            put("libraries", JsonArray(mergedLibs))
            put("arguments", JsonObject(mapOf("game" to mergeArgs("game"), "jvm" to mergeArgs("jvm"))))
            listOf("assetIndex", "assets", "javaVersion", "minimumLauncherVersion", "complianceLevel", "downloads", "logging")
                .forEach { key -> van[key]?.let { put(key, it) } }
        })
        fs.writeNew("versions/$instanceId/$instanceId.json", patched.toString().toByteArray(Charsets.UTF_8))
        if (!fs.exists("versions/$instanceId/$instanceId.jar")) {
            fs.linkOrCopy(
                "versions/$parentInstanceId/$parentInstanceId.jar",
                "versions/$instanceId/$instanceId.jar",
            )
        }
        log("$kind: 自包含 profile libraries=${mergedLibs.size} jar=$instanceId（硬链接共享原版）")
        return LoaderInstallResult(instanceId, loaderVersion, mergedLibs.size, 0, 0, emptyMap(), emptyList())
    }

    private fun mavenPathOf(name: String): Pair<String, List<String>> {
        val parts = name.split(":")
        if (parts.size < 3) throw LoaderInstallException("无法解析 library 坐标：$name")
        val (group, artifact, version) = parts
        val classifier = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
        val path = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version" +
            (classifier?.let { "-$it" } ?: "") + ".jar"
        return path to source.loaderMaven(group, artifact, version, classifier, "jar")
    }

    private fun forgeNumber(mcVersion: String, loaderVersion: String?): String {
        val v = loaderVersion?.takeIf { it.isNotBlank() }
            ?: throw LoaderInstallException("Forge 需要 loaderVersion（如 47.2.0）")
        return if (v.startsWith("$mcVersion-")) v.removePrefix("$mcVersion-") else v
    }
}
