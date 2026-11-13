package dev.mayo.ymcl.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.mayo.ymcl.ui.components.LocalReduceMotion

/** MD3 Expressive duration tokens. No business code may write a raw millis literal. */
object MotionDuration {
    const val Short1 = 50
    const val Short2 = 100
    const val Medium1 = 200
    const val Medium2 = 300
    const val Long1 = 450
}

val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.70f, 0.10f, 1.00f)
val EmphasizedAccelerate = CubicBezierEasing(0.30f, 0.00f, 0.80f, 0.15f)

/** Non-duration motion values, kept out of business code as well. */
const val CARD_PRESS_SCALE = 0.98f
val CARD_HOVER_LIFT = 2.dp

/** Position / scale / shape morph. Snaps (not slows down) under reduceMotion. */
fun <T> spatial(reduceMotion: Boolean): FiniteAnimationSpec<T> =
    if (reduceMotion) snap()
    else spring(dampingRatio = 0.80f, stiffness = Spring.StiffnessMediumLow)

/** Alpha / colour. Snaps (not slows down) under reduceMotion. */
fun <T> effects(reduceMotion: Boolean): FiniteAnimationSpec<T> =
    if (reduceMotion) snap()
    else tween(durationMillis = MotionDuration.Medium1, easing = EmphasizedDecelerate)

/** QA-only: `-Dymcl.reduceMotion=true` forces snaps without touching the setting. */
val motionQaOverride: Boolean
    get() = platformReduceMotionOverride()

internal expect fun platformReduceMotionOverride(): Boolean

/** Single source of truth for every scene: setting switch OR QA system property. */
@Composable
fun rememberReduceMotion(): Boolean = LocalReduceMotion.current || motionQaOverride
