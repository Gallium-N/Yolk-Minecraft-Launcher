package dev.mayo.ymcl

import dev.mayo.ymcl.app.ConfigCodec
import dev.mayo.ymcl.app.UnsupportedSchemaException
import dev.mayo.ymcl.domain.LoaderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class ConfigCodecTest {
    private val jsonWithUnknownField = """
        {
          "schemaVersion": 1,
          "id": "skyblock",
          "displayName": "空岛生存",
          "mcVersion": "26.1",
          "loader": { "kind": "fabric", "version": "0.16.9" },
          "mods": [ { "fileName": "mod-001.jar", "name": "Mod 001", "version": "1.0.0" } ],
          "futureField": { "nested": [1, 2, 3] }
        }
    """.trimIndent()

    @Test
    fun `unknown fields are ignored`() {
        val instance = ConfigCodec.decodeInstance(jsonWithUnknownField)
        assertEquals("空岛生存", instance.displayName)
        assertEquals("26.1", instance.mcVersion)
        assertEquals(LoaderKind.FABRIC, instance.loader.kind)
        assertEquals("0.16.9", instance.loader.version)
        assertEquals(1, instance.modCount)
    }

    @Test
    fun `schema version above one is a hard error`() {
        val future = jsonWithUnknownField.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        val error = assertThrows(UnsupportedSchemaException::class.java) {
            ConfigCodec.decodeInstance(future)
        }
        assertTrue(error.message!!.contains("schemaVersion=2"))
    }

    @Test
    fun `encode and decode roundtrip`() {
        val original = ConfigCodec.decodeInstance(jsonWithUnknownField)
        val encoded = ConfigCodec.encodeInstance(original)
        assertTrue(encoded.contains("\"schemaVersion\": 1"))
        val decoded = ConfigCodec.decodeInstance(encoded)
        assertEquals(original, decoded)
    }
}
