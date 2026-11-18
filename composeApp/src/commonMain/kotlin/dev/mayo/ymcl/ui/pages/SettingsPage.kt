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
import dev.mayo.ymcl.ui.motion.effects
import dev.mayo.ymcl.ui.motion.rememberReduceMotion

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
                    SettingsGroup.DOWNLOAD -> DownloadCards(state)
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
    val reduceMotion = rememberReduceMotion()
    Column(
        modifier = Modifier.width(240.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsGroup.entries.forEach { group ->
            val selected = state.settingsGroup == group
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val background by animateColorAsState(
                targetValue = when {
                    selected -> colors.secondaryContainer
                    hovered -> colors.surfaceContainerHigh
                    else -> colors.surfaceContainer.copy(alpha = 0f)
                },
                animationSpec = effects(reduceMotion),
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

        SettingsCard(height = (150 + (state.javaRuntimes.size.coerceAtLeast(1) * 58)).dp) {
            Text("Java 运行时", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "来源：启动器自装、JAVA_HOME、PATH、系统 JVM 目录、sdkman、Gradle JDK；点击一行设为默认",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            // Auto-manage + rescan controls.
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedPillButton(
                    text = if (state.autoManageJava) "自动管理：开" else "自动管理：关",
                    width = 120.dp,
                    onClick = { state.autoManageJava = !state.autoManageJava },
                )
                Spacer(Modifier.width(10.dp))
                OutlinedPillButton(text = "重新扫描", width = 96.dp, onClick = { state.rescanJava() })
                Spacer(Modifier.width(10.dp))
                OutlinedPillButton(
                    text = "安装 Java…",
                    width = 104.dp,
                    onClick = { state.loadAvailableJavaMajors() },
                )
            }
            if (state.availableJavaMajors.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("选择要安装的主版本（来自 Adoptium）：", fontSize = 11.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row {
                    state.availableJavaMajors.forEach { major ->
                        OutlinedPillButton(
                            text = "Java $major",
                            width = 84.dp,
                            onClick = { state.requestJavaInstall("__settings__", "手动安装", major) },
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (state.javaRuntimes.isEmpty()) {
                Text("未发现 Java；点「安装 Java…」自动安装，或安装 JDK 后「重新扫描」。", fontSize = 12.sp, color = colors.error)
            } else {
                state.javaRuntimes.forEach { runtime ->
                    val selected = runtime.path == state.selectedJavaPath
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
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
                                "${runtime.vendor} ${runtime.version} · Java ${runtime.major} · ${runtime.source}",
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                            )
                            Text(runtime.path, fontSize = 10.sp, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                        Text(runtime.arch, fontSize = 10.sp, color = colors.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                                .background(colors.surfaceContainerHigh)
                                .clickable {
                                    // runtime.path ends in .../bin/java -> home is two segments up.
                                    val sep = if ('/' in runtime.path) '/' else '\\'
                                    val bin = runtime.path.substringBeforeLast(sep)
                                    val home = bin.substringBeforeLast(sep)
                                    dev.mayo.ymcl.platform.openFolder(home.ifBlank { runtime.path })
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text("↗", fontSize = 12.sp, color = colors.onSurfaceVariant) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun DownloadCards(state: AppState) {
    val colors = MaterialTheme.colorScheme
    var keyDraft by remember { mutableStateOf(state.curseForgeApiKey) }
    LaunchedEffect(state.curseForgeApiKey) { keyDraft = state.curseForgeApiKey }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard(height = 164.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "允许 curse.tools 只读代理",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.weight(1f))
                dev.mayo.ymcl.ui.components.YmclSwitch(
                    checked = state.allowCurseToolsProxy,
                    onCheckedChange = { state.saveAllowCurseToolsProxy(it) },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "导入 CurseForge 整合包时，未配置 API key 就用这个第三方代理解析文件名（日志标注 via=curse.tools）。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "关闭后只走官方 API key；两者都不可用会列出缺文件清单。",
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
            )
        }
        SettingsCard(height = 168.dp) {
            Text("CurseForge API key", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "配置后走官方 API 解析文件名，优先于代理。仅保存在本机 launcher.json。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
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
                    if (keyDraft.isEmpty()) {
                        Text("未配置（留空走代理）", fontSize = 12.sp, color = colors.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        singleLine = true,
                        cursorBrush = SolidColor(colors.primary),
                        textStyle = TextStyle(fontSize = 12.sp, color = colors.onSurface),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(12.dp))
                OutlinedPillButton(
                    text = "保存",
                    width = 92.dp,
                    onClick = { state.saveCurseForgeApiKey(keyDraft.trim()) },
                )
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
