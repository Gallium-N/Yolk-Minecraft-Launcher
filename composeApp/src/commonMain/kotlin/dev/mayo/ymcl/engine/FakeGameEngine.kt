package dev.mayo.ymcl.engine

import dev.mayo.ymcl.domain.Instance
import dev.mayo.ymcl.domain.InstanceStatus
import dev.mayo.ymcl.domain.Loader
import dev.mayo.ymcl.domain.LoaderKind
import dev.mayo.ymcl.domain.ModEntry
import dev.mayo.ymcl.platform.nowMillis

/**
 * In-memory demo data (phase 1). Nothing is written to disk and no game is started.
 * The five names match the design mockups.
 */
class FakeGameEngine : GameEngine {
    private val instances = mutableListOf<Instance>()

    init {
        val now = nowMillis()
        instances += Instance(
            id = "underground-bar",
            displayName = "地下酒吧",
            mcVersion = "1.20.1",
            loader = Loader(LoaderKind.FORGE, "47.4.16"),
            mods = fakeMods(12),
            totalPlayTimeMs = hours(42),
            lastLaunchedAt = now - hours(2),
            status = InstanceStatus.RUNNING,
            updateAvailable = true,
        )
        instances += Instance(
            id = "skyblock",
            displayName = "空岛生存",
            mcVersion = "26.1",
            loader = Loader(LoaderKind.FABRIC, "0.16.9"),
            mods = fakeMods(48),
            totalPlayTimeMs = hours(126),
            lastLaunchedAt = now - hours(26),
            status = InstanceStatus.READY,
        )
        instances += Instance(
            id = "redstone-lab",
            displayName = "红石测试场",
            mcVersion = "1.21.4",
            loader = Loader(LoaderKind.FABRIC, "0.16.9"),
            mods = fakeMods(6),
            totalPlayTimeMs = hours(3),
            lastLaunchedAt = now - hours(36),
            status = InstanceStatus.READY,
        )
        instances += Instance(
            id = "create-steampunk",
            displayName = "Create: 机械时代",
            mcVersion = "1.20.1",
            loader = Loader(LoaderKind.FORGE, "47.4.16"),
            mods = fakeMods(214),
            totalPlayTimeMs = hours(88),
            lastLaunchedAt = now - hours(72),
            status = InstanceStatus.UPDATE_AVAILABLE,
            updateAvailable = true,
        )
        instances += Instance(
            id = "vanilla-snapshot",
            displayName = "原版 26.4 快照",
            mcVersion = "26.4",
            loader = Loader(LoaderKind.VANILLA),
            mods = emptyList(),
            totalPlayTimeMs = 0L,
            lastLaunchedAt = null,
            status = InstanceStatus.READY,
        )
    }

    override fun listInstances(): List<Instance> = instances.toList()

    override fun start(instanceId: String): LaunchResult {
        val index = instances.indexOfFirst { it.id == instanceId }
        if (index < 0) return LaunchResult.Failed("找不到实例：$instanceId")
        val current = instances[index]
        instances[index] = current.copy(
            status = InstanceStatus.RUNNING,
            lastLaunchedAt = nowMillis(),
        )
        return LaunchResult.Started(pid = null)
    }

    override fun stop(instanceId: String): Boolean {
        val index = instances.indexOfFirst { it.id == instanceId }
        if (index < 0) return false
        val current = instances[index]
        if (current.status != InstanceStatus.RUNNING) return false
        instances[index] = current.copy(status = InstanceStatus.READY)
        return true
    }

    private fun fakeMods(count: Int): List<ModEntry> = List(count) { index ->
        val number = (index + 1).toString().padStart(3, '0')
        ModEntry(
            fileName = "mod-$number.jar",
            name = "Mod $number",
            version = "1.0.$index",
        )
    }

    private fun hours(value: Long): Long = value * 60L * 60L * 1000L
}
