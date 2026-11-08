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
