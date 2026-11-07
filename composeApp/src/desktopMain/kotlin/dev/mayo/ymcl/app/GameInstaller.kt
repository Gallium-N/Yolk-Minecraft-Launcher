package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.VersionEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * P3 step 3: install a vanilla instance into an isolated directory.
 * Layout follows vanilla/HMCL; no loader install, no modpack, no UI.
 */
class GameInstaller(
    private val source: DownloadSource,
    private val downloader: GameDownloader,
    private val fs: DownloadFileSystem,
    private val log: (String) -> Unit = ::println,
) {
    data class Summary(
        val files: Int,
        val bytes: Long,
        val elapsedMs: Long,
        val failures: Int,
        val skipped: Int,
        val redownloaded: Int,
        val errors: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun List<String>.withSources(primary: String): List<CandidateUrl> = map { url ->
        val id = when {
            url.contains("bmclapi") -> "bmclapi"
            url.contains("mojang") || url.contains("piston") || url.contains("minecraft.net") ||
                url.contains("fabricmc") || url.contains("minecraftforge") || url.contains("neoforged") -> "official"
            else -> primary
        }
        CandidateUrl(url, id)
    }

    private fun parse(relativePath: String): JsonObject =
        json.parseToJsonElement(String(fs.readAll(relativePath), Charsets.UTF_8)).jsonObject

    fun install(versionId: String, entry: VersionEntry): Summary {
        val start = System.currentTimeMillis()
        val versionRel = "versions/$versionId/$versionId.json"
        val versionSpec = DownloadSpec(
            relativePath = versionRel,
            candidates = source.versionJson(versionId, entry.url).withSources(source.id),
            expectedSha1 = entry.sha1.ifBlank { null },
        )

        // Version json and asset index are needed before the object list can be built.
        downloader.download(versionSpec)
        val version = parse(versionRel)
        log("versionJson parsed: id=${version["id"]?.jsonPrimitive?.content} " +
            "libraries=${version["libraries"]?.jsonArray?.size ?: 0}")

        val assetIndexInfo = version["assetIndex"]?.jsonObject
        val assetIndexId = assetIndexInfo?.get("id")?.jsonPrimitive?.content
            ?: version["assets"]?.jsonPrimitive?.content ?: "legacy"
        val indexRel = "assets/indexes/$assetIndexId.json"
        val indexSpec = assetIndexInfo?.let { info ->
            DownloadSpec(
                relativePath = indexRel,
                candidates = source.assetIndex(assetIndexId, info["url"]!!.jsonPrimitive.content)
                    .withSources(source.id),
                expectedSha1 = info["sha1"]?.jsonPrimitive?.content,
                expectedSize = info["size"]?.jsonPrimitive?.content?.toLongOrNull(),
            )
        }
        if (indexSpec != null) downloader.download(indexSpec)

        val specs = mutableListOf<DownloadSpec>()
        specs += versionSpec
        if (indexSpec != null) specs += indexSpec

        // Client jar.
        version["downloads"]?.jsonObject?.get("client")?.jsonObject?.let { client ->
            specs += DownloadSpec(
                relativePath = "versions/$versionId/$versionId.jar",
                candidates = source.clientJar(versionId, client["url"]!!.jsonPrimitive.content)
                    .withSources(source.id),
                expectedSha1 = client["sha1"]?.jsonPrimitive?.content,
                expectedSize = client["size"]?.jsonPrimitive?.content?.toLongOrNull(),
            )
        }

        // Libraries (all of them; only linux native jars are unpacked).
        (version["libraries"]?.jsonArray?.toList() ?: emptyList()).forEach { element ->
            val lib = element.jsonObject
            val artifact = lib["downloads"]?.jsonObject?.get("artifact")?.jsonObject ?: return@forEach
            val path = artifact["path"]?.jsonPrimitive?.content ?: return@forEach
            val name = lib["name"]?.jsonPrimitive?.content.orEmpty()
            val linuxNative = name.contains(":natives-linux") || path.contains("-natives-linux")
            specs += DownloadSpec(
                relativePath = "libraries/$path",
                candidates = source.library(path).withSources(source.id),
                expectedSha1 = artifact["sha1"]?.jsonPrimitive?.content,
                expectedSize = artifact["size"]?.jsonPrimitive?.content?.toLongOrNull(),
                unpackNativesTo = if (linuxNative) "natives" else null,
            )
        }

        // Asset objects, keyed by their own sha1 (content-addressed).
        if (indexSpec != null && fs.exists(indexRel)) {
            val objects = parse(indexRel)["objects"]?.jsonObject ?: emptyMap()
            objects.forEach { (_, value) ->
                val obj = value.jsonObject
                val hash = obj["hash"]?.jsonPrimitive?.content ?: return@forEach
                val size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull()
                specs += DownloadSpec(
                    relativePath = "assets/objects/${hash.take(2)}/$hash",
                    candidates = source.asset(hash).withSources(source.id),
                    expectedSha1 = hash,
                    expectedSize = size,
                )
            }
            log("assetIndex parsed: objects=${objects.size}")
        }

        val totalBytes = specs.sumOf { it.expectedSize ?: 0L }
        downloader.reset(specs.size, totalBytes)
        log("install plan: files=${specs.size} expectedBytes=$totalBytes")

        val pool = Executors.newFixedThreadPool(8)
        val results = try {
            val futures = specs.map { spec ->
                pool.submit(Callable { runCatching { downloader.download(spec) } })
            }
            futures.map { it.get() }
        } finally {
            pool.shutdown()
        }

        val outcomes = results.mapNotNull { it.getOrNull() }
        val errors = results.mapNotNull { it.exceptionOrNull()?.message }
        val bytes = outcomes.sumOf { it.bytes }
        val summary = Summary(
            files = specs.size,
            bytes = bytes,
            elapsedMs = System.currentTimeMillis() - start,
            failures = errors.size,
            skipped = outcomes.count { it.skipped },
            redownloaded = outcomes.count { it.redownloaded },
            errors = errors,
        )
        log("install summary: files=${summary.files} bytes=${summary.bytes} " +
            "elapsedMs=${summary.elapsedMs} failures=${summary.failures} " +
            "skipped=${summary.skipped} redownloaded=${summary.redownloaded}")
        errors.forEach { log("INSTALL ERROR: $it") }
        return summary
    }
}
