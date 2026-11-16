package dev.mayo.ymcl.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mayo.ymcl.domain.InstanceId
import dev.mayo.ymcl.domain.LoaderKind
import dev.mayo.ymcl.domain.VersionEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** State + orchestration for the 5-step "new instance" wizard (section G). */
class NewInstanceUiState(
    private val backend: NewInstanceBackend?,
    private val onFinished: (String) -> Unit,
    private val onInstancesChanged: () -> Unit = {},
    private val log: (String) -> Unit = ::println,
    private val loaderProbeTimeoutMs: Long = 8_000L,
) {
    enum class Step { VERSION, LOADER, NAME, INSTALLING, DONE, ERROR }

    /** Result of the loader-availability probe started the moment a version is picked. */
    enum class Probe { IDLE, LOADING, OK, EMPTY, TIMEOUT, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The probe is purely presentational: it never feeds install() and never
    // changes the default loader (still vanilla). It exists so a version click
    // has an instant, cancellable loading/error/success cycle (P0.2 §3).
    var probe by mutableStateOf(Probe.IDLE)
        private set
    var probeVersions by mutableStateOf<List<String>>(emptyList())
        private set
    var probeError by mutableStateOf("")
        private set
    private var probeJob: Job? = null
    // Bumped on every probe/loader request; late results from a superseded
    // request are ignored so clicks never stack up.
    private var probeGeneration = 0L
    private var loaderGeneration = 0L

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
    var brokenInstalls by mutableStateOf<List<BrokenInstall>>(emptyList())
        private set
    var pendingDeleteId by mutableStateOf<String?>(null)
        private set
    var deleteError by mutableStateOf<String?>(null)
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
        refreshBrokenInstalls()
    }

    /** Discovers interrupted installs so they can be resumed or discarded. */
    fun refreshBrokenInstalls() {
        scope.launch {
            brokenInstalls = try {
                backend?.brokenInstalls() ?: emptyList()
            } catch (e: Exception) {
                log("NEW_INSTANCE broken scan failed: ${e.message}")
                emptyList()
            }
        }
    }

    fun requestDelete(id: String) {
        deleteError = null
        pendingDeleteId = id
    }

    fun dismissDelete() {
        pendingDeleteId = null
    }

    fun confirmDelete() {
        val id = pendingDeleteId ?: return
        scope.launch {
            val ok = try {
                backend?.deleteBrokenInstall(id) ?: false
            } catch (e: Exception) {
                log("NEW_INSTANCE delete failed: ${e.message}")
                false
            }
            pendingDeleteId = null
            if (!ok) {
                deleteError = "删除失败：$id 不是未完成的安装，或目录被占用。"
                return@launch
            }
            log("NEW_INSTANCE deleted broken install id=$id")
            if (step == Step.ERROR && id == lastInstanceId) {
                // The failed install being shown is gone; go back to the version
                // list (reset reloads versions + refreshes broken installs).
                reset()
            } else {
                refreshBrokenInstalls()
            }
            onInstancesChanged()
        }
    }

    fun selectVersion(v: VersionEntry) {
        // Cancel any previous probe AND invalidate any in-flight loader fetch:
        // clicking a second version switches directly, requests never stack up.
        probeJob?.cancel()
        loaderGeneration++
        selectedVersion = v
        neoForgeReachable = backend?.neoForgeReachable() ?: true
        loaderKind = LoaderKind.VANILLA
        selectedLoaderVersion = null
        loaderVersions = emptyList()
        loaderStatus = ""
        loadingLoaders = false
        // Switch to the next step IMMEDIATELY (skeleton, not blank), then probe.
        step = Step.LOADER
        proposeName()
        probeLoaders(v.id)
    }

    /** Re-runs the loader probe after a transient failure. */
    fun retryProbe() {
        val mc = selectedVersion?.id ?: return
        probeLoaders(mc)
    }

    /** Error action: leave the loader step and go back to the version list. */
    fun backToVersions() {
        probeJob?.cancel()
        loaderGeneration++
        probe = Probe.IDLE
        step = Step.VERSION
    }

    private fun probeLoaders(mc: String) {
        probeJob?.cancel()
        val gen = ++probeGeneration
        probe = Probe.LOADING
        probeError = ""
        probeVersions = emptyList()
        val b = backend
        if (b == null) {
            probe = Probe.FAILED
            probeError = "加载器数据未就绪；点重试或返回列表。"
            return
        }
        probeJob = scope.launch {
            // The backend meta fetch is a BLOCKING java.net call with its own
            // retry loop (connect 20s / read 60s), so coroutine cancellation
            // cannot interrupt it. A separate watchdog enforces the hard 8s
            // deadline; a generation token discards any late result.
            launch {
                delay(loaderProbeTimeoutMs)
                if (probeGeneration == gen && probe == Probe.LOADING) {
                    probe = Probe.TIMEOUT
                    probeError = "maven.fabricmc.net 超时（${loaderProbeTimeoutMs / 1000}s）；点重试或返回列表。"
                }
            }
            val outcome = runCatching { b.loaderVersions(LoaderKind.FABRIC, mc) }
            if (probeGeneration != gen || probe != Probe.LOADING) return@launch
            when {
                outcome.isFailure -> {
                    probe = Probe.FAILED
                    probeError = "Fabric 版本拉取失败：${outcome.exceptionOrNull()?.message}；点重试或返回列表。"
                }
                outcome.getOrNull().isNullOrEmpty() -> {
                    probe = Probe.EMPTY
                    probeError = "该版本没有 Fabric 构建；选原版或返回列表。"
                }
                else -> {
                    probeVersions = outcome.getOrNull()!!.take(8)
                    probe = Probe.OK
                }
            }
        }
    }

    fun selectLoader(kind: LoaderKind) {
        // A manual loader choice ends the probe phase; cancel it so requests
        // never stack up (P0.2 §3).
        probeJob?.cancel()
        probe = Probe.IDLE
        probeError = ""
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
        val gen = ++loaderGeneration
        loadingLoaders = true
        scope.launch {
            // Same watchdog as the probe: blocking meta fetch cannot be
            // cancelled, so enforce the 8s deadline independently and ignore
            // late/superseded results.
            launch {
                delay(loaderProbeTimeoutMs)
                if (loaderGeneration == gen && loadingLoaders) {
                    loadingLoaders = false
                    loaderStatus = "${kind.displayName} 源超时（${loaderProbeTimeoutMs / 1000}s）；重试或切换加载器"
                    proposeName()
                }
            }
            val outcome = runCatching { b.loaderVersions(kind, mc) }
            if (loaderGeneration != gen || !loadingLoaders) return@launch
            when {
                outcome.isFailure -> loaderStatus = "${kind.displayName} 版本拉取失败：${outcome.exceptionOrNull()?.message}"
                outcome.getOrNull().isNullOrEmpty() -> loaderStatus = "没有可用的 ${kind.displayName} 版本"
                else -> {
                    val list = outcome.getOrNull()!!
                    loaderVersions = list
                    selectedLoaderVersion = list.firstOrNull()
                    loaderStatus = ""
                }
            }
            loadingLoaders = false
            proposeName()
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
        probeJob?.cancel()
        loaderGeneration++
        probe = Probe.IDLE
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
                    refreshBrokenInstalls()
                }
                result.failures.isNotEmpty() -> {
                    error = "安装未完成，请查看失败清单。"
                    step = Step.ERROR
                    refreshBrokenInstalls()
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
        probeJob?.cancel()
        loaderGeneration++
        probe = Probe.IDLE
        step = Step.VERSION
        selectedVersion = null
        displayName = ""
        instanceId = ""
        error = null
        failures = emptyList()
        loadVersions()
    }
}
