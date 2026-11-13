package dev.mayo.ymcl.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.mayo.ymcl.app.AppPage
import dev.mayo.ymcl.ui.icons.YmclIcons
import dev.mayo.ymcl.ui.motion.effects
import dev.mayo.ymcl.ui.motion.rememberReduceMotion
import dev.mayo.ymcl.ui.motion.spatial
import kotlin.math.abs

private data class NavEntry(val page: AppPage, val icon: ImageVector)

private val NAV_ENTRIES = listOf(
    NavEntry(AppPage.HOME, Icons.Filled.PlayArrow),
    NavEntry(AppPage.INSTANCES, YmclIcons.Folder),
    NavEntry(AppPage.GET_CONTENT, Icons.AutoMirrored.Filled.List),
    NavEntry(AppPage.DIAGNOSTICS, Icons.Filled.Warning),
    NavEntry(AppPage.SETTINGS, Icons.Filled.Settings),
)

private val NAV_ITEM_SIZE = 64.dp
private val NAV_STRIDE = 80.dp
private val NAV_CORNER = 16.dp
private const val NAV_TRAVEL_STRETCH = 0.20f

@Composable
fun YmclNavigationRail(current: AppPage, onSelect: (AppPage) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val reduceMotion = rememberReduceMotion()
    val targetIndex = NAV_ENTRIES.indexOfFirst { it.page == current }.coerceAtLeast(0)
    val density = LocalDensity.current
    val stridePx = remember(density) { with(density) { NAV_STRIDE.toPx() } }
    val itemPx = remember(density) { with(density) { NAV_ITEM_SIZE.toPx() } }
    val cornerPx = remember(density) { with(density) { NAV_CORNER.toPx() } }
    val indicatorColor = colors.surfaceContainerHigh

    // ONE capsule that slides from the previous item to the new one; no per-item
    // fade-in/out. Spatial spring drives the position.
    val yState = animateFloatAsState(
        targetValue = stridePx * targetIndex,
        animationSpec = spatial(reduceMotion),
        label = "nav-indicator-y",
    )
    // Width + corner morph while travelling: derived from the spring, so no
    // object is allocated per frame and no layout size is animated.
    val stretchState = remember(stridePx, targetIndex) {
        derivedStateOf {
            val travel = abs(yState.value - stridePx * targetIndex) / stridePx
            1f + NAV_TRAVEL_STRETCH * travel.coerceIn(0f, 1f)
        }
    }

    Column(
        modifier = Modifier
            .width(88.dp)
            .fillMaxHeight()
            .background(colors.surfaceContainer),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Y",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimary,
            )
        }
        Spacer(Modifier.height(36.dp))
        Box(
            modifier = Modifier
                .width(NAV_ITEM_SIZE)
                .drawBehind {
                    val stretch = stretchState.value
                    val w = itemPx * stretch
                    val left = (size.width - w) / 2f
                    val corner = cornerPx / stretch
                    drawRoundRect(
                        color = indicatorColor,
                        topLeft = Offset(left, yState.value),
                        size = Size(w, itemPx),
                        cornerRadius = CornerRadius(corner, corner),
                    )
                },
        ) {
            Column {
                NAV_ENTRIES.forEachIndexed { index, entry ->
                    NavRailItem(
                        entry = entry,
                        selected = current == entry.page,
                        reduceMotion = reduceMotion,
                        onClick = { onSelect(entry.page) },
                    )
                    if (index != NAV_ENTRIES.lastIndex) Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun NavRailItem(
    entry: NavEntry,
    selected: Boolean,
    reduceMotion: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The selected pill is drawn by the parent; items only keep a hover tint.
    val background by animateColorAsState(
        targetValue = if (hovered && !selected) {
            colors.surfaceContainerHigh.copy(alpha = 0.55f)
        } else {
            Color.Transparent
        },
        animationSpec = effects(reduceMotion),
        label = "nav-item-bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
        animationSpec = effects(reduceMotion),
        label = "nav-item-fg",
    )

    Box {
        Column(
            modifier = Modifier
                .width(NAV_ITEM_SIZE)
                .height(NAV_ITEM_SIZE)
                .clip(RoundedCornerShape(NAV_CORNER))
                .background(background)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Icon(
                imageVector = entry.icon,
                contentDescription = entry.page.title,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
            // Text label only for the selected item; others rely on the hover tooltip.
            if (selected) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = entry.page.title,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
            }
        }
        if (hovered && !selected) {
            Popup(
                alignment = Alignment.CenterStart,
                offset = IntOffset(with(density) { 72.dp.roundToPx() }, 0),
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.inverseSurface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(entry.page.title, fontSize = 12.sp, color = colors.inverseOnSurface)
                }
            }
        }
    }
}
