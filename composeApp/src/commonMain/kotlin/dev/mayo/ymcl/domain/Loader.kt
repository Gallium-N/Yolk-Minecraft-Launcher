package dev.mayo.ymcl.domain

/** Java runtime that a particular Minecraft version is happiest with. */
enum class LoaderKind(val id: String, val displayName: String) {
    VANILLA("vanilla", "Vanilla"),
    FABRIC("fabric", "Fabric"),
    FORGE("forge", "Forge"),
    NEOFORGE("neoforge", "NeoForge"),
    QUILT("quilt", "Quilt");

    companion object {
        fun fromId(raw: String): LoaderKind? =
            entries.firstOrNull { it.id.equals(raw.trim(), ignoreCase = true) }
    }
}

/**
 * Loader of one instance. [display] is what the UI shows: "Forge 47.4.16",
 * while vanilla instances only show "Vanilla".
 */
data class Loader(
    val kind: LoaderKind,
    val version: String = "",
) {
    val display: String
        get() = if (kind == LoaderKind.VANILLA || version.isBlank()) {
            kind.displayName
        } else {
            "${kind.displayName} $version"
        }

    companion object {
        val Vanilla = Loader(LoaderKind.VANILLA)
    }
}
