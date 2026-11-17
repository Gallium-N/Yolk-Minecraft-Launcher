package dev.mayo.ymcl.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mayo.ymcl.domain.InstanceStatus
import dev.mayo.ymcl.ui.motion.CARD_HOVER_LIFT
import dev.mayo.ymcl.ui.motion.CARD_PRESS_SCALE
import dev.mayo.ymcl.ui.motion.effects
import dev.mayo.ymcl.ui.motion.rememberReduceMotion
import dev.mayo.ymcl.ui.motion.spatial

/** Set from Settings → 减少动效; components snap instead of morphing when true. */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun rememberMorphCorner(hovered: Boolean, selected: Boolean = false, base: Dp = 24.dp): Dp {
    val target = if (hovered || selected) 12.dp else base
    val reduceMotion = rememberReduceMotion()
    val animated by animateDpAsState(
        targetValue = target,
        animationSpec = spatial(reduceMotion),
        label = "card-corner-morph",
    )
    return animated
}

@Composable
fun StatusChip(status: InstanceStatus, width: Dp = 96.dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val background = when (status) {
        InstanceStatus.RUNNING -> colors.primaryContainer
        InstanceStatus.READY -> colors.secondaryContainer
        InstanceStatus.READ_FAILED -> colors.errorContainer
        InstanceStatus.UPDATE_AVAILABLE -> colors.tertiaryContainer
    }
    val foreground = when (status) {
        InstanceStatus.RUNNING -> colors.onPrimaryContainer
        InstanceStatus.READY -> colors.onSecondaryContainer
        InstanceStatus.READ_FAILED -> colors.onErrorContainer
        InstanceStatus.UPDATE_AVAILABLE -> colors.onTertiaryContainer
    }
    Box(
        modifier = modifier
            .width(width)
            .height(26.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = status.label,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            color = foreground,
        )
    }
}

@Composable
fun CircleButton(
    size: Dp,
    container: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .then(
                if (borderColor != null) {
                    Modifier.border(1.dp, borderColor, CircleShape)
                } else {
                    Modifier
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun IconCircle(
    icon: ImageVector,
    tint: Color,
    background: Color,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun PrimaryButton(
    text: String,
    width: Dp,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    leadingIcon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = if (hovered) colors.primary.copy(alpha = 0.88f) else colors.primary,
        animationSpec = effects(reduceMotion),
        label = "primary-button",
    )
    Row(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(CircleShape)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onPrimary,
        )
    }
}

@Composable
fun TonalButton(
    text: String,
    width: Dp,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
        )
    }
}

@Composable
fun OutlinedPillButton(
    text: String,
    width: Dp,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    borderColor: Color? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(CircleShape)
            .border(1.dp, borderColor ?: colors.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
        )
    }
}

/** 分段控件: outer tonal track, animated selected pill. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    width: Dp,
    height: Dp = 40.dp,
    onSelect: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val reduceMotion = rememberReduceMotion()
    Row(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val background by animateColorAsState(
                targetValue = if (selected) colors.primaryContainer else Color.Transparent,
                animationSpec = effects(reduceMotion),
                label = "segment-bg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(background)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** 44x24 switch per the design, smaller than the M3 default. */
@Composable
fun YmclSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val reduceMotion = rememberReduceMotion()
    val track by animateColorAsState(
        targetValue = if (checked) colors.primary else colors.surfaceContainerHighest,
        animationSpec = effects(reduceMotion),
        label = "switch-track",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = spatial(reduceMotion),
        label = "switch-thumb",
    )
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(24.dp)
            .clip(CircleShape)
            .background(track)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (checked) colors.onPrimary else colors.outline),
        )
    }
}

/**
 * Clickable surface that morphs its corner radius on hover (and when selected),
 * lifts 2dp on hover and scales to 0.98 while pressed. All three are drawn in a
 * graphicsLayer: the measured layout box (and therefore the click target) never
 * changes size.
 */
@Composable
fun MorphSurface(
    modifier: Modifier = Modifier,
    baseColor: Color,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val corner = rememberMorphCorner(hovered = hovered, selected = selected)
    val shape = RoundedCornerShape(corner)
    val density = LocalDensity.current
    val liftPx = remember(density) { with(density) { CARD_HOVER_LIFT.toPx() } }
    val scale by animateFloatAsState(
        targetValue = if (pressed) CARD_PRESS_SCALE else 1f,
        animationSpec = spatial(reduceMotion),
        label = "card-press-scale",
    )
    val lift by animateFloatAsState(
        targetValue = if (hovered) -liftPx else 0f,
        animationSpec = spatial(reduceMotion),
        label = "card-hover-lift",
    )
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = lift
            }
            .clip(shape)
            .background(baseColor)
            .hoverable(interaction)
            .then(clickableModifier),
    ) {
        content()
    }
}

// ---- Shared async feedback components (loading / error / empty) ----------
// Every page uses these three instead of writing its own; all colors come from
// MaterialTheme and all sizes reuse the existing dp/sp tokens.

/** Inline spinner + short label for a running async action. */
@Composable
fun LoadingIndicator(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}

/** Skeleton placeholder rows shown while a list is loading (never blank, never one line). */
@Composable
fun LoadingSkeleton(rows: Int = 3, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceContainerHighest),
            )
        }
    }
}

/** One error banner: what happened + the next step, with optional retry / back. */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.errorContainer)
            .padding(16.dp),
    ) {
        Column {
            Text(message, fontSize = 12.sp, lineHeight = 18.sp, color = colors.onErrorContainer)
            if (onRetry != null || onBack != null) {
                Spacer(Modifier.height(10.dp))
                Row {
                    if (onRetry != null) {
                        OutlinedPillButton(text = "重试", width = 96.dp, onClick = onRetry)
                        Spacer(Modifier.width(12.dp))
                    }
                    if (onBack != null) {
                        TonalButton(text = "返回列表", width = 108.dp, onClick = onBack)
                    }
                }
            }
        }
    }
}

/** One empty state: an optional icon, one short sentence and one optional button. */
@Composable
fun EmptyStateView(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    buttonText: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
        }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message, fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        if (buttonText != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            PrimaryButton(text = buttonText, width = 148.dp, onClick = onAction)
        }
    }
}
