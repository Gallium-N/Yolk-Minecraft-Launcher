package dev.mayo.ymcl.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mayo.ymcl.app.AppPage
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.app.formatPlayTime
import dev.mayo.ymcl.domain.Instance
import dev.mayo.ymcl.platform.formatLastLaunch
import dev.mayo.ymcl.platform.nowMillis
import dev.mayo.ymcl.ui.components.CircleButton
import dev.mayo.ymcl.ui.components.IconCircle
import dev.mayo.ymcl.ui.components.MorphSurface
import dev.mayo.ymcl.ui.components.PageHeader
import dev.mayo.ymcl.ui.components.SectionHeader
import dev.mayo.ymcl.ui.components.StatusChip
import dev.mayo.ymcl.ui.icons.YmclIcons

@Composable
fun HomePage(state: AppState) {
    val now = nowMillis()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentWidth = maxWidth
        val compact = maxHeight < 760.dp
        val heroHeight = if (compact) 200.dp else 240.dp
        // RW = clamp(400, 520, 0.437 * W_c); the left column takes the rest.
        val rightWidth = (contentWidth * 0.437f).coerceIn(400.dp, 520.dp)
        // STAT_DX = clamp(170, 210, (W_c - 300) / 4)
        val statSpacing = ((contentWidth - 300.dp) / 4f).coerceIn(170.dp, 210.dp)

        Column(Modifier.fillMaxSize()) {
            PageHeader(
                title = "启动台",
                meta = "${state.instances.size} 个实例 · ${state.updateCount} 个可更新",
            )
            HeroCard(
                state = state,
                now = now,
                heroHeight = heroHeight,
                statSpacing = statSpacing,
            )
            Spacer(Modifier.height(if (compact) 16.dp else 24.dp))
            Row(Modifier.fillMaxWidth()) {
                RecentInstancesColumn(
                    state = state,
                    now = now,
                    compact = compact,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(24.dp))
                GetContentColumn(
                    modifier = Modifier.width(rightWidth),
                    compact = compact,
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    state: AppState,
    now: Long,
    heroHeight: Dp,
    statSpacing: Dp,
) {
    val colors = MaterialTheme.colorScheme
    val instance = state.mainInstance ?: return
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
            .clip(shape)
            // No fixed end offset: the gradient follows the card's real width.
            .background(
                Brush.linearGradient(
                    colors = listOf(colors.primaryContainer, colors.tertiaryContainer),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, top = 32.dp),
        ) {
            Text(
                text = "主实例",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onPrimaryContainer.copy(alpha = 0.78f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = instance.displayName,
                fontSize = 36.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimaryContainer,
            )
            Spacer(Modifier.height(16.dp))
            Row {
                HeroStat("游戏版本", instance.mcVersion, statSpacing)
                HeroStat("模组加载器", instance.loader.display, statSpacing)
                HeroStat("总游玩", formatPlayTime(instance.totalPlayTimeMs), statSpacing)
                HeroStat("上次启动", formatLastLaunch(instance.lastLaunchedAt, now), statSpacing)
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleButton(
                size = 48.dp,
                container = colors.onPrimaryContainer.copy(alpha = 0.10f),
                contentColor = colors.onPrimaryContainer,
                borderColor = colors.onPrimaryContainer.copy(alpha = 0.35f),
                onClick = { },
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "实例设置",
                    tint = colors.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            CircleButton(
                size = 72.dp,
                container = colors.primary,
                contentColor = colors.onPrimary,
                onClick = { state.launch(instance.id) },
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "启动",
                    tint = colors.onPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, width: Dp) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.width(width)) {
        Text(
            text = label,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = colors.onPrimaryContainer.copy(alpha = 0.72f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onPrimaryContainer,
        )
    }
}

@Composable
private fun RecentInstancesColumn(
    state: AppState,
    now: Long,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionHeader("最近实例", hint = "按上次启动排序")
        Spacer(Modifier.height(16.dp))

        val recent = state.recentInstances
        recent.forEach { instance ->
            RecentInstanceCard(instance = instance, now = now) {
                state.selectInstance(instance.id)
            }
            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        }

        AllInstancesBar(count = state.instances.size) {
            state.navigate(AppPage.INSTANCES)
        }
    }
}

@Composable
private fun RecentInstanceCard(instance: Instance, now: Long, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MorphSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(144.dp),
        baseColor = colors.surfaceContainerLow,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 24.dp, top = 34.dp),
            ) {
                Text(
                    text = instance.displayName,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = instance.subLine,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = colors.onSurfaceVariant,
                )
            }

            Text(
                text = "上次启动 ${formatLastLaunch(instance.lastLaunchedAt, now)}",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp, end = 24.dp),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariant,
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 12.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            StatusChip(
                status = instance.status,
                width = 96.dp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 24.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun AllInstancesBar(count: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MorphSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        baseColor = colors.surfaceContainerHigh,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                YmclIcons.Folder,
                contentDescription = null,
                tint = colors.onSurface,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "所有实例",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "共 $count 个",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private data class ContentRow(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val primaryIcon: Boolean = false,
)

private val CONTENT_ROWS = listOf(
    ContentRow("下载游戏版本", "原版 · 快照 · 旧版本", Icons.Filled.KeyboardArrowDown, primaryIcon = true),
    ContentRow("打开实例文件夹", "主实例 · mods / config / saves", YmclIcons.Folder),
    ContentRow("浏览模组与整合包", "Modrinth · CurseForge", Icons.AutoMirrored.Filled.List),
    ContentRow("导入本地整合包", ".mrpack / zip", Icons.Filled.Add),
)

@Composable
private fun GetContentColumn(modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        SectionHeader("获取内容")
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 360.dp else 372.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surfaceContainerLow)
                .padding(vertical = if (compact) 12.dp else 18.dp),
        ) {
            CONTENT_ROWS.forEachIndexed { index, row ->
                ContentPanelRow(row = row, showDivider = index != CONTENT_ROWS.lastIndex)
            }
        }
    }
}

@Composable
private fun ContentPanelRow(row: ContentRow, showDivider: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircle(
                icon = row.icon,
                tint = if (row.primaryIcon) colors.primary else colors.onSurfaceVariant,
                background = colors.surfaceContainerHigh,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = row.title,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.subtitle,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.outlineVariant.copy(alpha = 0.4f)),
            )
        }
    }
}
