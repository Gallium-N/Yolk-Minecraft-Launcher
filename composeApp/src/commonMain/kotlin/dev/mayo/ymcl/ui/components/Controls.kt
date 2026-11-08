package dev.mayo.ymcl.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mayo.ymcl.domain.InstanceStatus

/** Set from Settings → 减少动效; components snap instead of morphing when true. */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun rememberMorphCorner(hovered: Boolean, selected: Boolean = false, base: Dp = 24.dp): Dp {
    val target = if (hovered || selected) 12.dp else base
    if (LocalReduceMotion.current) return target
    val animated by animateDpAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
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
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        if (hovered) colors.primary.copy(alpha = 0.88f) else colors.primary,
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
                if (selected) colors.primaryContainer else Color.Transparent,
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
    val track by animateColorAsState(
        if (checked) colors.primary else colors.surfaceContainerHighest,
        label = "switch-track",
    )
    val thumbOffset by animateDpAsState(
        if (checked) 22.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
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

/** Clickable surface that morphs its corner radius on hover (and when selected). */
@Composable
fun MorphSurface(
    modifier: Modifier = Modifier,
    baseColor: Color,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val corner = rememberMorphCorner(hovered = hovered, selected = selected)
    val shape = RoundedCornerShape(corner)
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(baseColor)
            .hoverable(interaction)
            .then(clickableModifier),
    ) {
        content()
    }
}
