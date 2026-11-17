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

/** Per-(loader, mc) key for the in-session loader-version cache. */
private data class LoaderCacheKey(val kind: LoaderKind, val mc: String)

/** State + orchestration for the 5-step "new instance" wizard (section G). */
class NewInstanceUiState(
    private val backend: NewInstanceBackend?,
    private val onFinished: (String) -> Unit,
    private val onInstancesChanged: () -> Unit = {},
    private val log: (String) -> Unit = ::println,
    private val loaderProbeTimeoutMs: Long = 30_000L,
) {
    enum class Step { VERSION, LOADER, NAME, INSTALLING, DONE, ERROR }

    /** Result of the loader-availability probe started the moment a version is picked. */
    enum class Probe { IDLE, CONNECTING, SLOW, OK, EMPTY, TIMEOUT, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The probe is purely presentational: it never feeds install() and never
    // changes the default loader (still vanilla). It exists so a version click
    // has an instant, cancellable loading/error/success cycle (P0.2 §3).
    var probe by mutableStateOf(Probe.IDLE)
        private set
    var probeError by mutableStateOf("")
        private set
    /** Stage hint for the LOADER progress bar: 0s / 3s / 10s ladder. */
    var probeHint by mutableStateOf("")
        private set
    /** True once the 10s "network slow" stage is reached (enables 取消). */
    var probeCanCancel by mutableStateOf(false)
        private set
    private var probeJob: Job? = null
    // Bumped on every probe/loader request; late results from a superseded
    // request are ignored so clicks never stack up.
    private var probeGeneration = 0L
    private var loaderGeneration = 0L
    /** In-session cache of loader versions, keyed by (loader, mc). Switching
     * away and back reuses the list with no network. */
    private val loaderCache = mutableMapOf<LoaderCacheKey, List<String>>()

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
        println("[UI] step=$step probe=$probe -> selectVersion ${v.id}")
        // Cancel any previous probe AND invalidate any in-flight loader fetch:
        // clicking a second version switches directly, requests never stack up.
        cancelProbe()
        selectedVersion = v
        // NeoForge reachability is a background curl used ONLY to grey out the
        // NeoForge chip. It never blocks the click path (rework diagnosis) and
        // is not a loader-list request — the vanilla default path makes none.
        neoForgeReachable = true
        scope.launch {
            val reachable = runCatching { backend?.neoForgeReachable() ?: true }.getOrDefault(true)
            neoForgeReachable = reachable
        }
        // Default to VANILLA with NO loader probe at all: vanilla needs no
        // versions and must not hit the ~12s fabric maven request (p0b-loader).
        loaderKind = LoaderKind.VANILLA
        selectedLoaderVersion = null
        loaderVersions = emptyList()
        loaderStatus = ""
        loadingLoaders = false
        step = Step.LOADER
        println("[UI] step=$step probe=$probe loader=VANILLA (after set LOADER, no loader probe)")
        proposeName()
    }

    /** Re-runs the loader probe after a transient failure. */
    fun retryProbe() {
        val mc = selectedVersion?.id ?: return
        val kind = loaderKind
        if (kind == LoaderKind.VANILLA) return
        probeLoaders(kind, mc, forceRefresh = true)
    }

    /** Error action: leave the loader step and go back to the version list. */
    fun backToVersions() {
        probeJob?.cancel()
        loaderGeneration++
        probe = Probe.IDLE
        probeError = ""
        probeHint = ""
        probeCanCancel = false
        step = Step.VERSION
    }

    /** Cancel the in-flight loader wait (10s+ stage button, or switch to vanilla). */
    fun cancelProbe() {
        probeJob?.cancel()
        probeJob = null
        // Invalidate the waiting coroutine on BOTH tokens: a late loaderVersions
        // result must never repopulate a list after the user cancelled/switched.
        probeGeneration++
        loaderGeneration++
        probe = Probe.IDLE
        probeError = ""
        probeHint = ""
        probeCanCancel = false
        loadingLoaders = false
        loaderVersions = emptyList()
        selectedLoaderVersion = null
        println("[UI] probe cancelled")
    }

    /**
     * Probe [kind] versions for [mc] and show the list. Only called for a
     * non-vanilla loader, so the default path makes no loader-list request.
     * Results are cached per (kind, mc): switching away and back reuses the
     * list with zero network. A successful refresh ignores stale generations.
     */
    private fun probeLoaders(kind: LoaderKind, mc: String, forceRefresh: Boolean = false) {
        probeJob?.cancel()
        val gen = ++probeGeneration
        probeError = ""
        probeCanCancel = false
        loadingLoaders = false
        loaderStatus = ""
        // Cache hit: reuse instantly, no network. The disk cache underneath
        // (Fabric) still applies; this is the per-loader in-session layer.
        val key = LoaderCacheKey(kind, mc)
        val cached = loaderCache[key]
        if (!forceRefresh && cached != null) {
            loaderVersions = cached
            selectedLoaderVersion = cached.firstOrNull()
            probe = Probe.OK
            probeHint = ""
            println("[UI] loader cache hit ${kind.name} $mc")
            return
        }

        probe = Probe.CONNECTING
        loaderVersions = emptyList()
        selectedLoaderVersion = null
        probeHint = "正在获取 ${kind.displayName} 版本…"
        println("[UI] probeLoaders kind=${kind.name} mc=$mc")
        val b = backend
        if (b == null) {
            probe = Probe.FAILED
            probeError = "加载器数据未就绪；点重试或返回列表。"
            return
        }
        loadingLoaders = true
        probeJob = scope.launch {
            // Stage ladder (no 8s false failure): 0s 获取 → 3s 连接源 →
            // 10s 较慢 + 取消 → 30s hard cap = readable error.
            val stages = launch {
                delay(3_000)
                if (probeGeneration == gen && probe == Probe.CONNECTING) {
                    probeHint = "正在连接 ${kind.displayName} 源…"
                    println("[UI] probe stage=3s hint=$probeHint")
                }
                delay(7_000) // t=10s
                if (probeGeneration == gen && probe == Probe.CONNECTING) {
                    probe = Probe.SLOW
                    probeHint = "网络较慢，仍在尝试"
                    probeCanCancel = true
                    println("[UI] probe stage=10s hint=$probeHint canCancel=$probeCanCancel")
                }
                delay(20_000) // t=30s hard cap
                if (probeGeneration == gen && (probe == Probe.CONNECTING || probe == Probe.SLOW)) {
                    probe = Probe.TIMEOUT
                    probeCanCancel = false
                    loadingLoaders = false
                    probeError = "连接 ${kind.displayName} 源超时（30s）；点重试或返回列表。"
                }
            }
            val outcome = runCatching { b.loaderVersions(kind, mc) }
            stages.cancel()
            if (probeGeneration != gen || probe == Probe.TIMEOUT || probe == Probe.IDLE) return@launch
            loadingLoaders = false
            probeCanCancel = false
            when {
                outcome.isFailure -> {
                    probe = Probe.FAILED
                    probeError = "${kind.displayName} 版本拉取失败：${outcome.exceptionOrNull()?.message}；点重试或返回列表。"
                }
                outcome.getOrNull().isNullOrEmpty() -> {
                    probe = Probe.EMPTY
                    probeError = "该版本没有 ${kind.displayName} 构建；选原版或返回列表。"
                }
                else -> {
                    val list = outcome.getOrNull()!!
                    loaderCache[key] = list
                    loaderVersions = list
                    selectedLoaderVersion = list.firstOrNull()
                    probe = Probe.OK
                    probeHint = ""
                }
            }
        }
    }

    fun selectLoader(kind: LoaderKind) {
        // Any loader change ends the current probe: cancel it so requests never
        // stack up and no late result can repopulate a different loader's list.
        cancelProbe()
        loaderKind = kind
        selectedLoaderVersion = null
        loaderVersions = emptyList()
        loaderStatus = ""
        // VANILLA shows no list and makes no loader-list request at all.
        if (kind == LoaderKind.VANILLA) {
            println("[UI] selectLoader VANILLA — no probe, no list")
            proposeName()
            return
        }
        if (kind == LoaderKind.NEOFORGE && !neoForgeReachable) {
            loaderStatus = "NeoForge 官方源不可达，请检查网络或代理"
            proposeName()
            return
        }
        val mc = selectedVersion?.id ?: return
        probeLoaders(kind, mc)
        proposeName()
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
        probeHint = ""
        probeCanCancel = false
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
        probeError = ""
        probeHint = ""
        probeCanCancel = false
        step = Step.VERSION
        selectedVersion = null
        displayName = ""
        instanceId = ""
        error = null
        failures = emptyList()
        loadVersions()
    }
}
