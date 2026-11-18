package dev.mayo.ymcl.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mayo.ymcl.domain.InstanceStatus
import dev.mayo.ymcl.domain.JavaRuntime
import dev.mayo.ymcl.engine.FakeGameEngine
import dev.mayo.ymcl.engine.GameEngine
import dev.mayo.ymcl.engine.LaunchRequest
import dev.mayo.ymcl.engine.LaunchResult
import dev.mayo.ymcl.platform.defaultGameDirectory
import dev.mayo.ymcl.platform.instancesDirectory
import dev.mayo.ymcl.platform.nowMillis
import dev.mayo.ymcl.platform.themeOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class AppPage(val title: String) {
    HOME("启动台"),
    INSTANCES("实例"),
    GET_CONTENT("获取内容"),
    IMPORT("导入整合包"),
    NEW_INSTANCE("新建实例"),
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
 * How an instance's process came to an end. Set at the moment the user asks for
 * a stop, so exit codes never have to be guessed (143 = SIGTERM could also be
 * an external kill, but a crash must still raise the red banner).
 */
private enum class LaunchOrigin { NORMAL, USER_STOP_REQUESTED }

/**
 * ViewModel-ish state holder. The desktop entry point injects the HMCL-backed
 * engine, an [InstanceRepository] wired to the real game root, and the persisted
 * isolation setting. UI/common code never imports HMCL types or touches the FS.
 */
class AppState(
    private val engine: GameEngine = FakeGameEngine(),
    private val repositoryFactory: (String) -> InstanceRepository = { EmptyInstanceRepository() },
    gameRoot: String = defaultGameDirectory(),
    private val isolationEnabled: Boolean = true,
    private val loaderProbeTimeoutMs: Long = 30_000L,
    modrinth: ModrinthService? = null,
    modpack: ModpackBackend? = null,
    newInstance: NewInstanceBackend? = null,
    private val javaInstaller: JavaInstallOps? = null,
    initialCurseForgeApiKey: String = "",
    initialAllowCurseToolsProxy: Boolean = true,
    private val persistModpackSettings: (String, Boolean) -> Unit = { _, _ -> },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var repository: InstanceRepository = repositoryFactory(gameRoot)
    private val lastLaunchTimes = mutableMapOf<String, Long>()

    /** 获取内容页状态；null service keeps previews/tests offline. */
    val modrinthUi = ModrinthUiState(
        service = modrinth,
        instances = { instances },
        isRunning = { id -> activeInstanceId == id },
        log = ::println,
    )

    /** 导入整合包向导状态；null backend keeps previews/tests offline. */
    val modpackUi = ModpackUiState(
        backend = modpack,
        onFinished = { id ->
            navigate(AppPage.INSTANCES)
            selectInstance(id)
            refresh()
        },
        log = ::println,
    )

    /** New-instance wizard state (section G). */
    val newInstanceUi = NewInstanceUiState(
        backend = newInstance,
        onFinished = { id ->
            navigate(AppPage.INSTANCES)
            selectInstance(id)
            refresh()
        },
        onInstancesChanged = { refresh() },
        log = ::println,
        loaderProbeTimeoutMs = loaderProbeTimeoutMs,
    )

    // instanceId -> origin of the running process. Guarded: stop() runs on the
    // UI thread while launch()/awaitExit() run on Dispatchers.IO.
    private val launchLock = Any()
    private val launched = mutableMapOf<String, LaunchOrigin>()

    private fun markOrigin(id: String, origin: LaunchOrigin?) {
        synchronized(launchLock) {
            if (origin == null) launched.remove(id) else launched[id] = origin
        }
    }

    private fun originOf(id: String): LaunchOrigin? = synchronized(launchLock) { launched[id] }

    var page by mutableStateOf(AppPage.HOME)
        private set

    var gameDirectory by mutableStateOf(gameRoot)
        private set

    var instances by mutableStateOf<List<InstanceSummary>>(emptyList())
        private set

    /** Version dirs that failed to parse in the last refresh (shown as 读取失败). */
    var instanceReadFailures by mutableStateOf(0)
        private set

    var javaRuntimes by mutableStateOf(
        runCatching { engine.detectJava() }
            .onFailure { println("YMCL Java detection failed: ${it.message}") }
            .getOrElse { emptyList() },
    )
        private set

    var selectedJavaPath by mutableStateOf(
        javaRuntimes.firstOrNull { it.major == 21 }?.path ?: javaRuntimes.firstOrNull()?.path,
    )
        private set

    /** True once the user explicitly picks a Java; the matrix must not override it. */
    var javaManuallySelected by mutableStateOf(false)
        private set

    /** When the launcher should auto-install a missing Java without asking. */
    var autoManageJava by mutableStateOf(false)

    /** Missing-Java dialog request: what instance/major we are installing for. */
    data class PendingJavaInstall(
        val instanceId: String,
        val displayName: String,
        val major: Int,
    )

    var pendingJavaInstall by mutableStateOf<PendingJavaInstall?>(null)
        private set

    /** Java install step ladder state (P7 reuses the P0.2 0/3/10/30s protocol). */
    var javaInstallStage by mutableStateOf("")
        private set
    var javaInstallProgress by mutableStateOf(JavaInstallProgress(""))
        private set
    var javaInstalling by mutableStateOf(false)
        private set
    var javaInstallError by mutableStateOf("")
        private set
    var availableJavaMajors by mutableStateOf<List<Int>>(emptyList())
        private set
    private var javaInstallJob: kotlinx.coroutines.Job? = null
    private var javaInstallGeneration = 0L

    var xmxMb by mutableStateOf(4096)

    var selectedInstanceId by mutableStateOf<String?>(null)
        private set

    var searchQuery by mutableStateOf("")

    var sort by mutableStateOf(InstanceSort.RECENT)

    var themeMode by mutableStateOf(ThemeMode.fromOverride(themeOverride()))

    var dynamicColor by mutableStateOf(true)

    var reduceMotion by mutableStateOf(false)

    var density by mutableStateOf(Density.COMFORTABLE)

    var settingsGroup by mutableStateOf(SettingsGroup.APPEARANCE)

    /** 获取内容页顶层 tab：0 = 安装游戏版本（向导），1 = 获取模组。默认第一个。 */
    var getContentTab by mutableStateOf(0)

    /** Hero / 空状态入口：直达「获取内容 → 安装游戏版本」第一步，版本列表开始加载。 */
    fun openInstallGame() {
        getContentTab = 0
        navigate(AppPage.GET_CONTENT)
        if (newInstanceUi.versions.isEmpty() && !newInstanceUi.loadingVersions) {
            newInstanceUi.loadVersions()
        }
    }

    /** 获取模组入口。 */
    fun openGetMods() {
        getContentTab = 1
        navigate(AppPage.GET_CONTENT)
    }

    /** CurseForge official API key (empty -> curse.tools proxy). */
    var curseForgeApiKey by mutableStateOf(initialCurseForgeApiKey)
        private set

    /** Third-party read-only curse.tools proxy switch, default on. */
    var allowCurseToolsProxy by mutableStateOf(initialAllowCurseToolsProxy)
        private set

    var activeInstanceId by mutableStateOf<String?>(null)
        private set

    var launchLogs by mutableStateOf<List<String>>(emptyList())
        private set

    var launchError by mutableStateOf<String?>(null)
        private set

    var logFilePath by mutableStateOf<String?>(null)
        private set

    var launchPending by mutableStateOf(false)
        private set

    val instancesRoot: String = instancesDirectory()

    val selectedJava: JavaRuntime?
        get() = javaRuntimes.firstOrNull { it.path == selectedJavaPath }

    /** Hero card: the most recently launched instance, else the first one. */
    val mainInstance: InstanceSummary?
        get() {
            if (instances.isEmpty()) return null
            val mostRecent = instances.maxByOrNull { lastLaunchTimes[it.id] ?: Long.MIN_VALUE }
            return if (mostRecent != null && lastLaunchTimes.containsKey(mostRecent.id)) {
                mostRecent
            } else {
                instances.first()
            }
        }

    val updateCount: Int get() = instances.count { it.updateAvailable }

    val recentInstances: List<InstanceSummary>
        get() = instances
            .filter { it.id != mainInstance?.id }
            .sortedByDescending { lastLaunchTimes[it.id] ?: Long.MIN_VALUE }
            .take(4)

    init {
        refresh()
    }

    fun navigate(target: AppPage) {
        page = target
    }

    /** Entry point for the 导入整合包 button, the drop target and the QA hook. */
    fun openImport(path: String) {
        navigate(AppPage.IMPORT)
        modpackUi.prepare(path)
    }

    fun saveCurseForgeApiKey(value: String) {
        curseForgeApiKey = value
        persistModpackSettings(value, allowCurseToolsProxy)
    }

    fun saveAllowCurseToolsProxy(value: Boolean) {
        allowCurseToolsProxy = value
        persistModpackSettings(curseForgeApiKey, value)
    }

    fun selectInstance(id: String) {
        selectedInstanceId = id
    }

    fun selectJava(path: String?) {
        selectedJavaPath = path
        javaManuallySelected = true
    }

    /** Background re-scan: reads release files only, never blocks the click. */
    fun rescanJava() {
        val installer = javaInstaller ?: return
        scope.launch {
            val found = runCatching { installer.rescan() }.getOrDefault(emptyList())
            javaRuntimes = found
            if (selectedJavaPath == null || found.none { it.path == selectedJavaPath }) {
                selectedJavaPath = found.firstOrNull { it.major == 21 }?.path ?: found.firstOrNull()?.path
            }
            println("Java rescan: ${found.size} runtimes")
        }
    }

    fun loadAvailableJavaMajors() {
        val installer = javaInstaller ?: return
        scope.launch {
            availableJavaMajors = runCatching { installer.availableMajors() }.getOrDefault(emptyList())
        }
    }

    fun dismissJavaInstall() {
        pendingJavaInstall = null
        javaInstallError = ""
    }

    /** Dialog's 自动安装 button / auto-manage path. */
    fun requestJavaInstall(instanceId: String, displayName: String, major: Int) {
        pendingJavaInstall = PendingJavaInstall(instanceId, displayName, major)
        javaInstallError = ""
        startJavaInstall(major)
    }

    private fun startJavaInstall(major: Int) {
        val installer = javaInstaller ?: return
        if (javaInstalling) return
        javaInstallJob?.cancel()
        val gen = ++javaInstallGeneration
        javaInstalling = true
        javaInstallError = ""
        javaInstallStage = "正在准备 Java $major…"
        javaInstallProgress = JavaInstallProgress("starting")
        javaInstallJob = scope.launch {
            // Step ladder (mirror of P0.2): 0s → 3s → 10s(+cancel) → 30s error.
            val stages = launch {
                kotlinx.coroutines.delay(3_000)
                if (javaInstallGeneration == gen && javaInstalling) {
                    javaInstallStage = "正在下载 Java $major…"
                }
                kotlinx.coroutines.delay(7_000)
                if (javaInstallGeneration == gen && javaInstalling) {
                    javaInstallStage = "网络较慢，仍在尝试"
                }
                kotlinx.coroutines.delay(20_000)
                if (javaInstallGeneration == gen && javaInstalling) {
                    javaInstallStage = "连接源超时（30s）；点重试或手动选择"
                }
            }
            var cancelled = false
            val result = try {
                installer.install(
                    major,
                    onProgress = { p ->
                        if (javaInstallGeneration == gen) javaInstallProgress = p
                    },
                    isCancelled = { javaInstallGeneration != gen },
                )
            } catch (e: Exception) {
                stages.cancel()
                JavaInstallResult(null, "", 0L, "", e.message ?: "install failed")
            }
            stages.cancel()
            if (javaInstallGeneration != gen) return@launch
            javaInstalling = false
            if (!result.error.contains("取消", true) && result.runtime != null) {
                javaInstallStage = "Java ${result.runtime.major} 安装完成（来源 ${result.usedSource}）"
                javaInstallError = ""
                rescanJava()
                // Auto-continue the launch that triggered this install.
                val target = pendingJavaInstall?.instanceId
                pendingJavaInstall = null
                if (target != null && target != "__settings__") launch(target)
            } else {
                javaInstallError = result.error.ifBlank { "安装未完成" }
                javaInstallStage = ""
            }
        }
    }

    fun cancelJavaInstall() {
        javaInstallGeneration++
        javaInstallJob?.cancel()
        javaInstallJob = null
        javaInstalling = false
        javaInstallStage = ""
        javaInstallError = "已取消，可重试；临时文件已清理"
    }

    fun changeGameDirectory(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == gameDirectory) return
        gameDirectory = trimmed
        repository = repositoryFactory(trimmed)
        selectedInstanceId = null
        refresh()
    }

    /** Called by the refresh button and on window re-focus. */
    fun refresh() {
        val scan = repository.scan()
        instanceReadFailures = scan.failed
        instances = scan.instances.map { summary ->
            summary.copy(
                status = if (summary.id == activeInstanceId) InstanceStatus.RUNNING else InstanceStatus.READY,
                lastLaunchedAt = lastLaunchTimes[summary.id],
            )
        }
        if (instances.none { it.id == selectedInstanceId }) {
            selectedInstanceId = instances.firstOrNull()?.id
        }
        modrinthUi.syncInstances()
    }

    fun launch(id: String? = null) {
        val target = id ?: selectedInstanceId ?: return
        if (activeInstanceId != null || launchPending) return

        val mcVersion = instances.firstOrNull { it.id == target }?.mcVersion ?: target
        val manualPath = if (javaManuallySelected) selectedJavaPath else null
        val choice = JavaSelector.select(mcVersion, javaRuntimes, manualPath)
        val java = choice.java
        val required = JavaMatrix.requiredMajor(mcVersion)
        // Old clients (<=1.16) are STRICT: a higher Java cannot run them. Even
        // though JavaSelector returns the nearest-higher with a warning, the
        // engine will reject it — treat it as missing so auto-install can offer Java 8.
        val unusableStrict = java != null && JavaMatrix.strict(mcVersion) && java.major != required
        if (java == null || unusableStrict) {
            val display = instances.firstOrNull { it.id == target }?.displayName ?: target
            if (autoManageJava && javaInstaller != null) {
                requestJavaInstall(target, display, required)
            } else {
                pendingJavaInstall = PendingJavaInstall(target, display, required)
            }
            return
        }
        println("Java selected: ${java.path} (${java.vendor} ${java.version}) reason=${choice.reason}")

        launchError = null
        launchLogs = emptyList()
        logFilePath = null
        launchPending = true
        println("[UI ${nowMillis()}] launch $target -> pending (isolated=$isolationEnabled)")

        scope.launch {
            val result = runCatching {
                engine.launch(
                    LaunchRequest(
                        instanceId = target,
                        gameDir = gameDirectory,
                        java = java,
                        xmxMb = xmxMb,
                        isolated = isolationEnabled,
                    ),
                )
            }.getOrElse { LaunchResult.Failed(it.message ?: it.javaClass.simpleName) }
            launchPending = false

            when (result) {
                is LaunchResult.Failed -> {
                    activeInstanceId = null
                    markOrigin(target, null)
                    launchError = result.message
                    println("[UI ${nowMillis()}] launch $target -> FAILED: ${result.message}")
                    refresh()
                }

                is LaunchResult.Started -> {
                    val handle = result.handle
                    activeInstanceId = target
                    markOrigin(target, LaunchOrigin.NORMAL)
                    lastLaunchTimes[target] = nowMillis()
                    logFilePath = handle.logFile
                    println("[UI ${nowMillis()}] launch $target -> 运行中 pid=${handle.pid} log=${handle.logFile}")
                    refresh()

                    scope.launch {
                        handle.logs.collect { line ->
                            launchLogs = (launchLogs + line).takeLast(2000)
                        }
                    }
                    scope.launch {
                        val code = engine.awaitExit(target)
                        val origin = originOf(target) ?: LaunchOrigin.NORMAL
                        activeInstanceId = null
                        when {
                            origin == LaunchOrigin.USER_STOP_REQUESTED ->
                                println("[UI ${nowMillis()}] stopped by user (exit=$code) $target -> 就绪")
                            code != 0 -> {
                                println("[UI ${nowMillis()}] launch $target -> 就绪 (crashed, exit=$code)")
                                launchError = classifyFailure(code, launchLogs, java, handle.logFile)
                            }
                            else ->
                                println("[UI ${nowMillis()}] launch $target -> 就绪 (exit=$code)")
                        }
                        markOrigin(target, null)
                        refresh()
                    }
                }
            }
        }
    }

    fun stop(id: String) {
        // Tag BEFORE killing the process tree: awaitExit's collector must see
        // USER_STOP_REQUESTED, otherwise a 143 would be misread as a crash.
        markOrigin(id, LaunchOrigin.USER_STOP_REQUESTED)
        if (engine.stop(id)) {
            println("[UI ${nowMillis()}] stop $id -> USER_STOP_REQUESTED (kill process tree)")
            launchLogs = launchLogs + "已请求停止 $id"
            activeInstanceId = null
            refresh()
        } else {
            // Nothing was running; drop the tentative tag.
            markOrigin(id, null)
        }
    }

    /** 打开文件夹 action — routed through AppState so the UI never does IO. */
    fun openInstanceFolder(id: String) {
        val path = instances.firstOrNull { it.id == id }?.path ?: return
        dev.mayo.ymcl.platform.openFolder(path)
    }

    fun filteredInstances(): List<InstanceSummary> {
        val query = searchQuery.trim()
        val matched = if (query.isEmpty()) {
            instances
        } else {
            instances.filter { instance ->
                instance.displayName.contains(query, ignoreCase = true) ||
                    instance.mcVersion.contains(query, ignoreCase = true) ||
                    instance.loaderLine.contains(query, ignoreCase = true)
            }
        }
        return when (sort) {
            InstanceSort.RECENT -> matched.sortedByDescending { it.lastLaunchedAt ?: Long.MIN_VALUE }
            InstanceSort.NAME -> matched.sortedBy { it.displayName }
            InstanceSort.PLAY_TIME -> matched.sortedByDescending { it.totalPlayTimeMs }
        }
    }

    fun runningCount(): Int = instances.count { it.status == InstanceStatus.RUNNING }

    private fun classifyFailure(code: Int, logs: List<String>, java: JavaRuntime, logFile: String): String {
        val tail = logs.takeLast(60).joinToString("\n")
        val reason = when {
            tail.contains("UnsupportedClassVersionError") ->
                "Java 版本不匹配：游戏需要更新的 Java，而当前是 ${java.vendor} ${java.version}。"
            tail.contains("NoClassDefFoundError") || tail.contains("ClassNotFoundException") ->
                "缺少 libraries 或模组依赖；请检查游戏目录是否完整。"
            tail.contains("Mixin") || tail.contains("mixin") ->
                "模组初始化失败（Mixin 冲突）；请查看日志里的第一个 Caused by。"
            tail.contains("Could not reserve enough space") || tail.contains("OutOfMemoryError") ->
                "内存不足；降低设置里的最大内存后重试。"
            else ->
                "游戏进程异常退出（退出码 $code）。"
        }
        return "$reason\n日志：$logFile"
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
