package dev.mayo.ymcl.engine

import dev.mayo.ymcl.domain.Instance
import dev.mayo.ymcl.domain.JavaRuntime
import kotlinx.coroutines.flow.Flow

/**
 * What the UI asks the engine to launch. [gameDir] is the shared repository
 * root (`<gameRoot>`), where `assets/`, `libraries/` and `versions/` live.
 */
data class LaunchRequest(
    val instanceId: String,
    val gameDir: String,
    val java: JavaRuntime,
    val xmxMb: Int,
    val extraJvmArgs: List<String> = emptyList(),
    /** When true (default) `--gameDir` is the instance dir; false uses the root. */
    val isolated: Boolean = true,
    /** Explicit MC version; when null the engine falls back to id heuristics. */
    val mcVersion: String? = null,
)

/**
 * Handle for one running process. [logs] replays recent lines so a collector that
 * subscribes just after launch still sees the first lines; [exitCode] completes once.
 */
data class LaunchHandle(
    val pid: Long?,
    val logFile: String,
    val logs: Flow<String>,
    val exitCode: Flow<Int>,
)

sealed interface LaunchResult {
    data class Started(val handle: LaunchHandle) : LaunchResult
    data class Failed(val message: String) : LaunchResult
}

/**
 * Boundary between the UI and a Minecraft engine. HMCL types stay in the desktop
 * engine implementation; common code only sees this interface and domain models.
 */
interface GameEngine {
    fun listInstances(gameDir: String): List<Instance>
    fun detectJava(): List<JavaRuntime>
    fun launch(request: LaunchRequest): LaunchResult

    /** Blocks until the process exits (call from an IO coroutine). */
    fun awaitExit(instanceId: String): Int

    fun stop(instanceId: String): Boolean
}
