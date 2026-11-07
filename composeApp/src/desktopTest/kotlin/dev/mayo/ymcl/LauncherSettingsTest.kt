package dev.mayo.ymcl

import dev.mayo.ymcl.app.LauncherSettings
import dev.mayo.ymcl.app.LauncherSettingsFile
import dev.mayo.ymcl.app.OfficialDownloadSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LauncherSettingsTest {
    private fun tempRoot(): String = Files.createTempDirectory("ymcl-settings").toString()

    @Test
    fun `missing file defaults to preferBmclapi true`() {
        val settings = LauncherSettings(tempRoot()) { }
        assertTrue(settings.load().preferBmclapi)
        assertEquals(1, settings.load().schemaVersion)
    }

    @Test
    fun `disabled switch resolves to OfficialDownloadSource`() {
        val settings = LauncherSettings(tempRoot()) { }
        settings.save(LauncherSettingsFile(preferBmclapi = false))
        assertFalse(settings.load().preferBmclapi)
        assertTrue(settings.source() === OfficialDownloadSource)
    }

    @Test
    fun `malformed json falls back to default without rewriting file`() {
        val root = tempRoot()
        val settings = LauncherSettings(root) { }
        val original = "{ this is not json"
        java.io.File(settings.file).apply { parentFile.mkdirs(); writeText(original) }
        assertTrue(settings.load().preferBmclapi)
        assertEquals(original, java.io.File(settings.file).readText())
    }

    @Test
    fun `unknown schemaVersion falls back to default`() {
        val settings = LauncherSettings(tempRoot()) { }
        java.io.File(settings.file).apply {
            parentFile.mkdirs()
            writeText("{\"schemaVersion\":2,\"preferBmclapi\":false}")
        }
        assertTrue(settings.load().preferBmclapi)
        assertEquals(1, settings.load().schemaVersion)
    }

    @Test
    fun `saved file is utf8 without bom and declares schemaVersion`() {
        val settings = LauncherSettings(tempRoot()) { }
        settings.save(LauncherSettingsFile(preferBmclapi = false))
        val bytes = java.io.File(settings.file).readBytes()
        assertFalse(bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
        assertTrue(String(bytes, Charsets.UTF_8).contains("schemaVersion"))
    }
}
