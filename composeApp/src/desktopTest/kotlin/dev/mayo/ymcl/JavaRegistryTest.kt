package dev.mayo.ymcl

import dev.mayo.ymcl.domain.JavaRuntime
import dev.mayo.ymcl.platform.JavaRegistry
import dev.mayo.ymcl.platform.probeJava
import dev.mayo.ymcl.platform.readTextAuto
import dev.mayo.ymcl.platform.writeTextUtf8Lf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JavaRegistryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val sample = JavaRuntime(
        id = "temurin-17.0.12-x64-linux-12345678",
        vendor = "Temurin",
        major = 17,
        version = "17.0.12",
        arch = "x64",
        os = "linux",
        path = "/opt/java-17/bin/java",
        source = "detected",
    )

    @Test
    fun `registry roundtrips runtimes and writes schema version 1`() {
        val root = temp.newFolder("app").absolutePath
        val registry = JavaRegistry(root)
        val loaded = registry.loadOrDetect(listOf(sample))

        assertEquals(1, loaded.size)
        assertEquals(sample, loaded.single())
        val text = readTextAuto(registry.file)
        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertTrue(text.contains("\"path\": \"/opt/java-17/bin/java\""))
    }

    @Test
    fun `unknown fields are tolerated`() {
        val root = temp.newFolder("unknown").absolutePath
        val registry = JavaRegistry(root)
        writeTextUtf8Lf(
            registry.file,
            """
            {
              "schemaVersion": 1,
              "futureTopLevel": true,
              "runtimes": [
                {
                  "id": "x",
                  "vendor": "Temurin",
                  "major": 21,
                  "version": "21.0.1",
                  "arch": "x64",
                  "os": "linux",
                  "path": "/j/bin/java",
                  "source": "detected",
                  "installedAt": null,
                  "futureRuntimeField": { "a": 1 }
                }
              ]
            }
            """.trimIndent(),
        )

        val loaded = registry.load()
        assertEquals(1, loaded.size)
        assertEquals("21.0.1", loaded.single().version)
    }

    @Test
    fun `schema version above one is a hard error`() {
        val root = temp.newFolder("future").absolutePath
        val registry = JavaRegistry(root)
        writeTextUtf8Lf(
            registry.file,
            """{"schemaVersion":2,"runtimes":[]}""",
        )

        val error = assertThrows(IllegalStateException::class.java) { registry.load() }
        assertTrue(error.message!!.contains("schemaVersion 2"))
    }

    @Test
    fun `fake java script is parsed into a runtime`() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val root = temp.newFolder("jdk")
        val bin = File(root, "bin").apply { mkdirs() }
        val java = File(bin, "java")
        java.writeText(
            """
            #!/bin/sh
            cat >&2 <<'EOF'
            Property settings:
                java.version = 17.0.12
                java.vendor = Eclipse Adoptium
                os.arch = amd64
            EOF
            exit 0
            """.trimIndent(),
        )
        java.setExecutable(true)

        val runtime = probeJava(java)
        assertNotNull(runtime)
        assertEquals("Temurin", runtime!!.vendor)
        assertEquals(17, runtime.major)
        assertEquals("x64", runtime.arch)
        assertEquals("detected", runtime.source)
    }
}
