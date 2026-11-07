package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.VersionManifest
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
 * P3 step 4: install a Fabric profile into an isolated game directory.
 * All library URLs go through [DownloadSource.loaderMaven]; no URL literal here.
 */
class LoaderInstaller(
    private val source: DownloadSource,
    private val downloader: GameDownloader,
    private val fs: DownloadFileSystem,
    private val meta: MetaRepository,
    private val log: (String) -> Unit = ::println,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun mavenPathOf(name: String): Pair<String, List<String>> {
        val parts = name.split(":")
        if (parts.size < 3) throw LoaderInstallException("无法解析 library 坐标：$name")
        val (group, artifact, version) = parts
        val path = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.jar"
        return path to source.loaderMaven(group, artifact, version, null, "jar")
    }

    /**
     * [instanceId] is the version directory name (P4). It defaults to null, in
     * which case the upstream profile id is used (the P3 behaviour).
     * [parentInstanceId] is the vanilla instance the profile inherits its jar
     * and libraries from; it defaults to [mcVersion] (also the P3 behaviour).
     */
    fun installFabric(
        mcVersion: String,
        manifest: VersionManifest,
        instanceId: String? = null,
        parentInstanceId: String = mcVersion,
    ): LoaderInstallResult {
        if (manifest.versions.none { it.id == mcVersion }) {
            throw LoaderInstallException(
                "MC 版本 $mcVersion 不在版本清单中（清单共 ${manifest.versions.size} 个版本，示例可用：1.20.1）",
            )
        }

        val selection = meta.loadFabricProfileWithFallback(mcVersion)
        val loaderVersion = selection.loaderVersion
        val versionId = instanceId
            ?: selection.profile.id.ifBlank { "fabric-loader-$loaderVersion-$mcVersion" }
        val profileText = meta.fabricProfileText(mcVersion, loaderVersion)
        val root = json.parseToJsonElement(profileText).jsonObject
        val libs = root["libraries"]?.jsonArray
            ?: throw LoaderInstallException("Fabric profile 缺少 libraries 字段")

        // Rewrite every library URL through DownloadSource; the version json keeps
        // the same libraries but with our source's candidate URL instead of the
        // upstream base URL.
        val rewritten = libs.map { element ->
            val lib = element.jsonObject
            val name = lib["name"]?.jsonPrimitive?.content
                ?: throw LoaderInstallException("Fabric library 缺少 name 字段")
            val (_, candidates) = mavenPathOf(name)
            JsonObject(lib.toMutableMap().apply {
                put("url", JsonPrimitive(candidates.first()))
            })
        }
        val versionJson = JsonObject(root.toMutableMap().apply {
            put("id", JsonPrimitive(versionId))
            put("inheritsFrom", JsonPrimitive(parentInstanceId))
            // Fabric has no jar of its own; point HMCL at the vanilla jar.
            put("jar", JsonPrimitive(parentInstanceId))
            put("libraries", JsonArray(rewritten))
        })
        val versionRel = "versions/$versionId/$versionId.json"
        fs.writeNew(versionRel, versionJson.toString().toByteArray(Charsets.UTF_8))
        log("fabric versionJson: $versionRel (id=$versionId, inheritsFrom=$parentInstanceId, " +
            "libraries=${libs.size}, mainClass=${selection.profile.mainClass})")

        // Already-present library files are counted as skipped (idempotent re-install).
        val specs = mutableListOf<DownloadSpec>()
        var skipped = 0
        libs.forEach { element ->
            val lib = element.jsonObject
            val name = lib["name"]?.jsonPrimitive?.content ?: return@forEach
            val (path, candidates) = mavenPathOf(name)
            val rel = "libraries/$path"
            if (fs.exists(rel)) {
                skipped++
                return@forEach
            }
            specs += DownloadSpec(
                relativePath = rel,
                candidates = candidates.map { CandidateUrl(it, source.id) },
                expectedSha1 = lib["sha1"]?.jsonPrimitive?.content,
                expectedSize = lib["size"]?.jsonPrimitive?.content?.toLongOrNull(),
            )
        }
        downloader.reset(specs.size, specs.sumOf { it.expectedSize ?: 0L })

        val sourceCounts = mutableMapOf<String, Int>()
        var downloaded = 0
        specs.forEach { spec ->
            val outcome = try {
                downloader.download(spec)
            } catch (e: Exception) {
                throw LoaderInstallException("Fabric 库下载失败：${spec.relativePath}；${e.message}")
            }
            downloaded++
            sourceCounts[outcome.sourceId] = (sourceCounts[outcome.sourceId] ?: 0) + 1
        }
        log("fabric libraries: total=${libs.size} downloaded=$downloaded skipped=$skipped sources=$sourceCounts")
        return LoaderInstallResult(
            versionId, loaderVersion, libs.size, downloaded, skipped, sourceCounts, selection.attempts,
        )
    }
}
