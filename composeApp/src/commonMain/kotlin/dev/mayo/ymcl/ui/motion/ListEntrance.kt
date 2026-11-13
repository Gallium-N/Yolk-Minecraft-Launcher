package dev.mayo.ymcl.ui.motion

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Only the first 8 items are staggered; later items share the last slot. */
const val STAGGER_ITEM_LIMIT = 8
const val STAGGER_STEP_MS = 20
val LIST_ENTRANCE_OFFSET = 12.dp

/** Capped stagger slot, pure for unit tests. */
fun staggerSlot(index: Int): Int = index.coerceAtLeast(0).coerceAtMost(STAGGER_ITEM_LIMIT - 1)

fun staggerDelayMs(index: Int): Int = staggerSlot(index) * STAGGER_STEP_MS

/**
 * Fade + rise from 12dp for a list item. Call inside `key(item.id)` so a refresh
 * only plays the entrance for genuinely new keys, never the whole table again.
 */
@Composable
fun Modifier.listItemEntrance(index: Int, reduceMotion: Boolean): Modifier {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val waitMs = if (reduceMotion) 0 else staggerDelayMs(index)
        if (waitMs > 0) delay(waitMs.toLong())
        shown = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = effects(reduceMotion),
        label = "list-entrance-alpha",
    )
    val y by animateDpAsState(
        targetValue = if (shown) 0.dp else LIST_ENTRANCE_OFFSET,
        animationSpec = spatial(reduceMotion),
        label = "list-entrance-y",
    )
    return this
        .graphicsLayer { this.alpha = alpha }
        .offset(y = y)
}
