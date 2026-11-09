package dev.mayo.ymcl.app

/**
 * Boundary between the UI and the host filesystem/network. Parsing the zip,
 * installing the base game + loader, downloading files[] and writing overrides
 * all live in a desktop `actual` implementation; the Compose layer only sees
 * this interface (and the pure [ModpackPlan]/[ImportSummary] models).
 */
interface ModpackBackend {
    /** Parses the pack at [path] without writing anything. Throws [ModpackParseException]. */
    fun parse(path: String): ModpackPlan

    /** Full import; progress is reported through [onProgress]. */
    fun install(plan: ModpackPlan, onProgress: (ImportProgress) -> Unit): ImportSummary

    /** Requests cancellation of the running import. */
    fun cancel()

    /** Existing version ids, used by InstanceId.allocate for duplicate names. */
    fun existingIds(): Set<String>
}
