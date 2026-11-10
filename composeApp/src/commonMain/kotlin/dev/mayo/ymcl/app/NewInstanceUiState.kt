package dev.mayo.ymcl.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mayo.ymcl.domain.InstanceId
import dev.mayo.ymcl.domain.LoaderKind
import dev.mayo.ymcl.domain.VersionEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** State + orchestration for the 5-step "new instance" wizard (section G). */
class NewInstanceUiState(
    private val backend: NewInstanceBackend?,
    private val onFinished: (String) -> Unit,
    private val log: (String) -> Unit = ::println,
) {
    enum class Step { VERSION, LOADER, NAME, INSTALLING, DONE, ERROR }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var step by mutableStateOf(Step.VERSION)
        private set
    var versions by mutableStateOf<List<VersionEntry>>(emptyList())
        private set
    var versionsError by mutableStateOf<String?>(null)
        private set
    var loadingVersions by mutableStateOf(false)
        private set
    var versionQuery by mutableStateOf("")
    var showAllTypes by mutableStateOf(false)
    var selectedVersion by mutableStateOf<VersionEntry?>(null)
        private set

    var loaderKind by mutableStateOf(LoaderKind.VANILLA)
        private set
    var loaderVersions by mutableStateOf<List<String>>(emptyList())
        private set
    var loaderStatus by mutableStateOf("")
        private set
    var loadingLoaders by mutableStateOf(false)
        private set
    var selectedLoaderVersion by mutableStateOf<String?>(null)
        private set
    var neoForgeReachable by mutableStateOf(true)
        private set

    var displayName by mutableStateOf("")
    var instanceId by mutableStateOf("")
        private set
    var targetDir by mutableStateOf("")
        private set

    var stage by mutableStateOf(InstallStage("准备中", 0, 7, 0L))
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var failures by mutableStateOf<List<String>>(emptyList())
        private set
    var lastInstanceId by mutableStateOf("")
        private set

    val visibleVersions: List<VersionEntry>
        get() = versions.filter { v ->
            (showAllTypes || v.type.equals("release", true)) &&
                v.id.contains(versionQuery.trim(), true)
        }

    fun loadVersions() {
        val b = backend ?: return
        loadingVersions = true
        versionsError = null
        scope.launch {
            try {
                versions = b.loadVersions()
                log("NEW_INSTANCE versions=${versions.size}")
            } catch (e: Exception) {
                versionsError = "版本清单拉取失败：${e.message}"
            } finally {
                loadingVersions = false
            }
        }
    }

    fun selectVersion(v: VersionEntry) {
        selectedVersion = v
        neoForgeReachable = backend?.neoForgeReachable() ?: true
        step = Step.LOADER
        selectLoader(LoaderKind.VANILLA)
    }

    fun selectLoader(kind: LoaderKind) {
        loaderKind = kind
        selectedLoaderVersion = null
        loaderVersions = emptyList()
        loaderStatus = ""
        if (kind == LoaderKind.VANILLA) {
            proposeName()
            return
        }
        if (kind == LoaderKind.NEOFORGE && !neoForgeReachable) {
            loaderStatus = "NeoForge 官方源不可达，请检查网络或代理"
            proposeName()
            return
        }
        val b = backend ?: return
        val mc = selectedVersion?.id ?: return
        loadingLoaders = true
        scope.launch {
            try {
                val list = b.loaderVersions(kind, mc)
                loaderVersions = list
                selectedLoaderVersion = list.firstOrNull()
                loaderStatus = if (list.isEmpty()) "没有可用的 ${kind.displayName} 版本" else ""
            } catch (e: Exception) {
                loaderStatus = "${kind.displayName} 版本列表拉取失败：${e.message}"
            } finally {
                loadingLoaders = false
                proposeName()
            }
        }
    }

    fun selectLoaderVersion(v: String) {
        selectedLoaderVersion = v
        proposeName()
    }

    fun nextToName() {
        step = Step.NAME
        proposeName()
    }

    fun back() {
        step = when (step) {
            Step.NAME -> Step.LOADER
            Step.LOADER -> Step.VERSION
            else -> step
        }
    }

    private fun proposeName() {
        val mc = selectedVersion?.id ?: return
        val name = if (loaderKind == LoaderKind.VANILLA) mc else "$mc · ${loaderKind.displayName}"
        if (displayName.isBlank() || displayName.startsWith(mc)) displayName = name
        updateName(displayName)
    }

    fun updateName(value: String) {
        displayName = value
        val mc = selectedVersion?.id ?: "1.20.1"
        val existing = backend?.existingIds() ?: emptySet()
        instanceId = InstanceId.allocate(value.ifBlank { mc }, mc, existing)
        targetDir = "versions/$instanceId"
    }

    fun start(resume: Boolean = false) {
        val b = backend ?: return
        val v = selectedVersion ?: return
        if (instanceId.isBlank()) return
        step = Step.INSTALLING
        error = null
        failures = emptyList()
        stage = InstallStage("准备中", 0, 7, 0L)
        val id = instanceId
        val name = displayName
        val kind = loaderKind
        val loader = selectedLoaderVersion
        lastInstanceId = id
        scope.launch {
            val result = b.install(v.id, v, id, name, kind, loader) { stage = it }
            failures = result.failures
            when {
                result.cancelled -> {
                    error = "安装已取消（未完成）。已下载部分会在下次安装时复用。"
                    step = Step.ERROR
                }
                result.failures.isNotEmpty() -> {
                    error = "安装未完成，请查看失败清单。"
                    step = Step.ERROR
                }
                else -> {
                    step = Step.DONE
                    onFinished(result.instanceId)
                }
            }
        }
    }

    fun cancel() {
        backend?.cancel()
        log("NEW_INSTANCE cancel clicked")
    }

    fun reset() {
        step = Step.VERSION
        selectedVersion = null
        displayName = ""
        instanceId = ""
        error = null
        failures = emptyList()
        loadVersions()
    }
}
