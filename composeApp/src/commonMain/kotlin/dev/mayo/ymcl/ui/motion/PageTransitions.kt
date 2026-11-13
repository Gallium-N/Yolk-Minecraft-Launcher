package dev.mayo.ymcl.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared axis X slide distance. */
val SHARED_AXIS_SLIDE: Dp = 30.dp
/** Stage-title vertical slide distance. */
val STAGE_TITLE_SLIDE: Dp = 12.dp

/**
 * +1 = forward (target page to the right): old page exits left, new page enters
 * from the right. -1 = backward, exactly mirrored. Same page resolves to +1.
 * Pure so it can be unit-tested without a Compose runtime.
 */
fun sharedAxisDirection(fromIndex: Int, toIndex: Int): Int =
    if (toIndex >= fromIndex) 1 else -1

@Composable
private fun rememberSlidePx(distance: Dp): Int {
    val density = LocalDensity.current
    return remember(density, distance) { with(density) { distance.roundToPx() } }
}

@Composable
fun rememberSharedAxisSlidePx(): Int = rememberSlidePx(SHARED_AXIS_SLIDE)

@Composable
fun rememberStageTitleSlidePx(): Int = rememberSlidePx(STAGE_TITLE_SLIDE)

/**
 * Shared axis X page transition; both pages are visible during the crossfade.
 * Plain function: AnimatedContent's transitionSpec is not a composable lambda.
 */
fun sharedAxisTransform(reduceMotion: Boolean, direction: Int, slidePx: Int): ContentTransform {
    if (reduceMotion) {
        return ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
    }
    return ContentTransform(
        targetContentEnter = slideInHorizontally(spatial(reduceMotion)) { direction * slidePx } +
            fadeIn(effects(reduceMotion)),
        initialContentExit = slideOutHorizontally(spatial(reduceMotion)) { -direction * slidePx } +
            fadeOut(effects(reduceMotion)),
        sizeTransform = null,
    )
}

/** Stage title: new title rises in, old title rises out. */
fun stageTitleTransform(reduceMotion: Boolean, slidePx: Int): ContentTransform {
    if (reduceMotion) {
        return ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
    }
    return ContentTransform(
        targetContentEnter = slideInVertically(spatial(reduceMotion)) { slidePx } +
            fadeIn(effects(reduceMotion)),
        initialContentExit = slideOutVertically(spatial(reduceMotion)) { -slidePx } +
            fadeOut(effects(reduceMotion)),
        sizeTransform = null,
    )
}

/** Scene 6: empty states / error banners expand in and shrink out. */
fun motionEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) EnterTransition.None
    else fadeIn(effects(reduceMotion)) + expandVertically(spatial(reduceMotion))

fun motionExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) ExitTransition.None
    else fadeOut(effects(reduceMotion)) + shrinkVertically(spatial(reduceMotion))

/** Shared wrapper so every banner/empty state uses the same enter/exit specs. */
@Composable
fun MotionVisibility(
    visible: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = motionEnter(reduceMotion),
        exit = motionExit(reduceMotion),
    ) {
        content()
    }
}
