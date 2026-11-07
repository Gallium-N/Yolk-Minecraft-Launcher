package dev.mayo.ymcl.domain

/**
 * Pure, host-independent instance-id rules. There is deliberately no java.io
 * here: an instance directory must survive being copied between OSes, so the
 * slug is generated identically everywhere.
 *
 * `instanceId` is the version directory name. It is generated once from the
 * displayName and then fixed — renaming the displayName never renames the dir.
 */
object InstanceId {
    const val MAX_PATH_LENGTH = 240

    private val WINDOWS_RESERVED = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) {
            add("COM$i")
            add("LPT$i")
        }
    }
    private val ILLEGAL_CHARS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')

    /**
     * Keeps ASCII letters/digits/`-`/`_`; folds every other character (including
     * CJK) to a single `-`, then trims leading/trailing separators. A pure
     * Chinese name therefore becomes an empty slug and falls back to the
     * `mc-<mcVersion>-<n>` form in [allocate].
     */
    fun slugify(displayName: String): String {
        val folded = buildString {
            var lastSeparator = false
            for (ch in displayName) {
                val keep = ch in 'a'..'z' || ch in 'A'..'Z' ||
                    ch in '0'..'9' || ch == '-' || ch == '_'
                if (keep) {
                    append(ch)
                    lastSeparator = false
                } else if (!lastSeparator && isNotEmpty()) {
                    append('-')
                    lastSeparator = true
                }
            }
        }
        return folded.trim('-', '_')
    }

    /** Validates an id (generated or user-supplied) against the cross-OS rules. */
    fun validate(id: String): NameValidation {
        if (id.isEmpty()) return invalid("instanceId 不能为空")
        if (id.isBlank()) return invalid("instanceId 不能只有空格")
        val badChar = id.firstOrNull { it in ILLEGAL_CHARS }
        if (badChar != null) {
            return invalid("instanceId 不能包含 < > : \" / \\ | ? *（发现 '$badChar'）")
        }
        if (id.any { it.isISOControl() }) return invalid("instanceId 不能包含控制字符")
        if (id.endsWith(' ')) return invalid("instanceId 不能以空格结尾（Windows 会静默去掉）")
        if (id.endsWith('.')) return invalid("instanceId 不能以点结尾（Windows 会静默去掉）")
        if (id == "." || id == "..") return invalid("instanceId 不能是 . 或 ..")
        val deviceBase = id.substringBefore('.').trimEnd(' ')
        if (deviceBase.uppercase() in WINDOWS_RESERVED) {
            return invalid("“$deviceBase”是 Windows 保留设备名，换一个名字")
        }
        return NameValidation.Valid
    }

    /**
     * Picks a non-conflicting id and never overwrites an existing instance:
     *  - a non-empty slug is used as-is, or suffixed `-2`, `-3`, ... on conflict;
     *  - an empty slug falls back to `mc-<mcVersion>-<n>` with n starting at 1.
     */
    fun allocate(displayName: String, mcVersion: String, existing: Set<String>): String {
        val slug = slugify(displayName)
        if (slug.isNotEmpty()) {
            if (slug !in existing && validate(slug) is NameValidation.Valid) return slug
            var n = 2
            while (true) {
                val candidate = "$slug-$n"
                if (candidate !in existing && validate(candidate) is NameValidation.Valid) return candidate
                n++
            }
        }
        val base = "mc-$mcVersion"
        var n = 1
        while (true) {
            val candidate = "$base-$n"
            if (candidate !in existing && validate(candidate) is NameValidation.Valid) return candidate
            n++
        }
    }

    /** Total `.../versions/<id>` path must stay below the legacy Windows limit. */
    fun pathTooLong(gameRoot: String, id: String): Boolean =
        (gameRoot.trimEnd('/', '\\') + "/versions/" + id).length > MAX_PATH_LENGTH

    private fun invalid(message: String): NameValidation = NameValidation.Invalid(message)
}
