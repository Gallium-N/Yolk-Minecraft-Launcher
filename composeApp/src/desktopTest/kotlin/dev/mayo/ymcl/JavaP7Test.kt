package dev.mayo.ymcl

import dev.mayo.ymcl.app.BmclapiDownloadSource
import dev.mayo.ymcl.app.JavaMatrix
import dev.mayo.ymcl.app.JavaProvisioner
import dev.mayo.ymcl.app.JavaSelector
import dev.mayo.ymcl.app.OfficialDownloadSource
import dev.mayo.ymcl.domain.JavaRelease
import dev.mayo.ymcl.domain.JavaRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P7 unit tests: pure release parsing, matrix, selection, mirror分流, sha, unpack. */
class JavaP7Test {

    private fun rt(major: Int, version: String, path: String = "/j$major/bin/java") = JavaRuntime(
        id = "j$major", vendor = "Temurin", major = major, version = version,
        arch = "x64", os = "linux", path = path, source = "detected",
    )

    @Test
    fun `release parse handles 1_8 style and modern versions`() {
        assertEquals(8, JavaRelease.parse("JAVA_VERSION=\"1.8.0_412\"\nIMPLEMENTOR=\"Eclipse Adoptium\"")!!.major)
        assertEquals(8, JavaRelease.majorOf("1.8.0_412"))
        assertEquals(21, JavaRelease.majorOf("21.0.13"))
        val info = JavaRelease.parse("JAVA_VERSION=\"21.0.13\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\nOS_ARCH=\"x86_64\"")!!
        assertEquals("21.0.13", info.version)
        assertEquals("x86_64", info.osArch)
        assertNull(JavaRelease.parse("no java here"))
    }

    @Test
    fun `matrix five boundaries`() {
        assertEquals(8, JavaMatrix.requiredMajor("1.16.2"))
        assertEquals(17, JavaMatrix.requiredMajor("1.17"))
        assertEquals(17, JavaMatrix.requiredMajor("1.20.4"))
        assertEquals(21, JavaMatrix.requiredMajor("1.20.5"))
        assertEquals(21, JavaMatrix.requiredMajor("26.1"))
    }

    @Test
    fun `selection user override wins with reason`() {
        val runtimes = listOf(rt(8, "1.8.0_412"), rt(21, "21.0.13"))
        val c = JavaSelector.select("1.20.1", runtimes, manualPath = "/j8/bin/java")
        assertEquals("/j8/bin/java", c.java!!.path)
        assertEquals("user override", c.reason)
    }

    @Test
    fun `selection exact major picks highest patch with reason`() {
        val runtimes = listOf(rt(21, "21.0.5"), rt(21, "21.0.13"), rt(25, "25.0.1"))
        val c = JavaSelector.select("1.21.1", runtimes)
        assertEquals("21.0.13", c.java!!.version)
        assertEquals("exact match 21.0.13", c.reason)
    }

    @Test
    fun `selection nearest newer and nearest lower carry reason`() {
        // 1.20.1 needs 17; only 21 present -> nearest newer 21.
        val newer = JavaSelector.select("1.20.1", listOf(rt(21, "21.0.13")))
        assertEquals(21, newer.java!!.major)
        assertEquals("nearest newer 21 (requested 17)", newer.reason)
        // 1.12.2 needs strict 8; only 17 present -> nearest newer 17 with warning
        // (no exact 8, and there is no lower major than 8 to fall back to).
        val lower = JavaSelector.select("1.12.2", listOf(rt(17, "17.0.12")))
        assertEquals(17, lower.java!!.major)
        assertTrue(lower.reason.contains("nearest newer 17"))
        assertTrue(lower.reason.contains("warning"))
    }

    @Test
    fun `selection none returns null with reason`() {
        val c = JavaSelector.select("1.21.1", emptyList())
        assertNull(c.java)
        assertTrue(c.reason.contains("no Java"))
    }

    @Test
    fun `mirror first candidate splits by major`() {
        val for8 = BmclapiDownloadSource.javaInstallCandidates(8, "linux", "x64", "OpenJDK8U-jre.tar.gz", "http://github.com/x")
        assertEquals("huawei", JavaProvisioner.sourceLabel(for8[0]))
        val for21 = BmclapiDownloadSource.javaInstallCandidates(21, "linux", "x64", "OpenJDK21U-jre.tar.gz", "http://github.com/x")
        assertEquals("tuna", JavaProvisioner.sourceLabel(for21[0]))
        // Official source with mirror off: github first.
        val off = OfficialDownloadSource.javaInstallCandidates(21, "linux", "x64", "a.tar.gz", "http://github.com/x", preferMirror = false)
        assertEquals("github", JavaProvisioner.sourceLabel(off[0]))
    }

    @Test
    fun `sha256 mismatch triggers redownload decision`() {
        assertTrue(JavaProvisioner.sha256Matches("ABC123", "abc123"))
        assertFalse(JavaProvisioner.sha256Matches("dead", "abc123"))
        assertTrue(JavaProvisioner.sha256Matches("anything", "")) // no checksum = accept (logged)
    }

    @Test
    fun `single top dir stripped and zip-slip rejected`() {
        val entries = listOf("jdk-21/bin/java", "jdk-21/release", "jdk-21/lib/modules")
        assertEquals("jdk-21/", JavaProvisioner.singleTopDir(entries))
        assertNull(JavaProvisioner.singleTopDir(listOf("a/bin/java", "b/release")))
        assertFalse(JavaProvisioner.safeEntry("../evil"))
        assertFalse(JavaProvisioner.safeEntry("a/../../evil"))
        assertFalse(JavaProvisioner.safeEntry("/abs"))
        assertFalse(JavaProvisioner.safeEntry("a\\evil"))
        assertTrue(JavaProvisioner.safeEntry("jdk-21/bin/java"))
    }

    @Test
    fun `install id is relative and has no absolute path`() {
        val id = JavaProvisioner.installId("Eclipse Adoptium", 21, "linux", "x64")
        assertEquals("eclipse-adoptium-21-linux-x64", id)
        assertFalse(id.contains("/"))
        assertFalse(id.contains("\\"))
        assertFalse(id.startsWith("C:"))
    }

    @Test
    fun `api json parsing majors and asset`() {
        assertEquals(listOf(8, 17, 21), JavaProvisioner.parseAvailableMajors("{\"available_releases\":[21,8,17,21]}"))
        val asset = JavaProvisioner.parseAsset(
            """[{"binary":{"package":{"name":"OpenJDK21U-jre.tar.gz","checksum":"abc","link":"http://github.com/x"}}}]""",
        )!!
        assertEquals("OpenJDK21U-jre.tar.gz", asset.name)
        assertEquals("abc", asset.checksum)
        assertTrue(asset.link.contains("github"))
    }
}
