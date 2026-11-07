package dev.mayo.ymcl.domain

import dev.mayo.ymcl.platform.PathUtil

enum class IsolationMode {
    /** `--gameDir` points at `versions/<instanceId>`; mods/saves live in the instance. */
    ISOLATED,

    /** `--gameDir` points at `<gameRoot>`; mods/saves are shared at the root. */
    SHARED_ROOT,
}

/**
 * Pure path model for one instance. HMCLCore's `DefaultGameRepositoryLayout` is
 * rooted at [gameRoot] (so `assets/`, `libraries/` and `versions/` are shared),
 * while the run directory (`--gameDir`/cwd) is either the instance dir or the
 * root. Keeping this object pure makes the launch layout testable offline.
 */
data class InstancePaths(
    val gameRoot: String,
    val instanceId: String,
    val mode: IsolationMode,
    val notice: String?,
) {
    val assetsDir: String get() = PathUtil.join(gameRoot, "assets")
    val librariesDir: String get() = PathUtil.join(gameRoot, "libraries")
    val versionsDir: String get() = PathUtil.join(gameRoot, "versions")
    val instanceDir: String get() = PathUtil.join(versionsDir, instanceId)
    val nativesDir: String get() = PathUtil.join(instanceDir, "natives")

    val runDir: String
        get() = when (mode) {
            IsolationMode.ISOLATED -> instanceDir
            IsolationMode.SHARED_ROOT -> gameRoot
        }

    /** The launch arguments that are printed into the log for acceptance. */
    fun launchArgs(): List<String> = listOf(
        "--gameDir", runDir,
        "--assetsDir", assetsDir,
        "--libraryDir", librariesDir,
        "-Djava.library.path=$nativesDir",
    )

    fun describe(): String =
        launchArgs().joinToString(" ") + " mode=" + when (mode) {
            IsolationMode.ISOLATED -> "isolated"
            IsolationMode.SHARED_ROOT -> "shared-root"
        }
}

object InstanceLayout {
    const val SHARED_MODS_NOTICE = "这是共享 mods 的老目录，仍按根目录运行"

    /**
     * Pure isolation decision. [rootModsNonEmpty] is computed by the host layer
     * (a directory probe); passing a boolean keeps the rule unit-testable.
     * A non-empty `<gameRoot>/mods/` forces the legacy shared-root mode and
     * produces a notice that the engine logs before launching.
     */
    fun resolve(
        settingsIsolated: Boolean,
        rootModsNonEmpty: Boolean,
        gameRoot: String,
        instanceId: String,
    ): InstancePaths {
        val forcedShared = rootModsNonEmpty
        val isolated = settingsIsolated && !forcedShared
        return InstancePaths(
            gameRoot = gameRoot,
            instanceId = instanceId,
            mode = if (isolated) IsolationMode.ISOLATED else IsolationMode.SHARED_ROOT,
            notice = if (forcedShared) SHARED_MODS_NOTICE else null,
        )
    }
}
