package dev.mayo.ymcl.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Rolling download-rate estimate from successive byte samples. Pure state, no
 * per-frame allocation: created once with [rememberDownloadRateMeter].
 */
class DownloadRateMeter {
    private var lastBytes = 0L
    private var lastAt = 0L
    var bytesPerSec by mutableStateOf(0.0)
        private set

    /** [nowMillis] is host time (ms); a batch reset restarts the sample window. */
    fun update(bytes: Long, nowMillis: Long) {
        val dt = nowMillis - lastAt
        when {
            lastAt == 0L || dt <= 0L || bytes < lastBytes -> {
                lastBytes = bytes
                lastAt = nowMillis
            }
            else -> {
                val instant = (bytes - lastBytes) * 1000.0 / dt.toDouble()
                bytesPerSec = if (bytesPerSec <= 0.0) instant else bytesPerSec * 0.6 + instant * 0.4
                lastBytes = bytes
                lastAt = nowMillis
            }
        }
    }
}

@Composable
fun rememberDownloadRateMeter(): DownloadRateMeter = remember { DownloadRateMeter() }

/**
 * Real byte fraction when a download is active, otherwise the coarse stage
 * fraction. Clamped so a reset() never makes the bar run backwards.
 */
fun progressFraction(done: Int, total: Int, bytes: Long, totalBytes: Long): Float = when {
    total > 0 && done >= total -> 1f
    totalBytes > 0L -> (bytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    total > 0 -> (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    else -> 0f
}

fun formatSpeed(bytesPerSec: Double): String = when {
    bytesPerSec <= 0.0 -> "—"
    bytesPerSec >= 1_048_576.0 -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
    bytesPerSec >= 1024.0 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
    else -> "${bytesPerSec.toLong()} B/s"
}

fun formatEta(remainingBytes: Long, bytesPerSec: Double): String {
    if (remainingBytes <= 0L) return "即将完成"
    if (bytesPerSec <= 0.0) return "计算中…"
    val secs = (remainingBytes / bytesPerSec).toLong().coerceAtLeast(1)
    return when {
        secs < 60 -> "$secs 秒"
        secs < 3600 -> "${secs / 60} 分 ${secs % 60} 秒"
        else -> "${secs / 3600} 小时 ${(secs % 3600) / 60} 分"
    }
}
