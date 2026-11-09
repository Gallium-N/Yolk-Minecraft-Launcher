package dev.mayo.ymcl.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * State + async orchestration for the import wizard. The backend does the real
 * work on [scope]; Compose observes these mutableStateOf fields directly.
 */
class ModpackUiState(
    private val backend: ModpackBackend?,
    /** Navigate to the instances page and select the freshly imported id. */
    private val onFinished: (String) -> Unit,
    private val log: (String) -> Unit = ::println,
) {
    enum class Stage { PICK, CONFIRM, IMPORTING, DONE, ERROR }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var stage by mutableStateOf(Stage.PICK)
        private set
    var pathDraft by mutableStateOf("")
    var parsing by mutableStateOf(false)
        private set
    var parseError by mutableStateOf<String?>(null)
        private set
    var plan by mutableStateOf<ModpackPlan?>(null)
        private set
    var progress by mutableStateOf(ImportProgress("准备中", 0, 0, 0L, 0L, null))
        private set
    var summary by mutableStateOf<ImportSummary?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var failures by mutableStateOf<List<String>>(emptyList())
        private set
    var warnings by mutableStateOf<List<String>>(emptyList())
        private set

    /** Called by the 导入整合包 button, the drop target and the QA hook. */
    fun prepare(path: String) {
        val b = backend
        if (b == null) {
            parseError = "导入服务未初始化。"
            return
        }
        val trimmed = path.trim().removeSurrounding("\"").removeSurrounding("'")
        if (trimmed.isEmpty()) {
            parseError = "请先选择 .mrpack / .zip 整合包文件。"
            return
        }
        pathDraft = trimmed
        parsing = true
        parseError = null
        error = null
        failures = emptyList()
        warnings = emptyList()
        summary = null
        stage = Stage.PICK
        scope.launch {
            try {
                val p = b.parse(trimmed)
                plan = p
                warnings = p.warnings
                stage = Stage.CONFIRM
                log("MRPACK parsed name=${p.name} mc=${p.mcVersion} loader=${p.loaderKind} " +
                    "files=${p.files.size} skipped=${p.skippedServerOnly} overrides=${p.overrides.size}")
            } catch (e: Exception) {
                parseError = "解析失败：${e.message}"
                log("MRPACK parse error: ${e.message}")
            } finally {
                parsing = false
            }
        }
    }

    fun start() {
        val b = backend ?: return
        val p = plan ?: return
        stage = Stage.IMPORTING
        error = null
        failures = emptyList()
        progress = ImportProgress("准备导入", 0, p.files.size, 0L, p.totalBytes, null)
        scope.launch {
            try {
                val s = b.install(p) { progress = it }
                summary = s
                failures = s.failures
                warnings = s.warnings
                when {
                    s.cancelled -> {
                        error = "导入已取消（未完成）：已停止下载并清理 .part，已解压的目录保留。"
                        stage = Stage.ERROR
                    }
                    s.failures.isNotEmpty() -> {
                        error = "导入未完成，请查看下方失败清单。"
                        stage = Stage.ERROR
                    }
                    else -> {
                        stage = Stage.DONE
                        onFinished(s.instanceId)
                    }
                }
            } catch (e: Exception) {
                error = "导入失败：${e.message}"
                failures = listOf(e.message ?: e.javaClass.simpleName)
                stage = Stage.ERROR
            }
        }
    }

    fun cancel() {
        backend?.cancel()
        log("MRPACK cancel requested")
    }

    fun reset() {
        stage = Stage.PICK
        plan = null
        summary = null
        error = null
        failures = emptyList()
        warnings = emptyList()
        parseError = null
    }
}
