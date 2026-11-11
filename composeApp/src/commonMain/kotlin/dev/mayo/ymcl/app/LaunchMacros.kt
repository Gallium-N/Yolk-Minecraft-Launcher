package dev.mayo.ymcl.app

/** Launch macros HMCLCore does not define; substituted at launch time. */
object LaunchMacros {
    /**
     * [clientId] is a 32-char hex string generated once per session by the
     * engine (commonMain has no UUID). `${auth_xuid}` becomes a quoted empty
     * string so the `--xuid` option keeps its value slot: HMCLCore rejects
     * blank argv entries ("Illegal command line"), so a literal empty string
     * cannot be used, while `""` renders as an empty string in the logged
     * command line and is harmless to the offline game.
     */
    fun extras(clientId: String): Map<String, String> = mapOf(
        "\${clientid}" to clientId,
        "\${auth_xuid}" to "\"\"",
        // HMCLCore's DEFAULT_JVM_ARGUMENTS carries these two but never defines
        // them, so without a mapping they leak into the command line as-is.
        "\${launcher_name}" to "YMCL",
        "\${launcher_version}" to "0.1",
    )
}
