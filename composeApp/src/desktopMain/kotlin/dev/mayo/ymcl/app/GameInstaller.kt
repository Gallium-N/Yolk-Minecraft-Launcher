package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.VersionEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * P4 step 0: install a vanilla instance into a shared game root.
 *
 * Shared files (asset index, asset objects, libraries, client jar) live once at
 * the root of [fs] (`<gameRoot>/assets`, `<gameRoot>/libraries`); per-instance
 * files (version json, version jar, natives, instance configs) live under
 * `versions/<instanceId>/`. The client jar is downloaded to a shared path once
 * and hard-linked into every instance, so a second 1.20.1 install downloads
 * only its tiny version json. The downloader's validation logic is untouched.
 *
 * [instanceId] defaults to [mcVersion] so the P3 self-checks keep working with
 * a single-root world model.
 */
class GameInstaller(
    private val source: DownloadSource,
    private val downloader: GameDownloader,
    private val fs: DownloadFileSystem,
    private val log: (String) -> Unit = ::println,
) {
    data class Summary(
        val files: Int,
        /** Total logical bytes of all planned files (cached ones included). */
        val bytes: Long,
        /** Bytes actually fetched from the network (excludes cached/skipped files). */
        val downloadedBytes: Long,
        val elapsedMs: Long,
        val failures: Int,
        val skipped: Int,
        val redownloaded: Int,
        val errors: List<String>,
        val shared: Int,
        val instance: Int,
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

    private fun scoped(scope: DownloadScope, instanceId: String, rel: String): String = when (scope) {
        DownloadScope.SHARED -> rel
        DownloadScope.INSTANCE -> "versions/$instanceId/$rel"
    }

    fun install(mcVersion: String, entry: VersionEntry, instanceId: String = mcVersion): Summary {
        val start = System.currentTimeMillis()

        // ---- Phase A: version json + asset index, downloaded once and counted ----
        val versionRel = scoped(DownloadScope.INSTANCE, instanceId, "$instanceId.json")
        val versionSpec = DownloadSpec(
            relativePath = versionRel,
            candidates = source.versionJson(mcVersion, entry.url).withSources(source.id),
            expectedSha1 = entry.sha1.ifBlank { null },
            scope = DownloadScope.INSTANCE,
        )
        val versionOutcome = downloader.download(versionSpec)
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
                scope = DownloadScope.SHARED,
            )
        }
        val indexOutcome = indexSpec?.let { downloader.download(it) }

        // ---- Phase B: build the shared-file specs (jar / libraries / assets) ----
        val specs = mutableListOf<DownloadSpec>()

        // Client jar: one shared copy, hard-linked into each instance afterwards.
        val sharedJarRel = scoped(DownloadScope.SHARED, instanceId, "libraries/ymcl-clientjar/$mcVersion.jar")
        version["downloads"]?.jsonObject?.get("client")?.jsonObject?.let { client ->
            specs += DownloadSpec(
                relativePath = sharedJarRel,
                candidates = source.clientJar(mcVersion, client["url"]!!.jsonPrimitive.content)
                    .withSources(source.id),
                expectedSha1 = client["sha1"]?.jsonPrimitive?.content,
                expectedSize = client["size"]?.jsonPrimitive?.content?.toLongOrNull(),
                scope = DownloadScope.SHARED,
            )
        }

        // Libraries: shared; only linux native jars are unpacked into the instance.
        (version["libraries"]?.jsonArray?.toList() ?: emptyList()).forEach { element ->
            val lib = element.jsonObject
            val downloads = lib["downloads"]?.jsonObject
            val artifact = downloads?.get("artifact")?.jsonObject
            val path = artifact?.get("path")?.jsonPrimitive?.content
            val name = lib["name"]?.jsonPrimitive?.content.orEmpty()
            if (path != null) {
                val linuxNative = name.contains(":natives-linux") || path.contains("-natives-linux")
                specs += DownloadSpec(
                    relativePath = scoped(DownloadScope.SHARED, instanceId, "libraries/$path"),
                    candidates = source.library(path).withSources(source.id),
                    expectedSha1 = artifact["sha1"]?.jsonPrimitive?.content,
                    expectedSize = artifact["size"]?.jsonPrimitive?.content?.toLongOrNull(),
                    unpackNativesTo = if (linuxNative) scoped(DownloadScope.INSTANCE, instanceId, "natives") else null,
                    scope = DownloadScope.SHARED,
                )
            }
            // Legacy (1.6–1.12) version json keeps the real LWJGL/JInput natives
            // under downloads.classifiers["natives-linux"], while downloads.artifact
            // is a 22-byte dummy. HMCL resolves that classifier path at launch, so
            // it must be present (and unpacked) or old clients fail with
            // NoSuchFileException before the main menu.
            downloads?.get("classifiers")?.jsonObject?.forEach { (classifier, value) ->
                if (!classifier.startsWith("natives-linux")) return@forEach
                val info = value.jsonObject
                val nativePath = info["path"]?.jsonPrimitive?.content ?: return@forEach
                specs += DownloadSpec(
                    relativePath = scoped(DownloadScope.SHARED, instanceId, "libraries/$nativePath"),
                    candidates = source.library(nativePath).withSources(source.id),
                    expectedSha1 = info["sha1"]?.jsonPrimitive?.content,
                    expectedSize = info["size"]?.jsonPrimitive?.content?.toLongOrNull(),
                    unpackNativesTo = scoped(DownloadScope.INSTANCE, instanceId, "natives"),
                    scope = DownloadScope.SHARED,
                )
            }
        }

        // Asset objects: shared, content-addressed by their own sha1.
        if (indexSpec != null && fs.exists(indexRel)) {
            val objects = parse(indexRel)["objects"]?.jsonObject ?: emptyMap()
            objects.forEach { (_, value) ->
                val obj = value.jsonObject
                val hash = obj["hash"]?.jsonPrimitive?.content ?: return@forEach
                val size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull()
                specs += DownloadSpec(
                    relativePath = scoped(DownloadScope.SHARED, instanceId, "assets/objects/${hash.take(2)}/$hash"),
                    candidates = source.asset(hash).withSources(source.id),
                    expectedSha1 = hash,
                    expectedSize = size,
                    scope = DownloadScope.SHARED,
                )
            }
            log("assetIndex parsed: objects=${objects.size}")
        }

        val allSpecs = listOf(versionSpec) + listOfNotNull(indexSpec) + specs
        val sharedCount = allSpecs.count { it.scope == DownloadScope.SHARED }
        val instanceCount = allSpecs.size - sharedCount
        val totalBytes = allSpecs.sumOf { it.expectedSize ?: 0L }
        downloader.reset(allSpecs.size, totalBytes)
        log("install plan: files=${allSpecs.size} shared=$sharedCount instance=$instanceCount " +
            "expectedBytes=$totalBytes")

        // ---- Phase C: download the shared files concurrently ----
        val pool = Executors.newFixedThreadPool(8)
        val results = try {
            val futures = specs.map { spec ->
                pool.submit(Callable { runCatching { downloader.download(spec) } })
            }
            futures.map { it.get() }
        } finally {
            pool.shutdown()
        }

        val outcomes = buildList {
            add(versionOutcome)
            indexOutcome?.let { add(it) }
            addAll(results.mapNotNull { it.getOrNull() })
        }
        val errors = results.mapNotNull { it.exceptionOrNull()?.message }

        // ---- Make the instance self-contained for HMCL after all downloads ----
        if (version["id"]?.jsonPrimitive?.content != instanceId) {
            val rewritten = JsonObject(version.toMutableMap().apply {
                put("id", JsonPrimitive(instanceId))
            })
            fs.writeNew(versionRel, rewritten.toString().toByteArray(Charsets.UTF_8))
        }
        val instanceJarRel = scoped(DownloadScope.INSTANCE, instanceId, "$instanceId.jar")
        if (!fs.exists(instanceJarRel)) {
            fs.linkOrCopy(sharedJarRel, instanceJarRel)
            log("client jar linked: $sharedJarRel -> $instanceJarRel")
        }
        // ---- Phase D: legacy virtual / map_to_resources assets (once, idempotent) ----
        val virtual = virtualizeLegacyAssets(mcVersion, indexRel)
        writeInstanceMetadata(mcVersion, instanceId, virtual)

        val bytes = outcomes.sumOf { it.bytes }
        val downloadedBytes = outcomes.filter { !it.skipped }.sumOf { it.bytes }
        val summary = Summary(
            files = allSpecs.size,
            bytes = bytes,
            downloadedBytes = downloadedBytes,
            elapsedMs = System.currentTimeMillis() - start,
            failures = errors.size,
            skipped = outcomes.count { it.skipped },
            redownloaded = outcomes.count { it.redownloaded },
            errors = errors,
            shared = sharedCount,
            instance = instanceCount,
        )
        log("install summary: files=${summary.files} bytes=${summary.bytes} " +
            "downloadedBytes=${summary.downloadedBytes} " +
            "elapsedMs=${summary.elapsedMs} failures=${summary.failures} " +
            "skipped=${summary.skipped} redownloaded=${summary.redownloaded} " +
            "shared=$sharedCount instance=$instanceCount")
        errors.forEach { log("INSTALL ERROR: $it") }
        return summary
    }

    /**
     * Copies asset objects into `assets/virtual/<mcVersion>/<original name>` for
     * 1.6–1.12 (and into `<gameRoot>/resources/` when map_to_resources applies).
     * Idempotent: already-present targets are left untouched. Runs only at
     * install time; `instance.json` records `assetsVirtual=true` for launch.
     */
    private fun virtualizeLegacyAssets(mcVersion: String, indexRel: String): Boolean {
        if (!fs.exists(indexRel)) return false
        val plan = try {
            LegacyAssets.plan(String(fs.readAll(indexRel), Charsets.UTF_8), mcVersion)
        } catch (e: Exception) {
            log("virtual assets: index unreadable: ${e.message}")
            return false
        } ?: return false
        var copied = 0
        var missing = 0
        plan.copies.forEach { (from, to) ->
            if (!fs.exists(from)) {
                missing++
                return@forEach
            }
            if (!fs.exists(to)) {
                fs.writeNew(to, fs.readAll(from))
                copied++
            }
        }
        if (plan.mapToResources) {
            plan.resourceCopies.forEach { (from, to) ->
                if (fs.exists(from) && !fs.exists(to)) fs.writeNew(to, fs.readAll(from))
            }
        }
        log(
            "virtual assets: root=${plan.root} objects=${plan.objectCount} copied=$copied " +
                "map_to_resources=${plan.mapToResources} dropped=${plan.dropped} missing=$missing",
        )
        return true
    }

    private fun writeInstanceMetadata(mcVersion: String, instanceId: String, virtual: Boolean) {
        val portableRel = "versions/$instanceId/instance.json"
        if (!fs.exists(portableRel)) {
            val text = buildJsonObject {
                put("schemaVersion", 1)
                put("id", instanceId)
                put("displayName", instanceId)
                put("mcVersion", mcVersion)
                put("loader", buildJsonObject { put("kind", "vanilla") })
                // A version dir named exactly after the MC version is the shared
                // base other instances inherit from; hide it from the instance list.
                if (instanceId == mcVersion) put("role", "base")
                if (virtual) put("assetsVirtual", true)
            }.toString()
            fs.writeNew(portableRel, text.toByteArray(Charsets.UTF_8))
        } else if (virtual) {
            // Reinstall: patch the flag onto an existing metadata file (idempotent).
            val obj = runCatching {
                json.parseToJsonElement(String(fs.readAll(portableRel), Charsets.UTF_8)).jsonObject.toMutableMap()
            }.getOrNull()
            if (obj != null && obj["assetsVirtual"] == null) {
                obj["assetsVirtual"] = JsonPrimitive(true)
                fs.writeNew(portableRel, JsonObject(obj).toString().toByteArray(Charsets.UTF_8))
            }
        }
        val machineRel = "versions/$instanceId/machine.local.json"
        if (!fs.exists(machineRel)) {
            val text = buildJsonObject {
                put("schemaVersion", 1)
                put("xmxMb", 4096)
            }.toString()
            fs.writeNew(machineRel, text.toByteArray(Charsets.UTF_8))
        }
    }
}
