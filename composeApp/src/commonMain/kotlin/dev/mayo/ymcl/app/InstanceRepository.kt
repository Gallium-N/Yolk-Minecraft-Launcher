package dev.mayo.ymcl.app

/** Result of a full scan, including per-entry parse failures. */
data class InstanceScan(
    val instances: List<InstanceSummary>,
    val failed: Int,
    val errors: List<String>,
)

/**
 * Boundary between the UI and the on-disk instance directory. The UI only ever
 * sees this interface (implemented in `platform`), never the filesystem.
 * A single unreadable instance must not fail the whole list: it is skipped and
 * reported in [InstanceScan.errors].
 */
interface InstanceRepository {
    /** Full scan; one bad entry never fails the whole list. */
    fun scan(): InstanceScan

    /** Convenience: just the successfully parsed instances. */
    fun list(): List<InstanceSummary> = scan().instances
}
