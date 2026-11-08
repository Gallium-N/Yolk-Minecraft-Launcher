package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.InstanceStatus

/**
 * App-layer view of one instance returned by [InstanceRepository].
 *
 * The disk fields (`id`..`path`) are read from `<gameRoot>/versions/<id>/`;
 * runtime fields (`status`, `lastLaunchedAt`, `totalPlayTimeMs`,
 * `updateAvailable`) are filled in by AppState as the app runs. [path] is for
 * display and the 打开文件夹 action ONLY — the UI must never use it to do IO.
 */
data class InstanceSummary(
    val id: String,
    val displayName: String,
    val mcVersion: String,
    /** One of: vanilla | fabric | forge | quilt | neoforge. */
    val loaderKind: String = "vanilla",
    val loaderVersion: String? = null,
    val isolated: Boolean = true,
    val path: String = "",
    // ---- runtime / display state owned by AppState ----
    val modCount: Int = 0,
    val totalPlayTimeMs: Long = 0L,
    val lastLaunchedAt: Long? = null,
    val status: InstanceStatus = InstanceStatus.READY,
    val updateAvailable: Boolean = false,
) {
    /** e.g. "Fabric 0.20.1", "Forge 47.4.23", "原版". */
    val loaderLine: String
        get() {
            val name = when (loaderKind.lowercase()) {
                "fabric" -> "Fabric"
                "forge" -> "Forge"
                "neoforge" -> "NeoForge"
                "quilt" -> "Quilt"
                else -> "原版"
            }
            return if (loaderVersion.isNullOrBlank() || name == "原版") name else "$name $loaderVersion"
        }

    val subLine: String get() = "$mcVersion · $loaderLine"
}
