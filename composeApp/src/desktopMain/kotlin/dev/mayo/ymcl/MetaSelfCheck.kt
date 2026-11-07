package dev.mayo.ymcl

import dev.mayo.ymcl.app.LauncherSettings
import dev.mayo.ymcl.app.MetaRepository
import dev.mayo.ymcl.app.UnreachableDownloadSource
import dev.mayo.ymcl.platform.FileMetaStore
import dev.mayo.ymcl.platform.HttpMetaFetcher
import dev.mayo.ymcl.platform.appDataDirectory

/** P3 step-1 self-check entry point. Not a game-file downloader. */
fun main() {
    val appRoot = appDataDirectory()
    val mode = System.getProperty("ymcl.meta.mode") ?: "online"
    val source = if (mode == "offline") UnreachableDownloadSource else LauncherSettings(appRoot).source()
    val repo = MetaRepository(appRoot, source, FileMetaStore(), HttpMetaFetcher(), System::currentTimeMillis)

    println("YMCL meta self-check | appRoot=$appRoot | source=${source.id} | mode=$mode")
    val manifest = repo.loadManifest()
    println("manifest: versions=${manifest.value.versions.size} offline=${manifest.offline} source=${manifest.sourceId}")
    if (manifest.offline) println("离线模式：使用本地缓存")

    val entry = manifest.value.versions.firstOrNull { it.id == "1.20.1" }
    if (entry != null) {
        val version = repo.loadVersionJson(entry)
        println("versionJson 1.20.1: sha1=${entry.sha1} cachedBytes=${version.value.length} offline=${version.offline}")
    } else {
        println("versionJson 1.20.1: NOT FOUND in manifest")
    }

    if (mode == "online") {
        val loaders = repo.loadFabricLoaderVersions("1.20.1")
        val selected = loaders.lastOrNull()
        println("fabricLoaders: count=${loaders.size} selected=$selected")
        if (selected != null) {
            val profile = repo.loadFabricProfile("1.20.1", selected)
            println("fabricProfile: mainClass=${profile.value.mainClass} libraries=${profile.value.libraries.size} offline=${profile.offline}")
            println("fabricProfile firstLibs=${profile.value.libraries.take(2).map { it.name }}")
        }
    }
    println("cacheRoot=$appRoot/data/meta")
}
