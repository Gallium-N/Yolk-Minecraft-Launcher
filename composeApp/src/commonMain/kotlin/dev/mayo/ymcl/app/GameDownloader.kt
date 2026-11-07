package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.DownloadProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CandidateUrl(val url: String, val sourceId: String)

data class DownloadSpec(
    val relativePath: String,
    val candidates: List<CandidateUrl>,
    val expectedSha1: String? = null,
    val expectedSize: Long? = null,
    /** When set, the downloaded jar is unpacked into this directory after verification. */
    val unpackNativesTo: String? = null,
)

data class DownloadResponseMeta(
    val statusCode: Int,
    val finalUrl: String,
    val bmclapiHash: String?,
    val bytesWritten: Long,
    /** False when a Range was requested but the server replied 200 (Range ignored). */
    val rangeHonored: Boolean = true,
)

data class DownloadOutcome(
    val relativePath: String,
    val sourceId: String,
    val elapsedMs: Long,
    val bytes: Long,
    val skipped: Boolean,
    val redownloaded: Boolean,
    val finalUrl: String,
)

class DownloadCancelledException : Exception("下载已取消")

class DownloadFailedException(message: String) : Exception(message)

/** Streams one GET into [write]; the callback may throw [DownloadCancelledException]. */
fun interface DownloadTransport {
    fun get(url: String, rangeStart: Long, write: (ByteArray) -> Unit): DownloadResponseMeta
}

/** All paths are relative to the isolated game directory. */
interface DownloadFileSystem {
    fun exists(relativePath: String): Boolean
    fun size(relativePath: String): Long
    fun sha1Of(relativePath: String): String
    fun readAll(relativePath: String): ByteArray
    fun writeNew(relativePath: String, data: ByteArray)
    fun append(relativePath: String, data: ByteArray)
    fun delete(relativePath: String)
    fun move(from: String, to: String)
    fun unpackNatives(jarBytes: ByteArray, destDir: String)
}

private val SHA1_REGEX = Regex("^[0-9a-fA-F]{40}$")

/**
 * Downloads one file: ordered candidates, .part resume, sha1/size verification,
 * exactly one redownload on mismatch, and immediate cancellation with .part cleanup.
 * Game-file downloader for P3 step 3; runs concurrently (one call per file).
 */
class GameDownloader(
    private val transport: DownloadTransport,
    private val fs: DownloadFileSystem,
    private val log: (String) -> Unit = ::println,
) {
    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> get() = _progress

    private val progressLock = Any()

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun reset(totalFiles: Int, totalBytes: Long) {
        synchronized(progressLock) {
            _progress.value = DownloadProgress(totalFiles = totalFiles, totalBytes = totalBytes)
        }
    }

    private fun bump(bytes: Long, file: String, failed: Boolean = false) {
        synchronized(progressLock) {
            val p = _progress.value
            _progress.value = p.copy(
                completedFiles = p.completedFiles + if (failed) 0 else 1,
                completedBytes = p.completedBytes + bytes,
                currentFile = file,
                failedFiles = p.failedFiles + if (failed) 1 else 0,
            )
        }
    }

    private fun checkCancelled(part: String) {
        if (cancelled) {
            fs.delete(part)
            throw DownloadCancelledException()
        }
    }

    fun download(spec: DownloadSpec): DownloadOutcome {
        val target = spec.relativePath
        val part = "$target.part"
        val start = System.currentTimeMillis()
        checkCancelled(part)

        // A complete local file that already matches the manifest never needs a request.
        if ((spec.expectedSha1 != null || spec.expectedSize != null) &&
            fs.exists(target) && verify(fs.sha1Of(target), fs.size(target), spec)
        ) {
            val bytes = fs.size(target)
            bump(bytes, target)
            log("$target <- cached 0ms ${bytes}B")
            return DownloadOutcome(target, "cached", 0L, bytes, true, false, target)
        }

        val errors = mutableListOf<String>()
        var redownloaded = false
        for (candidate in spec.candidates) {
            for (attempt in 1..2) {
                checkCancelled(part)
                val rangeStart = if (fs.exists(part) && fs.size(part) > 0) fs.size(part) else 0L
                if (rangeStart == 0L) fs.delete(part)

                val meta = try {
                    transport.get(candidate.url, rangeStart) { chunk ->
                        checkCancelled(part)
                        fs.append(part, chunk)
                    }
                } catch (e: DownloadCancelledException) {
                    throw e
                } catch (e: Exception) {
                    errors += "${candidate.url}#$attempt -> ${e.javaClass.simpleName}: ${e.message}"
                    fs.delete(part)
                    break // switch to the next candidate
                }

                if (meta.statusCode !in 200..299) {
                    errors += "${candidate.url}#$attempt -> HTTP ${meta.statusCode}"
                    fs.delete(part)
                    break
                }

                // Server ignored Range: restart this candidate from zero.
                if (!meta.rangeHonored) {
                    fs.delete(part)
                    continue
                }

                // x-bmclapi-hash: only a 40-char hex digest, only agrees with the
                // manifest sha1 when one exists, and only to skip an existing local
                // file. It is NEVER used to verify a freshly downloaded file.
                val hash = meta.bmclapiHash
                val hashUsable = hash != null && SHA1_REGEX.matches(hash) &&
                    (spec.expectedSha1 == null || hash.equals(spec.expectedSha1, ignoreCase = true))
                if (hashUsable && fs.exists(target) &&
                    fs.sha1Of(target).equals(hash, ignoreCase = true)
                ) {
                    fs.delete(part)
                    val bytes = fs.size(target)
                    val elapsed = System.currentTimeMillis() - start
                    bump(bytes, target)
                    log("$target <- ${candidate.sourceId} ${elapsed}ms ${bytes}B (bmclapi-hash skip)")
                    return DownloadOutcome(target, candidate.sourceId, elapsed, bytes, true, redownloaded, meta.finalUrl)
                }

                val actualSha1 = fs.sha1Of(part)
                val actualSize = fs.size(part)
                if (!verify(actualSha1, actualSize, spec)) {
                    errors += "$target#$attempt sha1 期望=${spec.expectedSha1} 实际=$actualSha1 " +
                        "size 期望=${spec.expectedSize} 实际=$actualSize"
                    fs.delete(part)
                    fs.delete(target)
                    if (attempt == 1) {
                        redownloaded = true
                        continue // exactly one redownload
                    }
                    throw DownloadFailedException(
                        "$target：重下一次后校验仍失败（期望 sha1=${spec.expectedSha1} / 实际=$actualSha1，" +
                            "期望 size=${spec.expectedSize} / 实际=$actualSize）；" +
                            "候选错误：" + errors.joinToString("; "),
                    )
                }

                fs.move(part, target)
                if (spec.unpackNativesTo != null) {
                    fs.unpackNatives(fs.readAll(target), spec.unpackNativesTo)
                }
                val elapsed = System.currentTimeMillis() - start
                log("$target <- ${candidate.sourceId} ${elapsed}ms ${actualSize}B")
                if (meta.finalUrl != candidate.url) {
                    log("  $target 302 -> ${meta.finalUrl}")
                }
                bump(actualSize, target)
                return DownloadOutcome(target, candidate.sourceId, elapsed, actualSize, false, redownloaded, meta.finalUrl)
            }
        }
        bump(0, target, failed = true)
        throw DownloadFailedException("$target：所有候选失败；" + errors.joinToString("; "))
    }

    /** Called by the orchestrator after cancellation to remove every leftover .part. */
    fun cleanupParts(paths: List<String>) {
        paths.forEach { fs.delete("$it.part") }
    }

    private fun verify(sha1: String, size: Long, spec: DownloadSpec): Boolean {
        if (spec.expectedSha1 != null && !sha1.equals(spec.expectedSha1, ignoreCase = true)) return false
        if (spec.expectedSize != null && size != spec.expectedSize) return false
        return true
    }
}
