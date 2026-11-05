package dev.mayo.ymcl.domain

import kotlinx.serialization.Serializable

/**
 * One Java installation known to the launcher.
 *
 * [path] is the absolute path to the `java` (or `java.exe`) binary. Persisted entries
 * with [source] = "installed" will later point below the app root; detected entries
 * always use their host path.
 */
@Serializable
data class JavaRuntime(
    val id: String,
    val vendor: String,
    val major: Int,
    val version: String,
    val arch: String,
    val os: String,
    val path: String,
    val source: String,
    val installedAt: String? = null,
) {
    val display: String get() = "$vendor $version ($arch)"
}
