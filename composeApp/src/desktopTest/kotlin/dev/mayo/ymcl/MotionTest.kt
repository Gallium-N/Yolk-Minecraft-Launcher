package dev.mayo.ymcl

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import dev.mayo.ymcl.ui.motion.MotionDuration
import dev.mayo.ymcl.ui.motion.STAGGER_ITEM_LIMIT
import dev.mayo.ymcl.ui.motion.STAGGER_STEP_MS
import dev.mayo.ymcl.ui.motion.effects
import dev.mayo.ymcl.ui.motion.motionQaOverride
import dev.mayo.ymcl.ui.motion.sharedAxisDirection
import dev.mayo.ymcl.ui.motion.spatial
import dev.mayo.ymcl.ui.motion.staggerDelayMs
import dev.mayo.ymcl.ui.motion.staggerSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline tests for the MD3 Expressive motion tokens (no Compose runtime). */
class MotionTest {

    @Test
    fun `spatial and effects snap under reduceMotion`() {
        assertTrue(spatial<Float>(true) is SnapSpec)
        assertTrue(effects<Float>(true) is SnapSpec)
        // Not "slower": the non-reduced path must be a real spring / tween.
        assertTrue(spatial<Float>(false) is SpringSpec)
        assertTrue(effects<Float>(false) is TweenSpec)
        assertEquals(MotionDuration.Medium1, (effects<Float>(false) as TweenSpec).durationMillis)
    }

    @Test
    fun `stagger is capped at the first 8 items`() {
        assertEquals(0, staggerDelayMs(0))
        assertEquals(STAGGER_STEP_MS * 3, staggerDelayMs(3))
        assertEquals(STAGGER_ITEM_LIMIT - 1, staggerSlot(STAGGER_ITEM_LIMIT - 1))
        assertEquals(STAGGER_ITEM_LIMIT - 1, staggerSlot(STAGGER_ITEM_LIMIT))
        assertEquals(STAGGER_ITEM_LIMIT - 1, staggerSlot(1000))
        assertEquals((STAGGER_ITEM_LIMIT - 1) * STAGGER_STEP_MS, staggerDelayMs(1000))
    }

    @Test
    fun `shared axis direction is forward, backward and symmetric`() {
        assertEquals(1, sharedAxisDirection(0, 1))
        assertEquals(1, sharedAxisDirection(0, 4))
        assertEquals(-1, sharedAxisDirection(4, 0))
        assertEquals(-1, sharedAxisDirection(3, 2))
        assertEquals(1, sharedAxisDirection(2, 2))
        // Forward and backward transitions must be exact mirrors.
        for (from in 0..4) {
            for (to in 0..4) {
                if (from == to) continue
                assertEquals(
                    -sharedAxisDirection(from, to),
                    sharedAxisDirection(to, from),
                )
            }
        }
    }

    @Test
    fun `QA system property forces reduceMotion without the setting`() {
        val previous = System.getProperty("ymcl.reduceMotion")
        try {
            System.clearProperty("ymcl.reduceMotion")
            assertFalse(motionQaOverride)
            System.setProperty("ymcl.reduceMotion", "true")
            assertTrue(motionQaOverride)
            System.setProperty("ymcl.reduceMotion", "false")
            assertFalse(motionQaOverride)
        } finally {
            if (previous == null) {
                System.clearProperty("ymcl.reduceMotion")
            } else {
                System.setProperty("ymcl.reduceMotion", previous)
            }
        }
    }
}
