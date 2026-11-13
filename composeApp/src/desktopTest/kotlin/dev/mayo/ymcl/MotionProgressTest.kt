package dev.mayo.ymcl

import dev.mayo.ymcl.ui.motion.DownloadRateMeter
import dev.mayo.ymcl.ui.motion.formatEta
import dev.mayo.ymcl.ui.motion.formatSpeed
import dev.mayo.ymcl.ui.motion.progressFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline tests for the byte-driven progress bar + live speed / ETA. */
class MotionProgressTest {

    @Test
    fun `byte fraction wins over the coarse stage fraction`() {
        assertEquals(0.5f, progressFraction(done = 5, total = 7, bytes = 50L, totalBytes = 100L), 0.0001f)
        // No active download: fall back to the 7-stage fraction.
        assertEquals(5f / 7f, progressFraction(done = 5, total = 7, bytes = 0L, totalBytes = 0L), 0.0001f)
    }

    @Test
    fun `byte fraction is clamped so a reset never runs the bar backwards`() {
        assertEquals(1f, progressFraction(done = 1, total = 7, bytes = 500L, totalBytes = 100L), 0.0001f)
        assertEquals(0f, progressFraction(done = 0, total = 7, bytes = -5L, totalBytes = 100L), 0.0001f)
        assertEquals(0f, progressFraction(done = 0, total = 0, bytes = 0L, totalBytes = 0L), 0.0001f)
        // Finishing the last stage must land on 100% even if bytes lag totals.
        assertEquals(1f, progressFraction(done = 7, total = 7, bytes = 10L, totalBytes = 100L), 0.0001f)
    }

    @Test
    fun `speed and ETA formatting`() {
        assertEquals("—", formatSpeed(0.0))
        assertEquals("512 B/s", formatSpeed(512.0))
        assertEquals("2.0 KB/s", formatSpeed(2048.0))
        assertEquals("3.0 MB/s", formatSpeed(3.0 * 1024 * 1024))
        assertEquals("即将完成", formatEta(0L, 1024.0))
        assertEquals("计算中…", formatEta(1024L, 0.0))
        assertEquals("10 秒", formatEta(10240L, 1024.0))
        assertEquals("1 分 5 秒", formatEta(65L * 1024, 1024.0))
    }

    @Test
    fun `rate meter estimates bytes per second and restarts on batch reset`() {
        val meter = DownloadRateMeter()
        meter.update(0L, 1000L)
        assertEquals(0.0, meter.bytesPerSec, 0.0001)
        meter.update(1024L, 2000L)
        assertEquals(1024.0, meter.bytesPerSec, 0.0001)
        meter.update(2048L, 3000L) // EMA keeps the steady rate
        assertEquals(1024.0, meter.bytesPerSec, 0.0001)
        // A new downloader batch calls reset(): bytes drop to 0, no bogus speed.
        meter.update(0L, 4000L)
        assertTrue(meter.bytesPerSec >= 0.0)
        meter.update(512L, 5000L)
        assertTrue(meter.bytesPerSec > 0.0)
    }
}
