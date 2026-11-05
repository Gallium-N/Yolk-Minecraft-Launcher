package dev.mayo.ymcl.platform

/**
 * Host OS as seen by the launcher. The app always keeps paths in the internal,
 * forward-slash form; conversion to the native separator only happens at the
 * file/process boundary.
 */
enum class OsFamily {
    WINDOWS,
    UNIX;

    val isWindows: Boolean get() = this == WINDOWS
    val separator: Char get() = if (isWindows) '\\' else '/'
}

/** Implemented with the host's os.name property. */
expect fun currentOsFamily(): OsFamily

/**
 * Pure path manipulation. No java.io here: same code runs on every target.
 * Rules:
 *  - internal representation always uses '/' and has no duplicate/trailing slashes;
 *  - '..' is resolved lexically and never escapes the root;
 *  - [toNative] is the only place that writes backslashes.
 */
object PathUtil {
    const val INTERNAL_SEPARATOR = '/'

    fun join(vararg parts: String): String =
        normalize(parts.filter { it.isNotEmpty() }.joinToString(INTERNAL_SEPARATOR.toString()))

    fun normalize(path: String): String {
        if (path.isEmpty()) return ""

        val absolute = path.startsWith('/') || path.startsWith('\\')
        val drive = if (path.length >= 2 && path[1] == ':' && path[0].isLetter()) path.substring(0, 2) else ""
        val body = if (drive.isNotEmpty()) path.substring(2) else path

        val resolved = ArrayDeque<String>()
        for (segment in body.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (resolved.isNotEmpty() && resolved.last() != "..") {
                        resolved.removeLast()
                    } else if (!absolute && drive.isEmpty()) {
                        resolved.addLast("..")
                    }
                }
                else -> resolved.addLast(segment)
            }
        }

        val joined = resolved.joinToString(INTERNAL_SEPARATOR.toString())
        return buildString {
            if (drive.isNotEmpty()) append(drive)
            if (absolute && drive.isEmpty()) append(INTERNAL_SEPARATOR)
            append(joined)
        }
    }

    fun toNative(path: String, os: OsFamily = currentOsFamily()): String =
        if (os.isWindows) {
            path.replace(INTERNAL_SEPARATOR, '\\')
        } else {
            path.replace('\\', INTERNAL_SEPARATOR)
        }

    fun fileName(path: String): String = path.replace('\\', '/').trimEnd('/').substringAfterLast('/')

    fun parent(path: String): String {
        val normalized = normalize(path)
        val index = normalized.lastIndexOf(INTERNAL_SEPARATOR)
        return when {
            index < 0 -> ""
            index == 0 -> "/"
            else -> normalized.substring(0, index)
        }
    }
}
