package dev.mayo.ymcl.platform

import dev.mayo.ymcl.app.DownloadFileSystem
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** All paths are relative to the isolated game directory. */
class NativeDownloadFileSystem(private val gameDir: String) : DownloadFileSystem {
    private fun file(rel: String): File = File(PathUtil.toNative(PathUtil.join(gameDir, rel)))

    override fun exists(relativePath: String): Boolean = file(relativePath).isFile

    override fun size(relativePath: String): Long = file(relativePath).length()

    override fun sha1Of(relativePath: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file(relativePath).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }

    override fun readAll(relativePath: String): ByteArray = file(relativePath).readBytes()

    override fun writeNew(relativePath: String, data: ByteArray) {
        val f = file(relativePath)
        f.parentFile?.mkdirs()
        f.writeBytes(data)
    }

    override fun append(relativePath: String, data: ByteArray) {
        val f = file(relativePath)
        f.parentFile?.mkdirs()
        FileOutputStream(f, true).use { it.write(data) }
    }

    override fun delete(relativePath: String) {
        file(relativePath).delete()
    }

    override fun move(from: String, to: String) {
        val target = file(to)
        target.parentFile?.mkdirs()
        Files.move(file(from).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Hard-links a shared file into an instance-owned path (zero extra bytes on
     * the same device). On cross-device (EXDEV) or unsupported filesystems we
     * fall back to a plain copy. Existing destinations are left untouched.
     */
    override fun linkOrCopy(from: String, to: String) {
        val src = file(from).toPath()
        val dst = file(to).toPath()
        if (Files.exists(dst)) return
        dst.parent?.let { Files.createDirectories(it) }
        try {
            Files.createLink(dst, src)
        } catch (e: Exception) {
            Files.deleteIfExists(dst)
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun unpackNatives(jarBytes: ByteArray, destDir: String) {
        val root = file(destDir)
        root.mkdirs()
        val rootPath = root.canonicalPath
        ZipInputStream(ByteArrayInputStream(jarBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory && !entry.name.startsWith("META-INF/")) {
                    val out = File(root, entry.name)
                    if (out.canonicalPath.startsWith(rootPath + File.separator)) {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { sink -> zis.copyTo(sink) }
                    }
                }
                zis.closeEntry()
            }
        }
    }
}
