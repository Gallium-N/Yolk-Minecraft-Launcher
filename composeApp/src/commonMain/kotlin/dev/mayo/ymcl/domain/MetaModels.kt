package dev.mayo.ymcl.domain

import kotlinx.serialization.Serializable

/** One entry of Mojang's version_manifest_v2.json. Unknown fields are tolerated. */
@Serializable
data class VersionEntry(
    val id: String,
    val type: String = "release",
    val url: String = "",
    val sha1: String = "",
    val releaseTime: String = "",
)

@Serializable
data class VersionManifest(
    val versions: List<VersionEntry> = emptyList(),
)

/** Wrapper persisted at data/meta/version_manifest_v2.json. */
@Serializable
data class CachedManifest(
    val schemaVersion: Int = 1,
    val fetchedAt: Long = 0L,
    val manifest: VersionManifest = VersionManifest(),
)

/** Only the fields P3 needs from a Fabric meta-API profile json. */
@Serializable
data class FabricProfile(
    val id: String = "",
    val inheritsFrom: String = "",
    val mainClass: String = "",
    val libraries: List<FabricLibrary> = emptyList(),
)

@Serializable
data class FabricLibrary(
    val name: String = "",
    val url: String? = null,
)
