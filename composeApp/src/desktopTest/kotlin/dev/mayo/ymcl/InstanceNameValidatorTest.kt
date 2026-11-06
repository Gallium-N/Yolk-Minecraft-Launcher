package dev.mayo.ymcl

import dev.mayo.ymcl.domain.InstanceNameValidator
import dev.mayo.ymcl.domain.NameValidation
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceNameValidatorTest {
    private val root = System.getProperty("java.io.tmpdir").trimEnd('/') + "/ymcl/instances"

    private fun invalidMessage(name: String): String {
        val result = InstanceNameValidator.validate(name, root)
        assertTrue("expected '$name' to be rejected, got $result", result is NameValidation.Invalid)
        return (result as NameValidation.Invalid).message
    }

    @Test
    fun `illegal characters are rejected`() {
        val message = invalidMessage("我的/世界")
        assertTrue(message.contains("不能包含"))
        invalidMessage("a:b")
        invalidMessage("a<b")
        invalidMessage("a|b")
    }

    @Test
    fun `windows reserved device names are rejected case insensitively`() {
        listOf("CON", "con", "Con", "Con.txt", "PRN", "aux", "NUL", "COM1", "lpt9").forEach { name ->
            val message = invalidMessage(name)
            assertTrue("$name should mention reserved device", message.contains("保留"))
        }
        assertTrue(InstanceNameValidator.validate("CONSOLE", root) is NameValidation.Valid)
        assertTrue(InstanceNameValidator.validate("COM10", root) is NameValidation.Valid)
    }

    @Test
    fun `legal chinese instance name is accepted`() {
        assertTrue(InstanceNameValidator.validate("空岛生存", root) is NameValidation.Valid)
        assertTrue(InstanceNameValidator.validate("Create: 机械时代", root) is NameValidation.Invalid)
        assertTrue(InstanceNameValidator.validate("Create 机械时代", root) is NameValidation.Valid)
    }

    @Test
    fun `windows path joining uses backslashes only at the native boundary`() {
        val joined = dev.mayo.ymcl.platform.PathUtil.join("instances", "空岛生存", "instance.json")
        assertTrue("internal representation must stay slash based", joined.contains('/'))
        assertTrue("internal representation must not contain backslashes", !joined.contains('\\'))

        val native = dev.mayo.ymcl.platform.PathUtil.toNative(
            joined,
            dev.mayo.ymcl.platform.OsFamily.WINDOWS,
        )
        assertTrue(native.contains('\\'))
        assertTrue(!native.contains('/'))
        assertTrue(native.endsWith("instance.json"))
    }

    @Test
    fun `overlong path gives a readable error`() {
        val deepRoot = "/" + "very-long-segment/".repeat(20)
        val result = InstanceNameValidator.validate("空岛生存", deepRoot)
        assertTrue(result is NameValidation.Invalid)
        val message = (result as NameValidation.Invalid).message
        assertTrue(message.contains("路径过长"))
        assertTrue(message.contains("240"))
    }
}
