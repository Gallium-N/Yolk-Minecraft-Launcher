package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.DownloadProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CandidateUrl(val url: String, val sourceId: String)

/**
 * Where a spec's [relativePath] lives. SHARED files are resolved from the game
 * root (`assets/`, `libraries/`) and are deduplicated across every instance;
 * INSTANCE files live under `versions/<instanceId>/`. The installer builds the
 * full relative path, so the downloader's validation logic needs no change.
 */
enum class DownloadScope { SHARED, INSTANCE }

data class DownloadSpec(
    val relativePath: String,
    val candidates: List<CandidateUrl>,
    val expectedSha1: String? = null,
    val expectedSize: Long? = null,
    /** When set, the downloaded jar is unpacked into this directory after verification. */
    val unpackNativesTo: String? = null,
    /** Descriptive target scope (shared root vs instance-private). */
    val scope: DownloadScope = DownloadScope.SHARED,
)

/** The whole body is buffered in memory by the transport, then written once. */
data class DownloadPayload(
    val statusCode: Int,
    val finalUrl: String,
    val bmclapiHash: String?,
    val bytesWritten: Long,
    val bytes: ByteArray,
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

/** No new bytes for the stall window; the transport closed the stream. */
class DownloadStallException(val host: String, val stallMs: Long) :
    Exception("stall: host=$host stallMs=$stallMs") {
    val label: String get() = "STALL@${stallMs / 1000}s"
}

/** Total per-file deadline exceeded; the transport cancelled the future. */
class DownloadTotalTimeoutException(val host: String, val totalMs: Long) :
    Exception("total timeout: host=$host totalMs=$totalMs") {
    val label: String get() = "TIMEOUT@${totalMs / 1000}s"
}

class DownloadFailedException(message: String) : Exception(message)

/** Buffers one GET fully in memory; throws on stall / total deadline. */
fun interface DownloadTransport {
    fun get(url: String, rangeStart: Long): DownloadPayload
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

    /** Child directory names of [relativePath]; empty when the FS cannot list. */
    fun listDirs(relativePath: String): List<String> = emptyList()

    /**
     * Materializes a verified shared file into an instance-owned path. The
     * platform actual hard-links (with copy fallback on EXDEV); this default is a
     * plain copy so in-memory fakes stay usable without extra wiring.
     */
    fun linkOrCopy(from: String, to: String) {
        writeNew(to, readAll(from))
    }
}

private val SHA1_REGEX = Regex("^[0-9a-fA-F]{40}$")

/**
 * Downloads one file: health-ranked candidates, .part resume, sha1/size verification,
 * exactly one redownload on mismatch, and immediate cancellation with .part cleanup.
 */
class GameDownloader(
    private val transport: DownloadTransport,
    private val fs: DownloadFileSystem,
    private val log: (String) -> Unit = ::println,
) {
    private val health = HostHealth(log)
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

    private fun partMeta(part: String) = "$part.meta"

    /** Deletes the .part AND its resume metadata together. */
    private fun dropPart(part: String) {
        fs.delete(part)
        fs.delete(partMeta(part))
    }

    /** Persists the resume stamp: current byte count + expected sha1 prefix. */
    private fun writePartMeta(part: String, spec: DownloadSpec) {
        val prefix = spec.expectedSha1?.take(12) ?: "none"
        fs.writeNew(partMeta(part), "size=${fs.size(part)}\nsha1Prefix=$prefix\n".toByteArray(Charsets.UTF_8))
    }

    private fun checkCancelled(part: String) {
        if (cancelled) {
            dropPart(part)
            throw DownloadCancelledException()
        }
    }

    private fun logSwitch(target: String, ordered: List<CandidateUrl>, index: Int) {
        val next = ordered.getOrNull(index + 1)
        if (next != null) {
            log("  $target -> ${next.sourceId} (${hostOf(next.url)})")
        } else {
            log("  $target -> no more candidates")
        }
    }

    private fun failCandidate(
        target: String,
        part: String,
        label: String,
        host: String,
        reason: String,
        errors: MutableList<String>,
        ordered: List<CandidateUrl>,
        index: Int,
    ) {
        log("$target <- $host$label")
        // A 302 may land on a fresh CDN host every file; also charge the failure
        // to the candidate's source so source-level degradation can accumulate.
        health.recordFailure(host, ordered.getOrNull(index)?.sourceId)
        errors += reason
        dropPart(part)
        logSwitch(target, ordered, index)
    }

    fun download(spec: DownloadSpec): DownloadOutcome {
        val target = spec.relativePath
        val part = "$target.part"
        val start = System.currentTimeMillis()
        checkCancelled(part)

        if ((spec.expectedSha1 != null || spec.expectedSize != null) &&
            fs.exists(target) && verify(fs.sha1Of(target), fs.size(target), spec)
        ) {
            val bytes = fs.size(target)
            bump(bytes, target)
            // Proof that the local sha1 skip really fires on a second install.
            log("$target <- skip(local, bmclapi-hash=${spec.expectedSha1 ?: fs.sha1Of(target)}) 0ms ${bytes}B")
            return DownloadOutcome(target, "cached", 0L, bytes, true, false, target)
        }

        // Cross-restart resume: a .part is only reused when its sha1 prefix
        // still matches this spec; otherwise discard and start over.
        val expectedPrefix = spec.expectedSha1?.take(12)
        if (fs.exists(part) && fs.size(part) > 0L) {
            val meta = runCatching { String(fs.readAll(partMeta(part)), Charsets.UTF_8) }.getOrNull()
            val stampedPrefix = meta?.lineSequence()
                ?.firstOrNull { it.startsWith("sha1Prefix=") }?.substringAfter('=')
            val usable = meta == null || stampedPrefix == null || expectedPrefix == null ||
                stampedPrefix == expectedPrefix
            if (usable) {
                log("$target resume from ${fs.size(part)} bytes")
            } else {
                log("$target .part sha1 prefix mismatch ($stampedPrefix != $expectedPrefix) -> discard")
                dropPart(part)
            }
        }

        val ordered = health.rank(spec.candidates)
        val errors = mutableListOf<String>()
        var redownloaded = false
        for ((candidateIndex, candidate) in ordered.withIndex()) {
            val host = hostOf(candidate.url)
            for (attempt in 1..2) {
                checkCancelled(part)
                val rangeStart = if (fs.exists(part) && fs.size(part) > 0) fs.size(part) else 0L
                if (rangeStart == 0L) dropPart(part)

                val payload = try {
                    transport.get(candidate.url, rangeStart)
                } catch (e: DownloadStallException) {
                    failCandidate(target, part, e.label, e.host,
                        "${candidate.url}#$attempt -> ${e.label}", errors, ordered, candidateIndex)
                    break
                } catch (e: DownloadTotalTimeoutException) {
                    failCandidate(target, part, e.label, e.host,
                        "${candidate.url}#$attempt -> ${e.label}", errors, ordered, candidateIndex)
                    break
                } catch (e: DownloadCancelledException) {
                    throw e
                } catch (e: Exception) {
                    failCandidate(target, part, "FAILED", host,
                        "${candidate.url}#$attempt -> ${e.javaClass.simpleName}: ${e.message}",
                        errors, ordered, candidateIndex)
                    break
                }

                if (payload.statusCode !in 200..299) {
                    failCandidate(target, part, "HTTP${payload.statusCode}", host,
                        "${candidate.url}#$attempt -> HTTP ${payload.statusCode}", errors, ordered, candidateIndex)
                    break
                }
                if (!payload.rangeHonored) {
                    dropPart(part)
                    continue
                }

                // x-bmclapi-hash: 40-char hex only, must agree with the manifest sha1
                // when one exists, and only to skip an already-present local file.
                val hash = payload.bmclapiHash
                val hashUsable = hash != null && SHA1_REGEX.matches(hash) &&
                    (spec.expectedSha1 == null || hash.equals(spec.expectedSha1, ignoreCase = true))
                if (hashUsable && fs.exists(target) &&
                    fs.sha1Of(target).equals(hash, ignoreCase = true)
                ) {
                    dropPart(part)
                    val bytes = fs.size(target)
                    val elapsed = System.currentTimeMillis() - start
                    health.recordSuccess(host, candidate.sourceId)
                    bump(bytes, target)
                    log("$target <- skip(local, bmclapi-hash=$hash) ${elapsed}ms ${bytes}B")
                    return DownloadOutcome(target, candidate.sourceId, elapsed, bytes, true, redownloaded, payload.finalUrl)
                }

                checkCancelled(part)
                fs.append(part, payload.bytes)
                writePartMeta(part, spec)
                val actualSha1 = fs.sha1Of(part)
                val actualSize = fs.size(part)
                if (!verify(actualSha1, actualSize, spec)) {
                    errors += "$target#$attempt sha1 期望=${spec.expectedSha1} 实际=$actualSha1 " +
                        "size 期望=${spec.expectedSize} 实际=$actualSize"
                    dropPart(part)
                    fs.delete(target)
                    if (attempt == 1) {
                        redownloaded = true
                        continue
                    }
                    throw DownloadFailedException(
                        "$target：重下一次后校验仍失败（期望 sha1=${spec.expectedSha1} / 实际=$actualSha1，" +
                            "期望 size=${spec.expectedSize} / 实际=$actualSize）；" +
                            "候选错误：" + errors.joinToString("; "),
                    )
                }

                fs.move(part, target)
                fs.delete(partMeta(part))
                if (spec.unpackNativesTo != null) {
                    fs.unpackNatives(fs.readAll(target), spec.unpackNativesTo)
                }
                val elapsed = System.currentTimeMillis() - start
                health.recordSuccess(host, candidate.sourceId)
                health.observe(host, hostOf(payload.finalUrl))
                log("$target <- ${candidate.sourceId} ${elapsed}ms ${actualSize}B")
                if (payload.finalUrl != candidate.url) {
                    log("  $target 302 -> ${payload.finalUrl}")
                }
                bump(actualSize, target)
                return DownloadOutcome(target, candidate.sourceId, elapsed, actualSize, false, redownloaded, payload.finalUrl)
            }
        }
        bump(0, target, failed = true)
        throw DownloadFailedException("$target：所有候选失败；" + errors.joinToString("; "))
    }

    fun cleanupParts(paths: List<String>) {
        paths.forEach {
            fs.delete("$it.part")
            fs.delete("$it.part.meta")
        }
    }

    private fun verify(sha1: String, size: Long, spec: DownloadSpec): Boolean {
        if (spec.expectedSha1 != null && !sha1.equals(spec.expectedSha1, ignoreCase = true)) return false
        if (spec.expectedSize != null && size != spec.expectedSize) return false
        return true
    }
}
