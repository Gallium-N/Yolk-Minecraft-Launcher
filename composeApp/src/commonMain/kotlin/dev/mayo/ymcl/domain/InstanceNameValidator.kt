package dev.mayo.ymcl.domain

sealed interface NameValidation {
    data object Valid : NameValidation
    data class Invalid(val message: String) : NameValidation
}

/**
 * Cross-OS instance name rules. The same rules must hold on Windows and Linux,
 * because an instance directory is expected to survive being copied between them.
 *
 * The rules are intentionally stricter than Linux: characters that Windows forbids
 * are rejected everywhere, device names are rejected case-insensitively, and the
 * final path is kept well below the legacy Windows 260-character limit.
 */
object InstanceNameValidator {
    const val MAX_NAME_LENGTH = 64
    const val MAX_PATH_LENGTH = 240

    private val ILLEGAL_CHARS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
    private val WINDOWS_RESERVED = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) {
            add("COM$i")
            add("LPT$i")
        }
    }

    fun validate(rawName: String, instancesRoot: String): NameValidation {
        if (rawName.isEmpty()) return invalid("实例名不能为空")
        if (rawName.isBlank()) return invalid("实例名不能只有空格")

        val badChar = rawName.firstOrNull { it in ILLEGAL_CHARS }
        if (badChar != null) {
            return invalid("实例名不能包含 < > : \" / \\ | ? *（发现 '$badChar'）")
        }
        if (rawName.any { it.isISOControl() }) {
            return invalid("实例名不能包含控制字符")
        }
        if (rawName.endsWith(' ')) return invalid("实例名不能以空格结尾（Windows 会静默去掉）")
        if (rawName.endsWith('.')) return invalid("实例名不能以点结尾（Windows 会静默去掉）")
        if (rawName == "." || rawName == "..") return invalid("实例名不能是 . 或 ..")

        // Windows treats "CON", "con.txt" and "CON." as the same device name.
        val deviceBase = rawName.substringBefore('.').trimEnd(' ')
        if (deviceBase.uppercase() in WINDOWS_RESERVED) {
            return invalid("“$deviceBase”是 Windows 保留设备名，换一个名字")
        }

        if (rawName.length > MAX_NAME_LENGTH) {
            return invalid("实例名过长（${rawName.length}/$MAX_NAME_LENGTH 字符），请缩短")
        }

        val root = instancesRoot.trimEnd('/', '\\')
        val fullPath = if (root.isEmpty()) rawName else "$root/$rawName"
        if (fullPath.length > MAX_PATH_LENGTH) {
            return invalid(
                "实例路径过长（${fullPath.length}/$MAX_PATH_LENGTH 字符），请缩短实例名或换一个更浅的实例目录",
            )
        }

        return NameValidation.Valid
    }

    private fun invalid(message: String): NameValidation = NameValidation.Invalid(message)
}
