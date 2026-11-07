package dev.mayo.ymcl

import dev.mayo.ymcl.app.BmclapiDownloadSource
import dev.mayo.ymcl.app.OfficialDownloadSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSourceTest {
    @Test
    fun `bmclapi candidates prefer mirror then official`() {
        assertEquals("bmclapi", BmclapiDownloadSource.id)
        val manifest = BmclapiDownloadSource.versionManifest()
        assertEquals(2, manifest.size)
        assertTrue(manifest[0].contains("bmclapi2.bangbang93.com/mc/game/"))
        assertTrue(manifest[1].contains("piston-meta.mojang.com"))
        assertTrue(
            BmclapiDownloadSource.library("org/ow2/asm/asm/9.3/asm-9.3.jar")[0].contains("/maven/"),
        )
        assertTrue(
            BmclapiDownloadSource.asset("b62ca8ec10d07e6bf5ac8dae0c8c1d2e6a1e3356")[0]
                .contains("/assets/b6/"),
        )
    }

    @Test
    fun `official candidates differ from mirror`() {
        assertEquals("official", OfficialDownloadSource.id)
        val manifest = OfficialDownloadSource.versionManifest()
        assertTrue(manifest[0].contains("piston-meta.mojang.com"))
        assertNotEquals(BmclapiDownloadSource.versionManifest()[0], manifest[0])
        assertTrue(
            OfficialDownloadSource.library("org/ow2/asm/asm/9.3/asm-9.3.jar")[0]
                .contains("libraries.minecraft.net"),
        )
        assertTrue(
            OfficialDownloadSource.asset("b62ca8ec10d07e6bf5ac8dae0c8c1d2e6a1e3356")[0]
                .contains("resources.download.minecraft.net"),
        )
    }

    @Test
    fun `fabric loader metadata prefers fast maven then slow meta`() {
        val candidates = BmclapiDownloadSource.fabricLoaderMetadata("1.20.1")
        assertEquals(2, candidates.size)
        assertTrue(candidates[0].contains("maven.fabricmc.net"))
        assertTrue(candidates[0].contains("maven-metadata.xml"))
        assertTrue(candidates[1].contains("meta.fabricmc.net"))
    }
}
