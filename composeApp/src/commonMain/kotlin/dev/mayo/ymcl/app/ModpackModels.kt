package dev.mayo.ymcl.app

/** Where the pack came from. Persisted into the instance.json we generate. */
enum class PackSource(val id: String) {
    MRPACK("mrpack"),
    CURSEFORGE("curseforge"),
}

/**
 * One file the pack wants inside the instance directory. [path] is relative to
 * the instance root (e.g. `mods/x.jar`, `config/y.json`) and has already been
 * checked by [ModpackParser.safePackPath]. CurseForge files carry only
 * [cfProjectId]/[cfFileId] until [CurseForgeFileResolver] fills in [downloads].
 */
data class PackFile(
    val path: String,
    val size: Long,
    val sha1: String?,
    val downloads: List<String>,
    val cfProjectId: String? = null,
    val cfFileId: String? = null,
)

/** An override file, already priority-resolved (client-overrides wins). */
data class PackOverride(val path: String, val bytes: ByteArray)

/** Everything the confirm page needs, produced by the pure parser. */
data class ModpackPlan(
    val source: PackSource,
    val name: String,
    val packVersion: String,
    val mcVersion: String,
    /** vanilla | fabric | forge | quilt | neoforge. */
    val loaderKind: String,
    val loaderVersion: String?,
    val files: List<PackFile>,
    val overrides: List<PackOverride>,
    /** Count of files[] whose env.client == "unsupported" (pure server mods). */
    val skippedServerOnly: Int,
    /** Count of entries dropped by the zip-slip / absolute-path / drive guard. */
    val droppedUnsafe: Int,
    val warnings: List<String>,
    /** Raw client-overrides/.launcher/instance.json, our own merge convention. */
    val instanceConfigJson: String?,
    val suggestedId: String,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

data class ImportProgress(
    val stage: String,
    val doneFiles: Int,
    val totalFiles: Int,
    val doneBytes: Long,
    val totalBytes: Long,
    val currentFile: String?,
)

data class ImportSummary(
    val instanceId: String,
    val source: PackSource,
    val files: Int,
    val bytes: Long,
    val skippedServerOnly: Int,
    val overrides: Int,
    val failures: List<String>,
    val warnings: List<String>,
    val cancelled: Boolean,
    /** Files written without a sha1 to check (CurseForge has none). */
    val unverified: Int,
) {
    val ok: Boolean get() = failures.isEmpty() && !cancelled
}

class ModpackParseException(message: String) : Exception(message)
class ModpackInstallException(message: String) : Exception(message)
