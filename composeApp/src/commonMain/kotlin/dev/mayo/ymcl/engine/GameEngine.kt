package dev.mayo.ymcl.engine

import dev.mayo.ymcl.domain.Instance

sealed interface LaunchResult {
    data class Started(val pid: Long? = null) : LaunchResult
    data class Failed(val message: String) : LaunchResult
}

/**
 * Boundary between the UI and a Minecraft engine. Phase 1 ships only the fake
 * implementation; HMCLCore will be adapted behind this interface in phase 2, so
 * nothing in ui/app depends on HMCL types.
 */
interface GameEngine {
    fun listInstances(): List<Instance>
    fun start(instanceId: String): LaunchResult
    fun stop(instanceId: String): Boolean
}
