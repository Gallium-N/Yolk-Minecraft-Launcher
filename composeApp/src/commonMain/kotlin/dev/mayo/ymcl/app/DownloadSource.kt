package dev.mayo.ymcl.app

// Every network URL literal lives in this file only. The phase-3 constraint is:
//   grep -rn "https://" composeApp/src --include=*.kt | grep -v DownloadSource
// must be empty.
private const val BMCLAPI = "https://bmclapi2.bangbang93.com"
private const val MOJANG_META = "https://piston-meta.mojang.com"
private const val MOJANG_LIBRARIES = "https://libraries.minecraft.net"
private const val MOJANG_ASSETS = "https://resources.download.minecraft.net"
private const val FABRIC_MAVEN = "https://maven.fabricmc.net"
private const val FABRIC_META = "https://meta.fabricmc.net"
private const val FORGE_MAVEN = "https://maven.minecraftforge.net"
private const val NEOFORGE_MAVEN = "https://maven.neoforged.net"
private const val MODRINTH_API = "https://api.modrinth.com/v2"
private const val UNREACHABLE = "https://127.0.0.1:9"

private const val FABRIC_LOADER_METADATA_PATH = "net/fabricmc/fabric-loader/maven-metadata.xml"

/**
 * Returns an ordered candidate URL list per artifact. The caller tries them in
 * order and switches to the next one on 404 / 5xx / timeout. A 302 is a normal
 * path (BMCLAPI /maven redirects to third-party CDNs), not a failure.
 */
interface DownloadSource {
    val id: String
    fun versionManifest(): List<String>
    fun versionJson(id: String, url: String): List<String>
    fun library(path: String): List<String>
    fun asset(hash: String): List<String>
    fun clientJar(versionId: String, url: String): List<String>
    fun loaderMaven(group: String, artifact: String, version: String, classifier: String?, ext: String): List<String>
    fun fabricLoaderMetadata(mcVersion: String): List<String>
    fun fabricProfile(mcVersion: String, loaderVersion: String): List<String>

    /** BMCLAPI has no asset-index endpoint (probed 404); the manifest URL is authoritative. */
    fun assetIndex(id: String, url: String): List<String> = listOf(url)

    // ---- Modrinth (P4 step-2). Single candidate each: no CN mirror exists. ----
    // Defaults so every existing source / test fake keeps compiling unchanged.
    /** [facets] is the raw 2-D JSON string, e.g. [["project_type:mod"],...]. */
    fun modrinthSearch(
        query: String,
        facets: String,
        offset: Int = 0,
        limit: Int = 20,
        popular: Boolean = false,
    ): List<String> = listOf(modrinthSearchUrl(query, facets, offset, limit, popular))

    fun modrinthProject(idOrSlug: String): List<String> =
        listOf("$MODRINTH_API/project/${percentEncode(idOrSlug)}")

    /** game_versions / loaders are strict JSON arrays; the instance's mc + loader decide them. */
    fun modrinthVersions(idOrSlug: String, mcVersion: String, loader: String): List<String> =
        listOf(
            modrinthUrl(
                "/project/${percentEncode(idOrSlug)}/version",
                "game_versions" to "[\"$mcVersion\"]",
                "loaders" to "[\"$loader\"]",
            ),
        )

    fun modrinthVersion(versionId: String): List<String> =
        listOf("$MODRINTH_API/version/${percentEncode(versionId)}")

    /** CDN url comes from version.files[].url; not a new host literal. */
    fun modrinthFile(url: String): List<String> = listOf(url)
}

/** Percent-encode an arbitrary UTF-8 string without java.net (commonMain safe). */
private val HEX = "0123456789ABCDEF".toCharArray()

private fun percentEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val v = byte.toInt() and 0xFF
        val c = v.toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
            append(c)
        } else {
            append('%').append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
    }
}

private fun modrinthUrl(path: String, vararg params: Pair<String, String>): String {
    val query = params.joinToString("&") { (k, v) -> "$k=${percentEncode(v)}" }
    return "$MODRINTH_API$path?$query"
}

/** Shared by every source: Modrinth has no mirror, so candidate lists are length 1. */
private fun modrinthSearchUrl(query: String, facets: String, offset: Int, limit: Int, popular: Boolean): String {
    val params = mutableListOf<Pair<String, String>>()
    // Empty query -> popular feed (index=downloads, no query param).
    if (query.isNotBlank()) params += "query" to query.trim()
    params += "limit" to limit.toString()
    params += "offset" to offset.toString()
    params += "index" to if (popular || query.isBlank()) "downloads" else "relevance"
    params += "facets" to facets
    return modrinthUrl("/search", *params.toTypedArray())
}

private fun mavenPath(
    group: String,
    artifact: String,
    version: String,
    classifier: String?,
    ext: String,
): String = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version" +
    (classifier?.let { "-$it" } ?: "") + ".$ext"

object BmclapiDownloadSource : DownloadSource {
    override val id = "bmclapi"

    override fun versionManifest(): List<String> = listOf(
        "$BMCLAPI/mc/game/version_manifest_v2.json",
        "$MOJANG_META/mc/game/version_manifest_v2.json",
    )

    override fun versionJson(id: String, url: String): List<String> = listOf(
        "$BMCLAPI/versions/$id/$id.json",
        url,
    )

    override fun library(path: String): List<String> = listOf(
        "$BMCLAPI/maven/$path",
        "$MOJANG_LIBRARIES/$path",
    )

    override fun asset(hash: String): List<String> = listOf(
        "$BMCLAPI/assets/${hash.take(2)}/$hash",
        "$MOJANG_ASSETS/${hash.take(2)}/$hash",
    )

    override fun clientJar(versionId: String, url: String): List<String> = listOf(
        "$BMCLAPI/versions/$versionId/$versionId.jar",
        url,
    )

    override fun loaderMaven(
        group: String,
        artifact: String,
        version: String,
        classifier: String?,
        ext: String,
    ): List<String> {
        val path = mavenPath(group, artifact, version, classifier, ext)
        return listOf("$BMCLAPI/maven/$path", "$FABRIC_MAVEN/$path", "$FORGE_MAVEN/$path")
    }

    override fun fabricLoaderMetadata(mcVersion: String): List<String> = listOf(
        "$FABRIC_MAVEN/$FABRIC_LOADER_METADATA_PATH",
        "$FABRIC_META/v2/versions/loader/$mcVersion",
    )

    override fun fabricProfile(mcVersion: String, loaderVersion: String): List<String> = listOf(
        "$FABRIC_META/v2/versions/loader/$mcVersion/$loaderVersion/profile/json",
    )
}

object OfficialDownloadSource : DownloadSource {
    override val id = "official"

    override fun versionManifest(): List<String> = listOf(
        "$MOJANG_META/mc/game/version_manifest_v2.json",
        "$BMCLAPI/mc/game/version_manifest_v2.json",
    )

    override fun versionJson(id: String, url: String): List<String> = listOf(
        url,
        "$BMCLAPI/versions/$id/$id.json",
    )

    override fun library(path: String): List<String> = listOf(
        "$MOJANG_LIBRARIES/$path",
        "$BMCLAPI/maven/$path",
    )

    override fun asset(hash: String): List<String> = listOf(
        "$MOJANG_ASSETS/${hash.take(2)}/$hash",
        "$BMCLAPI/assets/${hash.take(2)}/$hash",
    )

    override fun clientJar(versionId: String, url: String): List<String> = listOf(
        url,
        "$BMCLAPI/versions/$versionId/$versionId.jar",
    )

    override fun loaderMaven(
        group: String,
        artifact: String,
        version: String,
        classifier: String?,
        ext: String,
    ): List<String> {
        val path = mavenPath(group, artifact, version, classifier, ext)
        return listOf(
            "$FABRIC_MAVEN/$path",
            "$FORGE_MAVEN/$path",
            "$NEOFORGE_MAVEN/releases/$path",
            "$BMCLAPI/maven/$path",
        )
    }

    override fun fabricLoaderMetadata(mcVersion: String): List<String> = listOf(
        "$FABRIC_MAVEN/$FABRIC_LOADER_METADATA_PATH",
        "$FABRIC_META/v2/versions/loader/$mcVersion",
    )

    override fun fabricProfile(mcVersion: String, loaderVersion: String): List<String> = listOf(
        "$FABRIC_META/v2/versions/loader/$mcVersion/$loaderVersion/profile/json",
    )
}

/** Self-check only: every candidate points at an unreachable address. */
object UnreachableDownloadSource : DownloadSource {
    override val id = "unreachable"
    private fun dead(): List<String> = listOf("$UNREACHABLE/unreachable")
    override fun versionManifest(): List<String> = dead()
    override fun versionJson(id: String, url: String): List<String> = dead()
    override fun library(path: String): List<String> = dead()
    override fun asset(hash: String): List<String> = dead()
    override fun clientJar(versionId: String, url: String): List<String> = dead()
    override fun loaderMaven(group: String, artifact: String, version: String, classifier: String?, ext: String): List<String> = dead()
    override fun fabricLoaderMetadata(mcVersion: String): List<String> = dead()
    override fun fabricProfile(mcVersion: String, loaderVersion: String): List<String> = dead()
    override fun assetIndex(id: String, url: String): List<String> = dead()
    override fun modrinthSearch(query: String, facets: String, offset: Int, limit: Int, popular: Boolean): List<String> = dead()
    override fun modrinthProject(idOrSlug: String): List<String> = dead()
    override fun modrinthVersions(idOrSlug: String, mcVersion: String, loader: String): List<String> = dead()
    override fun modrinthVersion(versionId: String): List<String> = dead()
    override fun modrinthFile(url: String): List<String> = dead()
}

fun defaultDownloadSource(preferMirror: Boolean = true): DownloadSource =
    if (preferMirror) BmclapiDownloadSource else OfficialDownloadSource
