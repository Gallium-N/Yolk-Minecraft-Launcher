package dev.mayo.ymcl.domain

data class ModEntry(
    val fileName: String,
    val name: String = "",
    val version: String = "",
)

/**
 * A playable instance. Pure domain data: no file paths, no java.io, no UI strings
 * that depend on the host. Machine-specific settings live in machine.local.json and
 * are deliberately not part of this model.
 */
data class Instance(
    val id: String,
    val displayName: String,
    val mcVersion: String,
    val loader: Loader = Loader.Vanilla,
    val mods: List<ModEntry> = emptyList(),
    val cover: String? = null,
    val totalPlayTimeMs: Long = 0L,
    val lastLaunchedAt: Long? = null,
    val status: InstanceStatus = InstanceStatus.READY,
    val updateAvailable: Boolean = false,
) {
    val modCount: Int get() = mods.size
    val versionLine: String get() = mcVersion
    val loaderLine: String get() = loader.display
    val subLine: String get() = "$mcVersion · ${loader.display}"
}
