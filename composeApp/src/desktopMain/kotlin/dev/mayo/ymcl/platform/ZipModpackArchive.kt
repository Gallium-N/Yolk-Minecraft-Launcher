package dev.mayo.ymcl.platform

import dev.mayo.ymcl.app.ModpackArchive
import dev.mayo.ymcl.app.PackEntry
import java.io.File
import java.util.Collections
import java.util.zip.ZipFile

/**
 * Host-side [ModpackArchive] backed by a real zip. Entry NAMES are only read;
 * the pure parser decides which ones are safe (zip-slip) before any bytes are
 * written. The archive is always closed after parsing.
 */
class ZipModpackArchive(private val path: String) : ModpackArchive {
    private val zip = ZipFile(File(PathUtil.toNative(path)))

    override fun entries(): List<PackEntry> =
        Collections.list(zip.entries()).map { PackEntry(it.name, it.isDirectory) }

    override fun read(name: String): ByteArray {
        val entry = zip.getEntry(name) ?: error("missing zip entry: $name")
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    override fun close() {
        zip.close()
    }
}
