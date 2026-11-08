package dev.mayo.ymcl

import dev.mayo.ymcl.platform.FileInstanceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Offline tests for the real instance data layer (no network, no processes). */
class InstanceRepositoryTest {
    private fun tempRoot(): File = Files.createTempDirectory("ymcl-p4-repo").toFile()

    private fun File.writeRel(rel: String, text: String) {
        val f = File(this, rel)
        f.parentFile.mkdirs()
        f.writeText(text)
    }

    private val vanillaJson =
        """{"id":"%s","mainClass":"net.minecraft.client.main.Main","libraries":[]}"""

    private fun minimal(id: String): String = vanillaJson.format(id)

    // 1. vanilla + fabric + forge -> three summaries with correct loader inference.
    @Test
    fun `three loader kinds are inferred from the version json`() {
        val root = tempRoot()
        root.writeRel("versions/alpha/instance.json", """{"mcVersion":"1.20.1"}""")
        root.writeRel("versions/alpha/alpha.json", minimal("alpha"))
        root.writeRel("versions/beta/instance.json", """{"mcVersion":"1.20.1"}""")
        root.writeRel(
            "versions/beta/beta.json",
            """{"id":"beta","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",""" +
                """"libraries":[{"name":"net.fabricmc:fabric-loader:0.20.1"},""" +
                """{"name":"net.fabricmc:intermediary:1.20.1"}]}""",
        )
        root.writeRel("versions/gamma/instance.json", """{"mcVersion":"1.20.1"}""")
        root.writeRel(
            "versions/gamma/gamma.json",
            """{"id":"gamma","mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher",""" +
                """"libraries":[{"name":"net.minecraftforge:forge:1.20.1-47.4.23"}]}""",
        )

        val byId = FileInstanceRepository(root.absolutePath).list().associateBy { it.id }
        assertEquals(3, byId.size)
        assertEquals("vanilla", byId["alpha"]!!.loaderKind)
        assertEquals("原版", byId["alpha"]!!.loaderLine)
        assertEquals("fabric", byId["beta"]!!.loaderKind)
        assertEquals("Fabric 0.20.1", byId["beta"]!!.loaderLine)
        assertEquals("1.20.1", byId["beta"]!!.mcVersion)
        assertEquals("forge", byId["gamma"]!!.loaderKind)
        assertEquals("Forge 47.4.23", byId["gamma"]!!.loaderLine)
        assertEquals("1.20.1", byId["gamma"]!!.mcVersion)
    }

    // 2. fabric-loader-<loader>-<mc> directory name resolves loader + mc.
    @Test
    fun `fabric loader directory name is parsed`() {
        val root = tempRoot()
        val id = "fabric-loader-0.20.1-1.20.1"
        root.writeRel("versions/$id/$id.json", minimal(id))

        val s = FileInstanceRepository(root.absolutePath).list().single()
        assertEquals("fabric", s.loaderKind)
        assertEquals("0.20.1", s.loaderVersion)
        assertEquals("1.20.1", s.mcVersion)
        assertEquals("Fabric 0.20.1", s.loaderLine)
    }

    // 3. instance.json displayName wins; missing file falls back to dir name.
    @Test
    fun `instance json displayName takes priority over directory name`() {
        val root = tempRoot()
        root.writeRel("versions/one/instance.json", """{"displayName":"我的世界","mcVersion":"1.20.1"}""")
        root.writeRel("versions/one/one.json", minimal("one"))
        root.writeRel("versions/two/two.json", minimal("two"))

        val byId = FileInstanceRepository(root.absolutePath).list().associateBy { it.id }
        assertEquals("我的世界", byId["one"]!!.displayName)
        assertEquals("two", byId["two"]!!.displayName)
    }

    // 4. non-empty root mods/ forces shared-root (isolated=false).
    @Test
    fun `non-empty root mods forces non-isolated instances`() {
        val root = tempRoot()
        root.writeRel("versions/one/one.json", minimal("one"))
        root.writeRel("mods/x.jar", "x")
        assertFalse(FileInstanceRepository(root.absolutePath).list().single().isolated)

        val emptyRoot = tempRoot()
        emptyRoot.writeRel("versions/one/one.json", minimal("one"))
        assertTrue(FileInstanceRepository(emptyRoot.absolutePath).list().single().isolated)
    }

    // 5. a bad version json skips just that instance; the rest survive.
    @Test
    fun `bad version json skips one entry without failing the list`() {
        val root = tempRoot()
        root.writeRel("versions/good/good.json", minimal("good"))
        root.writeRel("versions/bad/bad.json", "{ this is not json")
        root.writeRel("versions/also-good/also-good.json", minimal("also-good"))

        val repo = FileInstanceRepository(root.absolutePath)
        val scan = repo.scan()
        assertEquals(2, scan.instances.size)
        assertEquals(1, scan.failed)
        assertTrue(scan.errors.any { it.startsWith("bad:") })
        assertEquals(2, repo.list().size)
        assertTrue(scan.instances.none { it.id == "bad" })
    }

    // 6. isolatedBySetting=false and the path field.
    @Test
    fun `setting override and instance path`() {
        val root = tempRoot()
        root.writeRel("versions/one/one.json", minimal("one"))
        val s = FileInstanceRepository(root.absolutePath, isolatedBySetting = false).list().single()
        assertFalse(s.isolated)
        assertTrue(s.path.endsWith("versions/one"))
    }
}
