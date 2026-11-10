package dev.mayo.ymcl.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure rules for 1.6–1.12 legacy assets. Old clients resolve sounds/textures by
 * their ORIGINAL name under `--assetsDir` (e.g. `minecraft/sounds/...`) instead
 * of by content hash, so the asset objects must be copied into a virtual tree.
 *
 * Mojang's current 1.12 / 1.7.10 index files no longer carry the `virtual`
 * flag, so both an explicit flag and the version range trigger virtualization.
 */
object LegacyAssets {
    private val json = Json { ignoreUnknownKeys = true }

    data class VirtualPlan(
        val root: String,
        val mapToResources: Boolean,
        /** (objects/<hh>/<hash>, virtual root/<original name>) */
        val copies: List<Pair<String, String>>,
        /** (objects/<hh>/<hash>, resources/<original name>) */
        val resourceCopies: List<Pair<String, String>>,
        val objectCount: Int,
        val dropped: Int,
    )

    /** Builds the copy plan from the asset-index JSON text; null when not legacy. */
    fun plan(indexText: String, mcVersion: String): VirtualPlan? {
        val index = json.parseToJsonElement(indexText).jsonObject
        val flagged = index["virtual"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        val mapFlagged = index["map_to_resources"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (!needsVirtual(flagged, mcVersion)) return null
        val map = needsMapToResources(mapFlagged, mcVersion)
        val root = virtualRoot(mcVersion)
        val objects = index["objects"]?.jsonObject ?: return null
        val copies = mutableListOf<Pair<String, String>>()
        val resourceCopies = mutableListOf<Pair<String, String>>()
        var dropped = 0
        objects.forEach { (name, value) ->
            val safe = safeAssetName(name)
            val hash = runCatching { value.jsonObject["hash"]?.jsonPrimitive?.content }.getOrNull()
            if (safe == null || hash == null) {
                dropped++
                return@forEach
            }
            val from = objectRel(hash)
            copies += from to "$root/$safe"
            if (map) resourceCopies += from to "resources/$safe"
        }
        return VirtualPlan(root, map, copies, resourceCopies, objects.size, dropped)
    }

    private val MC = Regex("""^1\.(\d+)(?:\.\d+)?$""")

    /** 1.6.x–1.12.x need virtual assets (and map_to_resources). */
    fun isLegacy(mcVersion: String): Boolean {
        val m = MC.matchEntire(mcVersion.trim()) ?: return false
        val minor = m.groupValues[1].toIntOrNull() ?: return false
        return minor in 6..12
    }

    fun needsVirtual(indexVirtual: Boolean, mcVersion: String): Boolean =
        indexVirtual || isLegacy(mcVersion)

    fun needsMapToResources(indexMap: Boolean, mcVersion: String): Boolean =
        indexMap || isLegacy(mcVersion)

    /** Virtual tree root, named after the MC version (acceptance #6). */
    fun virtualRoot(mcVersion: String): String = "assets/virtual/$mcVersion"

    /**
     * Sanitizes an asset object's original name. Keeps spaces / CJK (legal in
     * old asset names) but rejects absolute paths, drive letters and `..`.
     */
    fun safeAssetName(raw: String): String? {
        if (raw.isEmpty()) return null
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith('/')) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(raw)) return null
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." }) return null
        return segments.joinToString("/")
    }

    /** `objects/<hh>/<hash>` -> source relative path. */
    fun objectRel(hash: String): String = "assets/objects/${hash.take(2)}/$hash"
}
