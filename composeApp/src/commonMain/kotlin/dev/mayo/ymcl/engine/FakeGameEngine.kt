package dev.mayo.ymcl.engine

import dev.mayo.ymcl.domain.Instance
import dev.mayo.ymcl.domain.InstanceStatus
import dev.mayo.ymcl.domain.JavaRuntime
import dev.mayo.ymcl.domain.Loader
import dev.mayo.ymcl.domain.LoaderKind
import dev.mayo.ymcl.domain.ModEntry
import dev.mayo.ymcl.platform.nowMillis
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory demo data used by tests and previews. The desktop app injects the
 * real HMCL-backed engine instead.
 */
class FakeGameEngine : GameEngine {
    private val instances = mutableListOf<Instance>()
    private val running = mutableSetOf<String>()

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

    override fun listInstances(gameDir: String): List<Instance> = instances.toList()

    override fun detectJava(): List<JavaRuntime> = listOf(
        JavaRuntime(
            id = "fake-temurin-21",
            vendor = "Temurin",
            major = 21,
            version = "21.0.13+9",
            arch = "x64",
            os = "linux",
            path = "/usr/lib/jvm/java-21-temurin-jdk/bin/java",
            source = "detected",
        ),
    )

    override fun launch(request: LaunchRequest): LaunchResult {
        if (instances.none { it.id == request.instanceId }) {
            return LaunchResult.Failed("找不到实例：${request.instanceId}")
        }
        running += request.instanceId
        refresh()
        return LaunchResult.Started(
            LaunchHandle(
                pid = null,
                logFile = "",
                logs = flowOf("YMCL fake engine: ${request.instanceId}", "Setting user: Player"),
                exitCode = flowOf(0),
            ),
        )
    }

    override fun awaitExit(instanceId: String): Int = 0

    override fun stop(instanceId: String): Boolean {
        if (!running.remove(instanceId)) return false
        refresh()
        return true
    }

    private fun refresh() {
        for (index in instances.indices) {
            val current = instances[index]
            instances[index] = current.copy(
                status = if (current.id in running) InstanceStatus.RUNNING else InstanceStatus.READY,
            )
        }
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
