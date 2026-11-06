package dev.mayo.ymcl

import dev.mayo.ymcl.platform.decodeLogBytes
import dev.mayo.ymcl.platform.readTextAuto
import dev.mayo.ymcl.platform.writeTextUtf8Lf
import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TextFilesTest {
    private fun tempFile(name: String): java.nio.file.Path =
        Files.createTempDirectory("ymcl-text").resolve(name)

    @Test
    fun `utf8 file is read normally`() {
        val file = tempFile("utf8.txt")
        Files.write(file, "地下酒吧 · 空岛生存".toByteArray(Charsets.UTF_8))
        assertEquals("地下酒吧 · 空岛生存", readTextAuto(file.toString()))
    }

    @Test
    fun `gbk file falls back and keeps chinese`() {
        val file = tempFile("gbk.log")
        Files.write(file, "地下酒吧 崩溃日志".toByteArray(Charset.forName("GBK")))
        assertEquals("地下酒吧 崩溃日志", readTextAuto(file.toString()))
    }

    @Test
    fun `utf8 with bom is stripped`() {
        val file = tempFile("bom.txt")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        Files.write(file, bom + "模组加载器".toByteArray(Charsets.UTF_8))
        assertEquals("模组加载器", readTextAuto(file.toString()))
    }

    @Test
    fun `written text is utf8 without bom and uses lf`() {
        val file = tempFile("out.json")
        writeTextUtf8Lf(file.toString(), "{\r\n  \"name\": \"空岛生存\"\r\n}\r\n")
        val bytes = Files.readAllBytes(file)
        assertArrayEquals(
            "{\n  \"name\": \"空岛生存\"\n}\n".toByteArray(Charsets.UTF_8),
            bytes,
        )
        assertEquals(
            false,
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte(),
        )
    }

    @Test
    fun `gbk log bytes fall back and keep chinese`() {
        val bytes = "地下酒吧 进程日志".toByteArray(Charset.forName("GBK"))
        assertEquals("地下酒吧 进程日志", decodeLogBytes(bytes))
    }

    @Test
    fun `gbk byte stream decodes to chinese`() {
        val stream = java.io.ByteArrayInputStream("地下酒吧 崩溃日志".toByteArray(Charset.forName("GBK")))
        assertEquals("地下酒吧 崩溃日志", decodeLogBytes(stream.readBytes()))
    }

    @Test
    fun `utf8 log bytes are not mis-decoded as gbk`() {
        val text = "主菜单已加载 · LWJGL Version 3.3.1"
        assertEquals(text, decodeLogBytes(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `utf8 bom in log bytes is stripped`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals("日志首行", decodeLogBytes(bom + "日志首行".toByteArray(Charsets.UTF_8)))
    }
}
