package dev.mayo.ymcl.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class ModrinthDialog(
    val hit: ModrinthHit,
    val version: ModrinthVersion,
    val resolution: ModResolver.Resolution,
)

/**
 * State + async orchestration for the 获取内容 page. Compose observes these
 * mutableStateOf fields directly; all network/disk work runs on [scope].
 */
class ModrinthUiState(
    private val service: ModrinthService?,
    private val instances: () -> List<InstanceSummary>,
    private val isRunning: (String) -> Boolean,
    private val log: (String) -> Unit = ::println,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var query by mutableStateOf("")
    var results by mutableStateOf<List<ModrinthResultRow>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var selectedInstanceId by mutableStateOf<String?>(null)
    var dialog by mutableStateOf<ModrinthDialog?>(null)
    var optionalChecked by mutableStateOf<Set<String>>(emptySet())
    var installing by mutableStateOf(false)
    var progressFile by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    var installedProjectIds by mutableStateOf<Set<String>>(emptySet())

    val selectedInstance: InstanceSummary?
        get() {
            val list = instances()
            return list.firstOrNull { it.id == selectedInstanceId } ?: list.firstOrNull()
        }

    /** Called when the instance list changes (startup / focus refresh). */
    fun syncInstances() {
        val current = selectedInstance
        if (current != null && selectedInstanceId != current.id) selectedInstanceId = current.id
        refreshInstalled()
    }

    private fun refreshInstalled() {
        val id = selectedInstance?.id ?: return
        installedProjectIds = runCatching { service?.installed(id) ?: emptySet() }.getOrDefault(emptySet())
    }

    fun selectInstance(id: String) {
        selectedInstanceId = id
        refreshInstalled()
        search(query)
    }

    fun search(text: String) {
        query = text
        val instance = selectedInstance
        if (instance == null) {
            error = "还没有实例，请先在「实例」页创建一个实例。"
            results = emptyList()
            return
        }
        if (modLoaderOf(instance) == null) {
            error = "${instance.displayName} 是原版实例，没有模组加载器；请选择 Fabric / Forge 实例。"
            results = emptyList()
            return
        }
        if (service == null) {
            error = "Modrinth 服务未初始化。"
            return
        }
        loading = true
        error = null
        val svc = service
        scope.launch {
            try {
                val rows = svc.search(instance, text)
                results = rows
                loading = false
                if (rows.isEmpty()) {
                    error = if (text.isBlank()) {
                        "${instance.mcVersion} + ${instance.loaderLine} 下暂时没有热门模组"
                    } else {
                        "没有找到与「$text」匹配的模组（${instance.mcVersion} + ${instance.loaderLine}）"
                    }
                } else {
                    log("MR search '$text' -> ${rows.size} hits for ${instance.id}")
                }
            } catch (e: Exception) {
                loading = false
                results = emptyList()
                error = "搜索失败：${e.message}"
                log("MR search error: ${e.message}")
            }
        }
    }

    /** Step 1 of install: strict version check + running guard + dependency plan. */
    fun openInstall(row: ModrinthResultRow) {
        val instance = selectedInstance ?: return
        val version = row.version
        if (version == null) {
            error = "${instance.mcVersion} + ${instance.loaderLine} 下没有发布版本（${row.hit.title}）。"
            return
        }
        if (isRunning(instance.id)) {
            error = "实例 ${instance.displayName} 正在运行中，无法安装模组（进程会锁定 mods 文件）。请先停止游戏。"
            return
        }
        val svc = service ?: return
        loading = true
        error = null
        scope.launch {
            try {
                val resolution = svc.plan(instance, version)
                loading = false
                if (resolution.blocked != null) {
                    error = resolution.blocked
                    return@launch
                }
                if (resolution.errors.isNotEmpty()) {
                    error = "依赖解析失败：" + resolution.errors.joinToString("; ")
                    return@launch
                }
                optionalChecked = emptySet()
                dialog = ModrinthDialog(row.hit, version, resolution)
            } catch (e: Exception) {
                loading = false
                error = "无法解析依赖：${e.message}"
            }
        }
    }

    fun toggleOptional(versionId: String) {
        optionalChecked = if (versionId in optionalChecked) {
            optionalChecked - versionId
        } else {
            optionalChecked + versionId
        }
    }

    fun dismissDialog() {
        dialog = null
    }

    fun confirmInstall() {
        val d = dialog ?: return
        val instance = selectedInstance ?: return
        if (isRunning(instance.id)) {
            error = "实例 ${instance.displayName} 正在运行中，无法安装模组。请先停止游戏。"
            dialog = null
            return
        }
        val svc = service ?: return
        dialog = null
        installing = true
        error = null
        val selected = optionalChecked
        scope.launch {
            try {
                val report = svc.install(
                    instance = instance,
                    root = d.version,
                    selectedOptionalIds = selected,
                    instanceRunning = isRunning(instance.id),
                    onProgress = { name -> progressFile = name },
                )
                installing = false
                progressFile = null
                refreshInstalled()
                when {
                    report.failures.isNotEmpty() -> {
                        error = "安装失败：" + report.failures.joinToString("; ")
                        log("MR install failures: ${report.failures.size}")
                    }
                    report.installed.isEmpty() -> toast = "${d.hit.title} 已是最新，无需重复安装"
                    else -> toast = "已安装 ${d.hit.title}（${report.installed.size} 个文件）"
                }
            } catch (e: ModInstallException) {
                installing = false
                progressFile = null
                error = e.message
                log("MR install refused: ${e.message}")
            } catch (e: Exception) {
                installing = false
                progressFile = null
                error = "安装失败：${e.message}"
                log("MR install error: ${e.message}")
            }
        }
    }

    fun clearToast() {
        toast = null
    }
}
