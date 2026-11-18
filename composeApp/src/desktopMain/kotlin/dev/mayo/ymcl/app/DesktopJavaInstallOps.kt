package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.JavaRelease
import dev.mayo.ymcl.domain.JavaRuntime
import dev.mayo.ymcl.platform.PathUtil
import dev.mayo.ymcl.platform.detectJavaRuntimes
import dev.mayo.ymcl.platform.writeTextUtf8Lf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * Real Java auto-install (P7 §3): Adoptium API + candidate mirrors, streaming
 * download with progress/cancel/Range, sha256 verify, zip/tar.gz extraction with
 * zip-slip protection and single-top-dir stripping. Runs on a background thread;
 * never blocks the click path.
 */
class DesktopJavaInstallOps(
    private val appRoot: String,
    private val source: DownloadSource,
    private val preferMirror: Boolean = true,
    private val log: (String) -> Unit = ::println,
) : JavaInstallOps {
    private val javaRoot: String get() = PathUtil.join(appRoot, "java")

    override fun availableMajors(): List<Int> = try {
        val body = getText(source.adoptiumAvailableReleases().first())
        JavaProvisioner.parseAvailableMajors(body)
    } catch (e: Exception) {
        log("java available_releases failed: ${e.message}")
        emptyList()
    }

    override fun install(
        major: Int,
        onProgress: (JavaInstallProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): JavaInstallResult {
        val os = if (System.getProperty("os.name").lowercase().contains("win")) "windows" else "linux"
        val arch = when (System.getProperty("os.arch").lowercase()) {
            "aarch64", "arm64" -> "aarch64"
            else -> "x64"
        }
        return try {
            val assetJson = getText(source.adoptiumAsset(major, os, arch).first())
            val asset = JavaProvisioner.parseAsset(assetJson)
                ?: return JavaInstallResult(null, "", 0L, "", "无法解析 Adoptium asset（major=$major）")
            val candidates = source.javaInstallCandidates(major, os, arch, asset.name, asset.link, preferMirror)
            var lastErr = ""
            var bytes = 0L
            var usedSource = ""
            var archive: File? = null

            val part = File(javaRoot, ".part-$major-${asset.name}")
            for (candidate in candidates) {
                if (isCancelled()) { part.delete(); return cancelled() }
                val label = JavaProvisioner.sourceLabel(candidate)
                log("java download: $label $candidate")
                try {
                    part.parentFile?.mkdirs()
                    bytes = downloadTo(candidate, part, isCancelled, onProgress)
                    if (isCancelled()) { part.delete(); return cancelled() }
                    val actualSha = sha256(part)
                    if (!JavaProvisioner.sha256Matches(actualSha, asset.checksum)) {
                        log("java sha256 mismatch via $label (got=$actualSha expected=${asset.checksum}); delete + redownload")
                        part.delete()
                        lastErr = "sha256 mismatch via $label"
                        continue // redownload once from the next/again candidate
                    }
                    archive = part
                    usedSource = label
                    break
                } catch (e: Exception) {
                    if (isCancelled()) { part.delete(); return cancelled() }
                    lastErr = "$label: ${e.message}"
                    log("java candidate failed $label: ${e.message}")
                }
            }
            if (archive == null) part.delete() // all candidates failed -> no .part leftovers

            val archiveFile = archive
                ?: return JavaInstallResult(null, usedSource, bytes, "", lastErr.ifBlank { "所有候选下载失败" })

            // Resolve the real version from the release file inside the archive.
            val id = JavaProvisioner.installId("temurin", major, os, arch)
            val dest = File(javaRoot, id)
            val tmp = File(javaRoot, ".unpack-$id")
            val archiveSha = sha256(archiveFile) // compute ONCE, before deleting .part
            if (tmp.exists()) tmp.deleteRecursively()
            tmp.mkdirs()
            try {
                extract(archiveFile, tmp)
                val home = findHome(tmp)
                val info = JavaRelease.parse(File(home, "release").readText(Charsets.UTF_8))
                    ?: return JavaInstallResult(null, usedSource, bytes, archiveSha, "解压后缺少 release")
                if (dest.exists()) dest.deleteRecursively()
                if (!home.renameTo(dest)) home.copyRecursively(dest, overwrite = true)
                tmp.deleteRecursively()
                // tar/zip entries lose the executable bit; restore it on bin executables.
                makeBinExecutable(dest)

                val relHome = PathUtil.join("java", id)
                writeTextUtf8Lf(
                    PathUtil.join(dest.absolutePath, "install.json"),
                    Json.encodeToString(
                        JavaInstallInfo.serializer(),
                        JavaInstallInfo(
                            vendor = "Temurin", major = major, fullVersion = info.version,
                            sha256 = archiveSha, installedAt = Instant.now().toString(),
                            path = relHome,
                        ),
                    ) + "\n",
                )
                archiveFile.delete() // .part consumed
                val runtime = JavaRuntime(
                    id = id, vendor = "Temurin", major = major, version = info.version,
                    arch = arch, os = os,
                    path = File(File(dest, "bin"), if (os == "windows") "java.exe" else "java").absolutePath,
                    source = "installed", installedAt = Instant.now().toString(),
                )
                JavaInstallResult(runtime, usedSource, bytes, archiveSha)
            } catch (e: Exception) {
                tmp.deleteRecursively()
                JavaInstallResult(null, usedSource, bytes, "", "解压失败: ${e.message}")
            }
        } catch (e: Exception) {
            JavaInstallResult(null, "", 0L, "", e.message ?: "install failed")
        }
    }

    override fun rescan(): List<JavaRuntime> = detectJavaRuntimes()

    private fun cancelled() = JavaInstallResult(null, "", 0L, "", "已取消")

    private fun getText(url: String): String {
        val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "YMCL/0.1 (+local)")
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** Streaming GET into a .part file with Range resume, progress and cancel. */
    private fun downloadTo(
        url: String,
        target: File,
        isCancelled: () -> Boolean,
        onProgress: (JavaInstallProgress) -> Unit,
    ): Long {
        val existing = if (target.exists()) target.length() else 0L
        val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "YMCL/0.1 (+local)")
        if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
        val total = conn.contentLengthLong.let { if (it > 0) existing + it else -1L }
        var written = existing
        BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
            val append = existing > 0 && conn.responseCode == 206
            java.io.FileOutputStream(target, append).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (isCancelled()) throw java.io.IOException("cancelled")
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    written += n
                    onProgress(JavaInstallProgress("downloading", written, total))
                }
            }
        }
        return written
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Extract archive into [dest], stripping a single top-level dir; zip-slip safe. */
    private fun extract(archive: File, dest: File) {
        if (archive.name.endsWith(".zip", true)) {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().toList().filter { !it.isDirectory }
                val strip = JavaProvisioner.singleTopDir(entries.map { it.name })
                entries.forEach { e ->
                    val name = if (strip != null && e.name.startsWith(strip)) e.name.removePrefix(strip) else e.name
                    if (!JavaProvisioner.safeEntry(name)) throw java.io.IOException("unsafe zip entry: ${e.name}")
                    val out = File(dest, name)
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
        } else {
            // tar.gz — minimal USTAR reader (no new dependency). Collect regular
            // files first so a single top-level directory can be stripped safely.
            val saved = mutableListOf<Pair<String, ByteArray>>()
            GZIPInputStream(BufferedInputStream(archive.inputStream())).use { gz ->
                val buf = ByteArray(512)
                while (true) {
                    var read = 0
                    while (read < 512) {
                        val n = gz.read(buf, read, 512 - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read < 512) break
                    val name = String(buf, 0, 100, Charsets.UTF_8).trim('\u0000')
                    if (name.isEmpty()) break
                    val sizeField = String(buf, 124, 12, Charsets.UTF_8).trim('\u0000', ' ').trim()
                    // USTAR size field is octal ASCII (e.g. "0000001750").
                    val size = sizeField.toLongOrNull(8) ?: sizeField.toLongOrNull() ?: 0L
                    val typeFlag = buf[156].toInt()
                    val isDir = typeFlag == '5'.code || name.endsWith("/")
                    val data = ByteArray(size.toInt())
                    var off = 0
                    while (off < data.size) {
                        val n = gz.read(data, off, data.size - off)
                        if (n < 0) break
                        off += n
                    }
                    // padding to 512-byte boundary
                    val pad = (512 - (size % 512)) % 512
                    if (pad > 0) gz.skip(pad)
                    if (!isDir && (typeFlag == '0'.code || typeFlag == 0)) {
                        saved += name to data
                    }
                }
            }
            val strip = JavaProvisioner.singleTopDir(saved.map { it.first })
            saved.forEach { (raw, bytes) ->
                val name = if (strip != null && raw.startsWith(strip)) raw.removePrefix(strip) else raw
                if (!JavaProvisioner.safeEntry(name)) throw java.io.IOException("unsafe tar entry: $raw")
                val out = File(dest, name)
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)
            }
        }
    }

    /** tar/zip entries drop the executable bit; restore it on bin executables (Linux). */
    private fun makeBinExecutable(home: File) {
        if (System.getProperty("os.name").lowercase().contains("win")) return
        File(home, "bin").listFiles()?.forEach { f ->
            if (f.isFile) f.setExecutable(true, false)
        }
    }

    /** Locate the extracted home (the dir containing bin/java and release). */
    private fun findHome(root: File): File {
        if (File(root, "release").isFile) return root
        root.listFiles()?.filter { it.isDirectory }?.forEach { child ->
            if (File(child, "release").isFile) return child
        }
        return root
    }
}
