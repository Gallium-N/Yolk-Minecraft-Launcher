package dev.mayo.ymcl.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mayo.ymcl.app.AppPage
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.app.InstanceSort
import dev.mayo.ymcl.app.InstanceSummary
import dev.mayo.ymcl.platform.formatLastLaunch
import dev.mayo.ymcl.platform.nowMillis
import dev.mayo.ymcl.ui.components.CircleButton
import dev.mayo.ymcl.ui.components.OutlinedPillButton
import dev.mayo.ymcl.ui.components.PageHeader
import dev.mayo.ymcl.ui.components.PrimaryButton
import dev.mayo.ymcl.ui.components.StatusChip
import dev.mayo.ymcl.ui.icons.YmclIcons

@Composable
fun InstancesPage(state: AppState) {
    val colors = MaterialTheme.colorScheme
    val now = nowMillis()
    val instances = state.filteredInstances()
    val clipboard = LocalClipboardManager.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentWidth = maxWidth
        val columns = tableColumns(contentWidth)
        // Table eats the remaining height; at the 700dp minimum it still keeps
        // all five 92dp rows (header + rows = 504dp) instead of clipping the last.
        val tableHeight = (maxHeight - 220.dp).coerceAtLeast(504.dp)

        Column(Modifier.fillMaxWidth()) {
            PageHeader(
                title = "实例",
                meta = "${state.instances.size} 个实例 · ${state.updateCount} 个可更新",
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(
                    value = state.searchQuery,
                    onValueChange = { state.searchQuery = it },
                )
                Spacer(Modifier.weight(1f))
                SortMenu(state = state)
                Spacer(Modifier.width(12.dp))
                OutlinedPillButton(text = "刷新", width = 96.dp, onClick = { state.refresh() })
                Spacer(Modifier.width(12.dp))
                PrimaryButton(
                    text = "导入整合包",
                    width = 128.dp,
                    onClick = { state.navigate(AppPage.IMPORT) },
                )
                Spacer(Modifier.width(12.dp))
                OutlinedPillButton(
                    text = "新建实例",
                    width = 120.dp,
                    onClick = { state.navigate(AppPage.NEW_INSTANCE) },
                )
            }

            Spacer(Modifier.height(16.dp))

            state.launchError?.let { error ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(error, fontSize = 12.sp, lineHeight = 18.sp, color = colors.onErrorContainer)
                }
                Spacer(Modifier.height(12.dp))
            }

            if (state.instances.isEmpty()) {
                EmptyInstances(onGetContent = { state.navigate(AppPage.NEW_INSTANCE) })
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tableHeight)
                        .clip(RoundedCornerShape(24.dp))
                        .background(colors.surfaceContainerLow),
                ) {
                    TableHeader(columns)
                    if (instances.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "没有匹配的实例",
                                fontSize = 13.sp,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            instances.forEach { instance ->
                                InstanceRow(
                                    instance = instance,
                                    now = now,
                                    columns = columns,
                                    selected = instance.id == state.selectedInstanceId,
                                    running = state.activeInstanceId == instance.id,
                                    launching = state.launchPending,
                                    onSelect = { state.selectInstance(instance.id) },
                                    onLaunch = { state.launch(instance.id) },
                                    onStop = { state.stop(instance.id) },
                                    onOpenFolder = { state.openInstanceFolder(instance.id) },
                                    onCopyPath = { clipboard.setText(AnnotatedString(instance.path)) },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "共 ${state.instances.size} 个实例 · ${state.updateCount} 个可更新" +
                    if (state.instanceReadFailures > 0) " · ${state.instanceReadFailures} 个实例读取失败" else "",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = if (state.instanceReadFailures > 0) colors.error else colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyInstances(onGetContent: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceContainerLow),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                YmclIcons.Folder,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "还没有实例",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "点「新建实例」安装一个游戏版本，或导入整合包，实例会出现在这里。",
                fontSize = 13.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(text = "获取内容", width = 148.dp, onClick = onGetContent)
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .width(360.dp)
            .height(44.dp)
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh)
            .clickable { focusRequester.requestFocus() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = "搜索实例、版本或模组…",
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                cursorBrush = SolidColor(colors.primary),
                textStyle = TextStyle(fontSize = 13.sp, color = colors.onSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
    }
}

@Composable
private fun SortMenu(state: AppState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedPillButton(
            text = "${state.sort.label} ▾",
            width = 132.dp,
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            InstanceSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sort.label, fontSize = 13.sp) },
                    onClick = {
                        state.sort = sort
                        expanded = false
                    },
                )
            }
        }
    }
}

private data class TableColumns(
    val version: Dp,
    val playTime: Dp,
    val lastLaunch: Dp,
    val status: Dp,
    val actions: Dp,
)

/** Delta-based columns: only the first column is fixed, actions stick to the right. */
private fun tableColumns(contentWidth: Dp): TableColumns {
    val delta = contentWidth - 1144.dp
    return TableColumns(
        version = 300.dp + delta * 0.10f,
        playTime = 560.dp + delta * 0.30f,
        lastLaunch = 720.dp + delta * 0.45f,
        status = 880.dp + delta * 0.66f,
        actions = contentWidth - 104.dp,
    )
}

@Composable
private fun TableHeader(columns: TableColumns) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
    ) {
        HeaderCell("实例", 88.dp)
        HeaderCell("版本 · 加载器", columns.version)
        HeaderCell("总游玩", columns.playTime)
        HeaderCell("上次启动", columns.lastLaunch)
        HeaderCell("状态", columns.status)
        HeaderCell("操作", columns.actions)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.outlineVariant.copy(alpha = 0.35f)),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.HeaderCell(text: String, x: androidx.compose.ui.unit.Dp) {
    Text(
        text = text,
        modifier = Modifier
            .align(Alignment.CenterStart)
            .offset(x = x),
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InstanceRow(
    instance: InstanceSummary,
    now: Long,
    columns: TableColumns,
    selected: Boolean,
    running: Boolean,
    launching: Boolean,
    onSelect: () -> Unit,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onOpenFolder: () -> Unit,
    onCopyPath: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            selected -> colors.surfaceContainerHigh.copy(alpha = 0.55f)
            hovered -> colors.surfaceContainerHigh.copy(alpha = 0.30f)
            else -> colors.surfaceContainerLow.copy(alpha = 0f)
        },
        label = "instance-row",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 24.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = instance.displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimaryContainer,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 88.dp),
        ) {
            Text(
                text = instance.displayName,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${instance.modCount} 个模组",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = columns.version),
        ) {
            Text(
                text = instance.mcVersion,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = instance.loaderLine,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariant,
            )
        }

        Text(
            text = dev.mayo.ymcl.app.formatPlayTime(instance.totalPlayTimeMs),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = columns.playTime),
            fontSize = 13.sp,
            color = colors.onSurface,
        )

        Text(
            text = formatLastLaunch(instance.lastLaunchedAt, now),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = columns.lastLaunch),
            fontSize = 13.sp,
            color = colors.onSurface,
        )

        StatusChip(
            status = instance.status,
            width = 92.dp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = columns.status),
        )

        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = columns.actions),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleButton(
                size = 36.dp,
                container = colors.primary,
                contentColor = colors.onPrimary,
                onClick = { if (running) onStop() else onLaunch() },
            ) {
                if (launching && !running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.onPrimary,
                    )
                } else {
                    Icon(
                        if (running) Icons.Filled.Close else Icons.Filled.PlayArrow,
                        contentDescription = if (running) "停止" else "启动",
                        tint = colors.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            MoreMenu(onOpenFolder = onOpenFolder, onCopyPath = onCopyPath)
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.outlineVariant.copy(alpha = 0.30f)),
        )
    }
}

/** 三点菜单：只放「打开文件夹」「复制路径」，不做删除。 */
@Composable
private fun MoreMenu(onOpenFolder: () -> Unit, onCopyPath: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "更多",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("打开文件夹", fontSize = 13.sp) },
                onClick = { expanded = false; onOpenFolder() },
            )
            DropdownMenuItem(
                text = { Text("复制路径", fontSize = 13.sp) },
                onClick = { expanded = false; onCopyPath() },
            )
        }
    }
}
