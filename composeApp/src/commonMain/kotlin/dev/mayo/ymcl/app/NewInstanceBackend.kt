package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.LoaderKind
import dev.mayo.ymcl.domain.VersionEntry

/** One install stage shown by the wizard / modpack progress component. */
data class InstallStage(
    val name: String,
    val done: Int,
    val total: Int,
    val bytes: Long,
    /** Real downloaded bytes (wizard + import), 0 when no download is active. */
    val totalBytes: Long = 0L,
    val currentFile: String = "",
)

data class NewInstanceResult(
    val instanceId: String,
    val failures: List<String>,
    val cancelled: Boolean,
)

/** An interrupted install (`installState: broken`) that can be discarded. */
data class BrokenInstall(
    val id: String,
    val displayName: String,
    val path: String,
)

/**
 * Boundary for the "new instance" wizard. The UI only ever sees this; the
 * desktop implementation reuses GameInstaller / LoaderInstaller, so no URL or
 * file IO happens in Compose code.
 */
interface NewInstanceBackend {
    fun existingIds(): Set<String>

    /** Mojang version manifest (cached + offline fallback). Throws on failure. */
    fun loadVersions(): List<VersionEntry>

    /** Available loader versions for [kind] on [mcVersion]; empty for vanilla. */
    fun loaderVersions(kind: LoaderKind, mcVersion: String): List<String>

    /** Whether the NeoForge official source is reachable (probe before enabling). */
    fun neoForgeReachable(): Boolean

    fun install(
        mcVersion: String,
        entry: VersionEntry,
        instanceId: String,
        displayName: String,
        kind: LoaderKind,
        loaderVersion: String?,
        onStage: (InstallStage) -> Unit,
    ): NewInstanceResult

    /** Stops downloads but KEEPS already-downloaded files for a later resume. */
    fun cancel()

    /** Instances whose install was cancelled/failed (`installState: broken`). */
    fun brokenInstalls(): List<BrokenInstall>

    /**
     * Deletes ONE unfinished install directory. Implementations MUST refuse
     * anything that is not a `broken` instance (normal instances, shared base
     * dirs, path traversal / absolute ids). Returns true when it was removed.
     */
    fun deleteBrokenInstall(instanceId: String): Boolean
}
