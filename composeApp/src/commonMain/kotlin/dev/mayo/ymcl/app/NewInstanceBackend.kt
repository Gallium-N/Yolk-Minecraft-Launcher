package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.LoaderKind
import dev.mayo.ymcl.domain.VersionEntry

/** One install stage shown by the wizard / modpack progress component. */
data class InstallStage(
    val name: String,
    val done: Int,
    val total: Int,
    val bytes: Long,
)

data class NewInstanceResult(
    val instanceId: String,
    val failures: List<String>,
    val cancelled: Boolean,
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
}
