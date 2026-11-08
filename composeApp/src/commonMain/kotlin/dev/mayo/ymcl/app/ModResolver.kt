package dev.mayo.ymcl.app

/**
 * Pure Modrinth decision logic: no IO, no java.*. Everything that needs the
 * network is injected as a [lookup] function, so the whole dependency graph,
 * version choice and filename policy are unit-testable offline.
 */
object ModResolver {
    const val MAX_DEPTH = 5
    const val MAX_FILENAME = 100

    /** 2-D facets JSON, e.g. [["project_type:mod"],["versions:1.20.1"],["categories:fabric"]]. */
    fun facets(mcVersion: String, loader: String, projectType: String = "mod"): String =
        "[[\"project_type:$projectType\"],[\"versions:$mcVersion\"],[\"categories:$loader\"]]"

    /** Strip path separators, reject any "..", truncate to 100 chars (keep .jar). */
    fun safeFilename(raw: String): String? {
        if (raw.isBlank() || raw.contains("..")) return null
        val base = raw.replace('\\', '/').substringAfterLast('/')
        val cleaned = base.filter { it.code >= 32 && it != '/' && it != '\\' }
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
        if (cleaned.length <= MAX_FILENAME) return cleaned
        val ext = if (cleaned.endsWith(".jar", ignoreCase = true)) ".jar" else ""
        return cleaned.take(MAX_FILENAME - ext.length) + ext
    }

    /** Strict double filter — no "guessing it might work" across loaders. */
    fun compatible(version: ModrinthVersion, mcVersion: String, loader: String): Boolean =
        mcVersion in version.gameVersions && loader.lowercase() in version.loaders.map { it.lowercase() }

    private fun channelRank(type: String): Int = when (type.lowercase()) {
        "release" -> 0
        "beta" -> 1
        "alpha" -> 2
        else -> 3
    }

    /** All versions ordered best-first; alpha stays visible at the bottom. */
    fun visibleVersions(versions: List<ModrinthVersion>): List<ModrinthVersion> =
        versions.sortedWith(
            compareBy<ModrinthVersion> { channelRank(it.versionType) }
                .thenByDescending { it.datePublished },
        )

    /** Latest release; if none, latest beta. Alpha is never the default. */
    fun defaultVersion(versions: List<ModrinthVersion>): ModrinthVersion? {
        val releases = versions.filter { it.versionType.equals("release", ignoreCase = true) }
        if (releases.isNotEmpty()) return releases.maxByOrNull { it.datePublished }
        val betas = versions.filter { it.versionType.equals("beta", ignoreCase = true) }
        return betas.maxByOrNull { it.datePublished }
    }

    class CycleException(message: String) : Exception(message)

    data class Planned(
        val version: ModrinthVersion,
        val file: ModrinthFileRef,
        val depth: Int,
        val reason: String, // root | required | optional | optional(selected)
    )

    data class Resolution(
        val install: List<Planned>,
        val optional: List<Planned>,
        val blocked: String?,
        val errors: List<String>,
        val visited: Set<String>,
    )

    /**
     * Walk the dependency graph. Only `required` is auto-installed; `optional`
     * is returned for the UI to tick (default off); `incompatible` already
     * installed blocks the install; `embedded` counts as included. Cycle guard
     * is `projectId@versionId`, recursion is capped at [MAX_DEPTH].
     */
    fun resolve(
        root: ModrinthVersion,
        installed: Set<String>,
        lookup: (String) -> ModrinthVersion?,
        selectedOptionalIds: Set<String> = emptySet(),
    ): Resolution {
        val install = mutableListOf<Planned>()
        val optional = mutableListOf<Planned>()
        val errors = mutableListOf<String>()
        val done = mutableSetOf<String>()
        val stack = mutableSetOf<String>()
        var blocked: String? = null

        fun keyOf(v: ModrinthVersion) = "${v.projectId}@${v.id}"

        fun plan(v: ModrinthVersion, depth: Int, reason: String) {
            val key = keyOf(v)
            if (key in stack) {
                throw CycleException("检测到循环依赖：$key（依赖链 ${stack.joinToString(" -> ")} -> $key）")
            }
            if (!done.add(key)) return
            stack += key
            try {
                if (v.projectId in installed) return
                val file = v.primaryFile
                if (file == null) {
                    errors += "${v.projectId}@${v.id} 没有可用文件"
                    return
                }
                install += Planned(v, file, depth, reason)

                for (dep in v.dependencies) {
                    when (dep.dependencyType.lowercase()) {
                        "embedded" -> Unit // bundled inside the parent jar
                        "incompatible" -> {
                            val pid = dep.projectId
                            if (pid != null && pid in installed) {
                                blocked = "已安装的模组 $pid 与 ${v.projectId} 不兼容，阻止安装。"
                            }
                        }
                        "optional" -> {
                            val dv = dep.versionId?.let(lookup)
                            if (dv != null) {
                                val f = dv.primaryFile
                                if (f != null) {
                                    val p = Planned(dv, f, depth + 1, "optional")
                                    optional += p
                                    if (dv.id in selectedOptionalIds) {
                                        plan(dv, depth + 1, "optional(selected)")
                                    }
                                }
                            }
                        }
                        "required" -> {
                            val vid = dep.versionId
                            if (vid == null) {
                                errors += "${v.projectId} 的 required 依赖缺少 version_id，无法补齐"
                                continue
                            }
                            val dv = lookup(vid)
                            if (dv == null) {
                                errors += "无法解析 required 依赖版本 $vid"
                                continue
                            }
                            if (depth + 1 > MAX_DEPTH) {
                                errors += "依赖深度超过 $MAX_DEPTH，已截断：${keyOf(dv)}"
                                continue
                            }
                            plan(dv, depth + 1, "required")
                        }
                    }
                }
            } finally {
                stack -= key
            }
        }

        plan(root, 0, "root")
        return Resolution(install, optional, blocked, errors, done)
    }
}
