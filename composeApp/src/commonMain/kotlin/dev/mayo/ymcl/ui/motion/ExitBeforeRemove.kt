package dev.mayo.ymcl.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Slight shrink on the way out; scale only, so the measured layout never moves. */
const val EXIT_SHRINK_SCALE = 0.96f

/**
 * Scene 7: play fade + slight shrink first, and only invoke [onExitFinished]
 * (which removes the data) once the exit animation has actually finished.
 * Under reduceMotion the specs snap and the callback fires immediately.
 */
@Composable
fun ExitBeforeRemove(
    exiting: Boolean,
    reduceMotion: Boolean,
    onExitFinished: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var fired by remember { mutableStateOf(false) }

    val alpha by animateFloatAsState(
        targetValue = if (exiting) 0f else 1f,
        animationSpec = effects(reduceMotion),
        label = "exit-before-remove-alpha",
        finishedListener = {
            if (exiting && !fired) {
                fired = true
                onExitFinished()
            }
        },
    )
    val scale by animateFloatAsState(
        targetValue = if (exiting) EXIT_SHRINK_SCALE else 1f,
        animationSpec = spatial(reduceMotion),
        label = "exit-before-remove-scale",
    )

    // snap() has zero duration and may not report a finished animation; make the
    // reduced-motion path deterministic instead of relying on a listener.
    LaunchedEffect(exiting, reduceMotion) {
        if (exiting && reduceMotion && !fired) {
            fired = true
            onExitFinished()
        }
    }

    Box(
        modifier.graphicsLayer {
            this.alpha = alpha
            scaleX = scale
            scaleY = scale
        },
    ) {
        content()
    }
}
