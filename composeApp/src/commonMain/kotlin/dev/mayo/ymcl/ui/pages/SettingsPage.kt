package dev.mayo.ymcl.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.app.Density
import dev.mayo.ymcl.app.SettingsGroup
import dev.mayo.ymcl.app.ThemeMode
import dev.mayo.ymcl.ui.components.PageHeader
import dev.mayo.ymcl.ui.components.OutlinedPillButton
import dev.mayo.ymcl.ui.components.SegmentedControl

@Composable
fun SettingsPage(state: AppState) {
    Column(Modifier.fillMaxWidth()) {
        PageHeader(title = "设置", meta = "YMCL 0.1.0 · 本地模式")

        Row(Modifier.fillMaxWidth()) {
            SettingsSubNav(state)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                when (state.settingsGroup) {
                    SettingsGroup.APPEARANCE -> AppearanceCards(state)
                    SettingsGroup.GAME -> GameCards(state)
                    SettingsGroup.ABOUT -> AboutCard()
                    else -> PlaceholderCard(state.settingsGroup)
                }
            }
        }
    }
}

@Composable
private fun SettingsSubNav(state: AppState) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.width(240.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsGroup.entries.forEach { group ->
            val selected = state.settingsGroup == group
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val background by animateColorAsState(
                when {
                    selected -> colors.secondaryContainer
                    hovered -> colors.surfaceContainerHigh
                    else -> colors.surfaceContainer.copy(alpha = 0f)
                },
                label = "settings-nav-bg",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(background)
                    .hoverable(interaction)
                    .clickable(interactionSource = interaction, indication = null) {
                        state.settingsGroup = group
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = group.label,
                    modifier = Modifier.padding(start = 16.dp),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppearanceCards(state: AppState) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard(height = 140.dp) {
            Text("主题", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "跟随系统时读取桌面环境的深浅色设置",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            SegmentedControl(
                options = ThemeMode.entries.map { it.label },
                selectedIndex = ThemeMode.entries.indexOf(state.themeMode),
                width = 320.dp,
                height = 40.dp,
                onSelect = { state.themeMode = ThemeMode.entries[it] },
            )
        }

        SettingsCard(height = 160.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "从壁纸自动取色",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.weight(1f))
                dev.mayo.ymcl.ui.components.YmclSwitch(
                    checked = state.dynamicColor,
                    onCheckedChange = { state.dynamicColor = it },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "用 KMeans 抽主色生成整套配色，关闭则只用种子色",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        colors.primary,
                        colors.secondary,
                        colors.tertiary,
                        colors.inversePrimary,
                        colors.onTertiaryFixedVariant,
                    ).forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                    }
                }
                Spacer(Modifier.width(24.dp))
                Text(
                    "当前种子色 #EFDBB2 · 来自「暖金」预设",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        SettingsCard(height = 120.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "减少动效",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.weight(1f))
                dev.mayo.ymcl.ui.components.YmclSwitch(
                    checked = state.reduceMotion,
                    onCheckedChange = { state.reduceMotion = it },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "关闭卡片 shape morph、弹簧与页面转场，仅保留淡入",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
        }

        SettingsCard(height = 130.dp) {
            Text("界面密度", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "紧凑会让列表行高从 92 降到 72，一屏多看两行",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            SegmentedControl(
                options = Density.entries.map { it.label },
                selectedIndex = Density.entries.indexOf(state.density),
                width = 220.dp,
                height = 40.dp,
                onSelect = { state.density = Density.entries[it] },
            )
        }
    }
}

@Composable
private fun GameCards(state: AppState) {
    val colors = MaterialTheme.colorScheme
    var pathDraft by remember { mutableStateOf(state.gameDirectory) }
    LaunchedEffect(state.gameDirectory) { pathDraft = state.gameDirectory }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard(height = 148.dp) {
            Text("游戏目录", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "读取 versions/*/<id>.json，不会联网或补全文件",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceContainerHigh)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = pathDraft,
                        onValueChange = { pathDraft = it },
                        singleLine = true,
                        cursorBrush = SolidColor(colors.primary),
                        textStyle = TextStyle(fontSize = 12.sp, color = colors.onSurface),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(12.dp))
                OutlinedPillButton(
                    text = "应用",
                    width = 92.dp,
                    onClick = { state.changeGameDirectory(pathDraft) },
                )
            }
        }

        SettingsCard(height = (96 + (state.javaRuntimes.size.coerceAtLeast(1) * 62)).dp) {
            Text("Java 运行时", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "来自 JAVA_HOME、PATH、系统 JVM 目录、sdkman 与 Gradle JDK；点击选择启动用 Java",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            if (state.javaRuntimes.isEmpty()) {
                Text("未发现 Java；请安装 JDK 21 后重启 YMCL。", fontSize = 12.sp, color = colors.error)
            } else {
                state.javaRuntimes.forEach { runtime ->
                    val selected = runtime.path == state.selectedJavaPath
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) colors.secondaryContainer else colors.surfaceContainerHigh.copy(alpha = 0.45f),
                            )
                            .clickable { state.selectJava(runtime.path) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (selected) colors.primary else colors.outlineVariant),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${runtime.vendor} ${runtime.version} · Java ${runtime.major}",
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                            )
                            Text(
                                runtime.path,
                                fontSize = 10.sp,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Text(runtime.arch, fontSize = 10.sp, color = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard(height = 150.dp) {
            Text("YMCL", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(6.dp))
            Text("Yolk Minecraft Launcher · P2", fontSize = 12.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text(
                "基于 HMCLCore（GPL-3.0-or-later WITH AdditionRef-HMCL）。",
                fontSize = 12.sp,
                color = colors.onSurface,
            )
            Text(
                "Copyright © HMCL Authors. 完整许可证见仓库 LICENSE。",
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsCard(height: Dp, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceContainerLow)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        content = content,
    )
}

@Composable
private fun PlaceholderCard(group: SettingsGroup) {
    val colors = MaterialTheme.colorScheme
    SettingsCard(height = 120.dp) {
        Text(
            text = group.label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "待实现",
            fontSize = 12.sp,
            color = colors.onSurfaceVariant,
        )
    }
}
