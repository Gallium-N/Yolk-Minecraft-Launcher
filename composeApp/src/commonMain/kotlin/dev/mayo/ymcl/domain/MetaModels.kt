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

/** Wrapper persisted at data/meta/fabric/<mc>-loaders.json (TTL 24h, stale-while-revalidate). */
@Serializable
data class CachedLoaderVersions(
    val schemaVersion: Int = 1,
    val fetchedAt: Long = 0L,
    val versions: List<String> = emptyList(),
)

/** Probe result persisted at data/meta/probe/<loader>.json (TTL 5 min). */
@Serializable
data class CachedProbe(
    val schemaVersion: Int = 1,
    val fetchedAt: Long = 0L,
    val reachable: Boolean = false,
    /** Short label of the candidate that answered, e.g. "bmclapi" / "official". */
    val via: String = "",
    /** Short labels of every candidate tried, in order. */
    val tried: List<String> = emptyList(),
    /** Human-readable note (e.g. what was tried) shown when unreachable. */
    val note: String = "",
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
    val sha1: String? = null,
    val size: Long? = null,
    val md5: String? = null,
    val sha256: String? = null,
)
