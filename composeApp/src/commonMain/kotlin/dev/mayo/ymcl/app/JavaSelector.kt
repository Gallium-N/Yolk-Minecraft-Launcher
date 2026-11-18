package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.JavaRuntime

/**
 * Pure Java selection (P7 §2). Decides which runtime launches a given MC
 * version, with an explicit, loggable reason. No IO, no coroutines.
 */
data class JavaChoice(
    val java: JavaRuntime?,
    val reason: String,
)

object JavaSelector {
    /** Full numeric version for "highest patch wins" within one major. */
    private fun patchOf(version: String): Int {
        // "21.0.13" -> 13, "1.8.0_412" -> 412, "17.0.12+7" -> 12.
        val underscore = version.substringAfter('_', "")
        if (underscore.isNotBlank()) return underscore.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        val parts = version.split('+').first().split('.').mapNotNull { it.toIntOrNull() }
        return parts.getOrNull(2) ?: 0
    }

    /**
     * [manualPath], when set and present in [runtimes], wins outright (a
     * mismatch is only a warning). Otherwise apply the version matrix: exact
     * major first (highest patch), then nearest-higher, then nearest-lower.
     */
    fun select(
        mcVersion: String,
        runtimes: List<JavaRuntime>,
        manualPath: String? = null,
    ): JavaChoice {
        if (!manualPath.isNullOrBlank()) {
            val manual = runtimes.firstOrNull { it.path == manualPath }
            if (manual != null) return JavaChoice(manual, "user override")
        }

        val required = JavaMatrix.requiredMajor(mcVersion)
        val exact = runtimes.filter { it.major == required }
        if (exact.isNotEmpty()) {
            val best = exact.maxWith(compareBy<JavaRuntime> { patchOf(it.version) }.thenBy { it.path })
            return JavaChoice(best, "exact match ${best.version}")
        }

        // No exact major: nearest higher first, then nearest lower. For strict
        // (<=1.16, needs Java 8) a higher Java usually can't run the old client,
        // so we still pick it but flag a warning (the user can override / install 8).
        val strict = JavaMatrix.strict(mcVersion)
        val higher = runtimes.filter { it.major > required }.minByOrNull { it.major }
        if (higher != null) {
            val suffix = if (strict) " (requested $required, warning)" else " (requested $required)"
            return JavaChoice(higher, "nearest newer ${higher.major}$suffix")
        }
        val lower = runtimes.filter { it.major < required }.maxByOrNull { it.major }
        return if (lower != null) {
            JavaChoice(lower, "nearest lower ${lower.major} (requested $required, warning)")
        } else {
            JavaChoice(null, "no Java >= $required (requested by $mcVersion)")
        }
    }
}
