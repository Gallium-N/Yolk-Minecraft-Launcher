package dev.mayo.ymcl

import dev.mayo.ymcl.platform.OsFamily
import dev.mayo.ymcl.platform.PathUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathUtilTest {
    @Test
    fun `join keeps the internal slash form`() {
        assertEquals(
            "instances/空岛生存/instance.json",
            PathUtil.join("instances", "空岛生存", "instance.json"),
        )
        assertEquals("instances/空岛生存", PathUtil.join("instances/", "/空岛生存/"))
    }

    @Test
    fun `toNative converts only at the boundary`() {
        val internal = "instances/空岛生存/instance.json"
        assertEquals("instances\\空岛生存\\instance.json", PathUtil.toNative(internal, OsFamily.WINDOWS))
        assertEquals(internal, PathUtil.toNative(internal, OsFamily.UNIX))
        assertTrue(internal.contains('/'))
    }

    @Test
    fun `normalize resolves dots and duplicate separators`() {
        assertEquals("instances/skyblock/mods", PathUtil.normalize("instances//skyblock/./mods/"))
        assertEquals("instances/skyblock", PathUtil.normalize("instances/skyblock/../skyblock"))
        assertEquals("/a/c", PathUtil.normalize("/a/b/../c"))
        assertEquals("", PathUtil.normalize(""))
    }
}
