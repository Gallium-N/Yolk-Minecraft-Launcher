package dev.mayo.ymcl.app

/** One file of a Modrinth version (CDN url comes from the API, never a literal). */
data class ModrinthFileRef(
    val url: String,
    val filename: String,
    val primary: Boolean,
    val sha1: String?,
    val sha512: String?,
    val size: Long,
)

/** dependency_type ∈ required | optional | incompatible | embedded. */
data class ModrinthDependency(
    val versionId: String?,
    val projectId: String?,
    val fileName: String?,
    val dependencyType: String,
)

data class ModrinthVersion(
    val id: String,
    val projectId: String,
    val versionNumber: String,
    val versionType: String,
    val name: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val files: List<ModrinthFileRef>,
    val dependencies: List<ModrinthDependency>,
    val datePublished: String,
) {
    val primaryFile: ModrinthFileRef?
        get() = files.firstOrNull { it.primary } ?: files.firstOrNull()
}

data class ModrinthHit(
    val projectId: String,
    val slug: String,
    val title: String,
    val description: String,
    val author: String,
    val downloads: Long,
    val iconUrl: String?,
    val latestVersionId: String?,
)

data class ModrinthProject(
    val id: String,
    val slug: String,
    val title: String,
    val description: String,
    val projectType: String,
)

/**
 * Human-friendly label for a raw Modrinth `version_number`. Modrinth stores
 * strings like `mc1.20.1-0.5.13-fabric` or `1.7.6+1.20.1`; the result page
 * shows just `0.5.13` plus a small loader chip instead of the raw string.
 */
data class VersionLabel(val version: String, val loader: String?)

private val MC_TOKEN = Regex("""(?i)mc\d+(?:\.\d+){1,3}""")
private val LOADER_WORD = Regex("""(?i)\b(fabric|forge|quilt|neoforge|quilted|sodium|iris)\b""")
private val VERSION_TOKEN = Regex("""\d+(?:\.\d+){1,3}(?:[-+._]?[0-9A-Za-z]+)*""")

fun versionLabelOf(raw: String, loaders: List<String>): VersionLabel {
    val loader = loaders.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: Regex("""(?i)(fabric|forge|quilt|neoforge)""").find(raw)?.value
    var rest = MC_TOKEN.replace(raw, " ")
    rest = LOADER_WORD.replace(rest, " ")
    val version = VERSION_TOKEN.find(rest)?.value?.trimEnd('-', '+', '.', '_', ' ')
        ?.takeIf { it.isNotBlank() } ?: raw.trim()
    return VersionLabel(version, loader)
}
