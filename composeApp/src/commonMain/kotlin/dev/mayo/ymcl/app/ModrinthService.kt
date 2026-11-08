package dev.mayo.ymcl.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** A search hit enriched with the best compatible version for the instance. */
data class ModrinthResultRow(
    val hit: ModrinthHit,
    val version: ModrinthVersion?,
    val versionCount: Int,
)

/** Modrinth loader id for an instance; null for vanilla (mods don't apply). */
fun modLoaderOf(instance: InstanceSummary): String? = when (instance.loaderKind.lowercase()) {
    "fabric" -> "fabric"
    "forge" -> "forge"
    "quilt" -> "quilt"
    "neoforge" -> "neoforge"
    else -> null
}

/**
 * Composes the API client, pure resolver and installer for the UI. All network
 * work happens here (never in the Compose layer); AppState drives it.
 */
class ModrinthService(
    private val client: ModrinthClient,
    private val installer: ModInstaller,
    private val log: (String) -> Unit = ::println,
) {
    private val slugCache = mutableMapOf<String, String>()

    private fun slugOf(projectId: String): String = slugCache.getOrPut(projectId) {
        runCatching { client.project(projectId).slug }.getOrDefault(projectId)
    }

    suspend fun search(instance: InstanceSummary, query: String): List<ModrinthResultRow> {
        val loader = modLoaderOf(instance)
            ?: return emptyList() // vanilla: no mod loader
        val hits = withContext(Dispatchers.IO) {
            client.search(query, instance.mcVersion, loader, popular = query.isBlank())
        }
        // Resolve the best compatible version per hit concurrently (20 hits max).
        return coroutineScope {
            hits.map { hit ->
                async(Dispatchers.IO) {
                    val versions = runCatching {
                        client.versions(hit.slug, instance.mcVersion, loader)
                    }.getOrDefault(emptyList())
                    ModrinthResultRow(hit, ModResolver.defaultVersion(versions), versions.size)
                }
            }.awaitAll()
        }
    }

    fun versions(instance: InstanceSummary, slug: String): List<ModrinthVersion> {
        val loader = modLoaderOf(instance) ?: return emptyList()
        return runCatching { client.versions(slug, instance.mcVersion, loader) }.getOrDefault(emptyList())
    }

    fun plan(instance: InstanceSummary, root: ModrinthVersion): ModResolver.Resolution =
        ModResolver.resolve(
            root = root,
            installed = installer.installedProjectIds(instance.id),
            lookup = { vid -> runCatching { client.version(vid) }.getOrNull() },
        )

    /**
     * Re-resolve with the user's optional ticks, log required dependencies, then
     * write to disk. Never proceeds with a blocker / resolver error.
     */
    fun install(
        instance: InstanceSummary,
        root: ModrinthVersion,
        selectedOptionalIds: Set<String>,
        instanceRunning: Boolean,
        onProgress: (String) -> Unit = {},
    ): ModInstallReport {
        val resolution = ModResolver.resolve(
            root = root,
            installed = installer.installedProjectIds(instance.id),
            lookup = { vid -> runCatching { client.version(vid) }.getOrNull() },
            selectedOptionalIds = selectedOptionalIds,
        )
        if (resolution.blocked != null) throw ModInstallException(resolution.blocked!!)
        if (resolution.errors.isNotEmpty()) {
            throw ModInstallException("依赖解析失败：" + resolution.errors.joinToString("; "))
        }
        resolution.install.filter { it.reason == "required" }.forEach { p ->
            log("resolved dependency ${slugOf(p.version.projectId)}-${p.version.versionNumber} (required)")
        }
        return installer.install(instance.id, resolution.install, instanceRunning, onProgress)
    }
}
