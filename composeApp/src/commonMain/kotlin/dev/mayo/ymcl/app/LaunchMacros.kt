package dev.mayo.ymcl.app

/** Launch macros HMCLCore does not define; substituted at launch time. */
object LaunchMacros {
    /**
     * [clientId] is a 32-char hex string generated once per session by the
     * engine (commonMain has no UUID). `${auth_xuid}` stays an empty string so
     * the argument keeps its position in the command line.
     */
    fun extras(clientId: String): Map<String, String> = mapOf(
        "\${clientid}" to clientId,
        "\${auth_xuid}" to "",
    )
}
