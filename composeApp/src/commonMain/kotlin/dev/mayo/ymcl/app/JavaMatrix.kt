package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.JavaRuntime

/**
 * `mcVersion -> required Java major`: ≤1.16 → 8, 1.17–1.20.4 → 17,
 * 1.20.5+ → 21, unknown → 21. Old clients (≤1.16) only run on Java 8, so
 * for them the major must match EXACTLY; newer ones accept any Java >= required.
 */
object JavaMatrix {
    fun requiredMajor(mcVersion: String): Int {
        val parts = mcVersion.trim().split('.').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty() || parts[0] != 1) return 21
        val minor = parts.getOrNull(1) ?: return 21
        val patch = parts.getOrNull(2) ?: 0
        return when {
            minor <= 16 -> 8
            minor < 20 -> 17
            minor == 20 && patch <= 4 -> 17
            else -> 21
        }
    }

    /** ≤1.16 needs the exact major (Java 17 cannot run 1.12.2). */
    fun strict(mcVersion: String): Boolean = requiredMajor(mcVersion) == 8

    data class Selection(
        val java: JavaRuntime?,
        val required: Int,
        val available: List<Int>,
        val strict: Boolean,
    )

    fun select(mcVersion: String, runtimes: List<JavaRuntime>): Selection {
        val required = requiredMajor(mcVersion)
        val available = runtimes.map { it.major }.distinct().sorted()
        val java = if (strict(mcVersion)) {
            runtimes.filter { it.major == required }.minByOrNull { it.path }
        } else {
            runtimes.filter { it.major >= required }.maxByOrNull { it.major }
        }
        return Selection(java, required, available, strict(mcVersion))
    }

    /** Readable error when no registered Java satisfies the matrix. */
    fun missingMessage(selection: Selection): String =
        if (selection.strict) {
            "需要 Java ${selection.required}，本机只有 ${selection.available.joinToString("/")}。" +
                "旧版 Minecraft 无法在更高版本 Java 上运行。"
        } else {
            "需要 Java ${selection.required} 或更高，本机只有 ${selection.available.joinToString("/")}。"
        }
}
