package dev.mayo.ymcl.app

/**
 * Host-side process execution for Forge / NeoForge installers and the NeoForge
 * reachability probe. Kept as an interface so the loader logic stays in
 * `commonMain` and is unit-testable offline with a fake.
 */
data class InstallerRun(
    val exitCode: Int,
    val output: List<String>,
    val elapsedMs: Long,
)

data class Reachability(
    val reachable: Boolean,
    val attempts: Int,
    val httpCodes: List<Int>,
)

interface LoaderProcess {
    /** `curl -sI --max-time 20` repeated [attempts] times; any 2xx/3xx counts as reachable. */
    fun probe(url: String, attempts: Int = 3): Reachability

    /** Runs `java -jar <jar> <args...>` in [workDir]; captures stdout+stderr lines. */
    fun runInstaller(
        jarPath: String,
        args: List<String>,
        workDir: String,
        timeoutMinutes: Long = 10,
    ): InstallerRun
}

/** Default for unit tests / previews where no real installer should ever run. */
object UnavailableLoaderProcess : LoaderProcess {
    override fun probe(url: String, attempts: Int): Reachability =
        Reachability(false, attempts, List(attempts) { 0 })

    override fun runInstaller(
        jarPath: String,
        args: List<String>,
        workDir: String,
        timeoutMinutes: Long,
    ): InstallerRun = throw LoaderInstallException(
        "加载器安装进程不可用（无平台实现）：$jarPath",
    )
}
