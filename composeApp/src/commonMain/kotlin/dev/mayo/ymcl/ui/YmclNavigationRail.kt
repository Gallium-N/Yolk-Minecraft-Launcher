package dev.mayo.ymcl.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mayo.ymcl.app.AppPage
import dev.mayo.ymcl.ui.icons.YmclIcons

private data class NavEntry(val page: AppPage, val icon: ImageVector)

private val NAV_ENTRIES = listOf(
    NavEntry(AppPage.HOME, Icons.Filled.PlayArrow),
    NavEntry(AppPage.INSTANCES, YmclIcons.Folder),
    NavEntry(AppPage.GET_CONTENT, Icons.AutoMirrored.Filled.List),
    NavEntry(AppPage.DIAGNOSTICS, Icons.Filled.Warning),
    NavEntry(AppPage.SETTINGS, Icons.Filled.Settings),
)

@Composable
fun YmclNavigationRail(current: AppPage, onSelect: (AppPage) -> Unit) {
    val colors = MaterialTheme.colorScheme
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
        NAV_ENTRIES.forEach { entry ->
            NavRailItem(
                entry = entry,
                selected = current == entry.page,
                onClick = { onSelect(entry.page) },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NavRailItem(entry: NavEntry, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            selected -> colors.surfaceContainerHigh
            hovered -> colors.surfaceContainerHigh.copy(alpha = 0.55f)
            else -> colors.surfaceContainer.copy(alpha = 0f)
        },
        label = "nav-item-bg",
    )
    val contentColor by animateColorAsState(
        if (selected) colors.primary else colors.onSurfaceVariant,
        label = "nav-item-fg",
    )

    Box {
        Column(
            modifier = Modifier
                .width(64.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(16.dp))
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
