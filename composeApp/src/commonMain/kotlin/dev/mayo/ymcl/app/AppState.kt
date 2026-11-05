package dev.mayo.ymcl.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mayo.ymcl.domain.Instance
import dev.mayo.ymcl.domain.InstanceStatus
import dev.mayo.ymcl.engine.FakeGameEngine
import dev.mayo.ymcl.engine.GameEngine
import dev.mayo.ymcl.engine.LaunchResult
import dev.mayo.ymcl.platform.instancesDirectory
import dev.mayo.ymcl.platform.themeOverride

enum class AppPage(val title: String) {
    HOME("启动台"),
    INSTANCES("实例"),
    DIAGNOSTICS("诊断"),
    SETTINGS("设置"),
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    companion object {
        fun fromOverride(value: String?): ThemeMode = when (value?.trim()?.lowercase()) {
            "dark" -> DARK
            "light" -> LIGHT
            else -> SYSTEM
        }
    }
}

enum class Density(val label: String) {
    COMFORTABLE("舒适"),
    COMPACT("紧凑"),
}

enum class SettingsGroup(val label: String) {
    APPEARANCE("外观"),
    GAME("游戏"),
    LAUNCH("启动"),
    DOWNLOAD("下载"),
    ABOUT("关于"),
}

enum class InstanceSort(val label: String) {
    RECENT("最近启动"),
    NAME("名称"),
    PLAY_TIME("游玩时长"),
}

/**
 * ViewModel-ish state holder. No DI framework: the engine is a constructor
 * parameter with a fake default, so phase 2 can inject the HMCL adapter.
 */
class AppState(
    private val engine: GameEngine = FakeGameEngine(),
) {
    var page by mutableStateOf(AppPage.HOME)
        private set

    var instances by mutableStateOf(engine.listInstances())
        private set

    var selectedInstanceId by mutableStateOf(instances.firstOrNull()?.id)
        private set

    var searchQuery by mutableStateOf("")

    var sort by mutableStateOf(InstanceSort.RECENT)

    var themeMode by mutableStateOf(ThemeMode.fromOverride(themeOverride()))

    var dynamicColor by mutableStateOf(true)

    var reduceMotion by mutableStateOf(false)

    var density by mutableStateOf(Density.COMFORTABLE)

    var settingsGroup by mutableStateOf(SettingsGroup.APPEARANCE)

    val instancesRoot: String = instancesDirectory()

    val mainInstance: Instance?
        get() = instances.firstOrNull { it.id == selectedInstanceId } ?: instances.firstOrNull()

    val updateCount: Int get() = instances.count { it.updateAvailable }

    val recentInstances: List<Instance>
        get() = instances
            .filter { it.id != mainInstance?.id }
            .sortedByDescending { it.lastLaunchedAt ?: Long.MIN_VALUE }
            .take(2)

    fun navigate(target: AppPage) {
        page = target
    }

    fun selectInstance(id: String) {
        selectedInstanceId = id
    }

    fun launch(id: String? = null) {
        val target = id ?: selectedInstanceId ?: return
        when (engine.start(target)) {
            is LaunchResult.Started -> refresh()
            is LaunchResult.Failed -> Unit // Phase 1 fake never fails.
        }
    }

    fun stop(id: String) {
        if (engine.stop(id)) refresh()
    }

    fun filteredInstances(): List<Instance> {
        val query = searchQuery.trim()
        val matched = if (query.isEmpty()) {
            instances
        } else {
            instances.filter { instance ->
                instance.displayName.contains(query, ignoreCase = true) ||
                    instance.mcVersion.contains(query, ignoreCase = true) ||
                    instance.loader.display.contains(query, ignoreCase = true) ||
                    instance.mods.any { it.fileName.contains(query, ignoreCase = true) }
            }
        }
        return when (sort) {
            InstanceSort.RECENT -> matched.sortedByDescending { it.lastLaunchedAt ?: Long.MIN_VALUE }
            InstanceSort.NAME -> matched.sortedBy { it.displayName }
            InstanceSort.PLAY_TIME -> matched.sortedByDescending { it.totalPlayTimeMs }
        }
    }

    fun runningCount(): Int = instances.count { it.status == InstanceStatus.RUNNING }

    private fun refresh() {
        instances = engine.listInstances()
        if (instances.none { it.id == selectedInstanceId }) {
            selectedInstanceId = instances.firstOrNull()?.id
        }
    }
}

fun formatPlayTime(millis: Long): String {
    if (millis <= 0L) return "未启动"
    val hours = millis / 3_600_000L
    if (hours == 0L) {
        val minutes = (millis / 60_000L).coerceAtLeast(1L)
        return "$minutes 分钟"
    }
    return "$hours 小时"
}
