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
}

fun defaultDownloadSource(preferMirror: Boolean = true): DownloadSource =
    if (preferMirror) BmclapiDownloadSource else OfficialDownloadSource
