package dev.mayo.ymcl.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.app.ModrinthDialog
import dev.mayo.ymcl.app.ModrinthResultRow
import dev.mayo.ymcl.app.ModrinthUiState
import dev.mayo.ymcl.ui.components.EmptyStateView
import dev.mayo.ymcl.ui.components.ErrorBanner
import dev.mayo.ymcl.ui.components.LoadingIndicator
import dev.mayo.ymcl.ui.components.LoadingSkeleton
import dev.mayo.ymcl.ui.components.OutlinedPillButton
import dev.mayo.ymcl.ui.components.PageHeader
import dev.mayo.ymcl.ui.components.PrimaryButton
import dev.mayo.ymcl.ui.components.SectionHeader
import dev.mayo.ymcl.ui.components.SegmentedControl
import dev.mayo.ymcl.ui.components.TonalButton
import dev.mayo.ymcl.ui.motion.MotionVisibility
import dev.mayo.ymcl.ui.motion.listItemEntrance
import dev.mayo.ymcl.ui.motion.rememberReduceMotion
import kotlinx.coroutines.delay

private val GET_CONTENT_TABS = listOf("安装游戏版本", "获取模组")

@Composable
fun GetContentPage(state: AppState) {
    val reduceMotion = rememberReduceMotion()
    Column(Modifier.fillMaxSize()) {
        PageHeader(title = "获取内容", meta = "安装游戏版本 · 获取模组")
        SegmentedControl(
            options = GET_CONTENT_TABS,
            selectedIndex = state.getContentTab,
            width = 320.dp,
            onSelect = { state.getContentTab = it },
        )
        Spacer(Modifier.height(16.dp))
        when (state.getContentTab) {
            0 -> Box(Modifier.weight(1f).fillMaxWidth()) { NewInstanceWizard(state) }
            else -> ModSearchBody(state = state, reduceMotion = reduceMotion, modifier = Modifier.weight(1f))
        }
    }
}

/** Tab 1: the original Modrinth mod search, using the shared feedback components. */
@Composable
private fun ModSearchBody(state: AppState, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val ui = state.modrinthUi

    // Entering the tab searches the current query (blank -> popular feed).
    LaunchedEffect(Unit) { ui.search(ui.query) }
    LaunchedEffect(ui.toast) {
        if (ui.toast != null) {
            delay(4000)
            ui.clearToast()
        }
    }

    Column(modifier.fillMaxSize()) {
        InstanceSelector(ui = ui, instances = state.instances)
        Spacer(Modifier.height(12.dp))
        SearchRow(ui = ui)
        Spacer(Modifier.height(12.dp))

        val errorText = ui.error
        val lastError = remember { mutableStateOf<String?>(null) }
        LaunchedEffect(errorText) { if (errorText != null) lastError.value = errorText }
        MotionVisibility(visible = errorText != null, reduceMotion = reduceMotion) {
            Column {
                ErrorBanner(
                    message = lastError.value.orEmpty(),
                    onRetry = { ui.search(ui.query) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        if (ui.installing) {
            LoadingIndicator(text = ui.progressFile ?: "正在安装")
            Spacer(Modifier.height(8.dp))
        }

        when {
            ui.loading && ui.results.isEmpty() -> LoadingSkeleton(rows = 3)
            else -> {
                SectionHeader("搜索结果", hint = if (ui.query.isBlank()) "热门模组" else "关键词：${ui.query}")
                Spacer(Modifier.height(8.dp))
                Column(
                    // Search-results list scrolls INSIDE a weight(1f) bounded area;
                    // page header + instance selector above stay fixed. List-internal.
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (ui.results.isEmpty()) {
                        EmptyStateView(
                            title = "没有模组",
                            message = "换个关键词试试",
                            buttonText = "重试",
                            onAction = { ui.search(ui.query) },
                        )
                    }
                    ui.results.forEachIndexed { index, row ->
                        key(row.hit.projectId) {
                            ResultRow(
                                modifier = Modifier.listItemEntrance(index, reduceMotion),
                                row = row,
                                ui = ui,
                            )
                        }
                    }
                }
            }
        }

        val toastText = ui.toast
        val lastToast = remember { mutableStateOf<String?>(null) }
        LaunchedEffect(toastText) { if (toastText != null) lastToast.value = toastText }
        MotionVisibility(visible = toastText != null, reduceMotion = reduceMotion) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.primaryContainer)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(lastToast.value.orEmpty(), fontSize = 13.sp, color = colors.onPrimaryContainer)
            }
        }
    }

    ui.dialog?.let { dialog -> DependencyDialog(dialog = dialog, ui = ui) }
}

@Composable
private fun InstanceSelector(ui: ModrinthUiState, instances: List<dev.mayo.ymcl.app.InstanceSummary>) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val selected = ui.selectedInstance
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("目标实例", fontSize = 13.sp, color = colors.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box {
            OutlinedPillButton(
                text = selected?.let { "${it.displayName} · ${it.mcVersion} · ${it.loaderLine}" }
                    ?: "没有实例",
                width = 300.dp,
                onClick = { expanded = true },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                instances.forEach { instance ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${instance.displayName} · ${instance.mcVersion} · ${instance.loaderLine}",
                                fontSize = 13.sp,
                            )
                        },
                        onClick = {
                            expanded = false
                            ui.selectInstance(instance.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchRow(ui: ModrinthUiState) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .width(420.dp)
                .height(44.dp)
                .clip(CircleShape)
                .background(colors.surfaceContainerHigh)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (ui.query.isEmpty()) {
                    Text("搜索模组", fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
                BasicTextField(
                    value = ui.query,
                    onValueChange = { ui.query = it },
                    singleLine = true,
                    cursorBrush = SolidColor(colors.primary),
                    textStyle = TextStyle(fontSize = 13.sp, color = colors.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { ui.search(ui.query) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        PrimaryButton(text = "搜索", width = 120.dp, onClick = { ui.search(ui.query) })
    }
}

private fun formatDownloads(n: Long): String = when {
    n >= 100_000_000L -> "%.1f 亿".format(n / 100_000_000.0)
    n >= 10_000L -> "%.1f 万".format(n / 10_000.0)
    else -> n.toString()
}

@Composable
private fun ResultRow(
    modifier: Modifier = Modifier,
    row: ModrinthResultRow,
    ui: ModrinthUiState,
) {
    val colors = MaterialTheme.colorScheme
    val hit = row.hit
    val version = row.version
    val installed = hit.projectId in ui.installedProjectIds
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceContainerLow)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                hit.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                "${hit.author} · ${formatDownloads(hit.downloads)} 次下载 · ${hit.slug}",
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (version == null) {
            Text("无兼容版本", fontSize = 11.sp, color = colors.error)
        } else {
            val label = dev.mayo.ymcl.app.versionLabelOf(version.versionNumber, version.loaders)
            Column(horizontalAlignment = Alignment.End) {
                Text(label.version, fontSize = 12.sp, color = colors.onSurface)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    label.loader?.let { LoaderChip(it) }
                    if (label.loader != null) Spacer(Modifier.width(6.dp))
                    ChannelBadge(version.versionType)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        if (installed) {
            TonalButton(text = "已安装", width = 96.dp, onClick = { })
        } else {
            PrimaryButton(
                text = "安装",
                width = 96.dp,
                onClick = { ui.openInstall(row) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun LoaderChip(loader: String) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(loader, fontSize = 10.sp, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun ChannelBadge(type: String) {
    val colors = MaterialTheme.colorScheme
    val (label, bg, fg) = when (type.lowercase()) {
        "release" -> Triple("release", colors.primaryContainer, colors.onPrimaryContainer)
        "beta" -> Triple("beta", colors.tertiaryContainer, colors.onTertiaryContainer)
        else -> Triple("alpha", colors.errorContainer, colors.onErrorContainer)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, fontSize = 10.sp, color = fg)
    }
}

@Composable
private fun DependencyDialog(dialog: ModrinthDialog, ui: ModrinthUiState) {
    val colors = MaterialTheme.colorScheme
    val required = dialog.resolution.install.filter { it.reason != "root" && it.reason != "optional(selected)" }
    val optional = dialog.resolution.optional
    AlertDialog(
        onDismissRequest = { ui.dismissDialog() },
        title = {
            Text(
                "安装 ${dialog.hit.title} · ${dialog.version.versionNumber}",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            // Dependency list scrolls INSIDE a bounded dialog body (never the page).
            Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text("必需依赖（自动安装）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                Spacer(Modifier.height(6.dp))
                if (required.isEmpty()) {
                    Text("无", fontSize = 12.sp, color = colors.onSurfaceVariant)
                } else {
                    required.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = true, enabled = false, onCheckedChange = null)
                            Text("${p.version.projectId} · ${p.version.versionNumber}", fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("可选依赖（默认不勾）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                Spacer(Modifier.height(6.dp))
                if (optional.isEmpty()) {
                    Text("无", fontSize = 12.sp, color = colors.onSurfaceVariant)
                } else {
                    optional.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = p.version.id in ui.optionalChecked,
                                onCheckedChange = { ui.toggleOptional(p.version.id) },
                            )
                            Text("${p.version.projectId} · ${p.version.versionNumber}", fontSize = 12.sp)
                        }
                    }
                }
                if (dialog.resolution.errors.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        dialog.resolution.errors.joinToString("; "),
                        fontSize = 11.sp,
                        color = colors.error,
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(text = "确认安装", width = 132.dp, onClick = { ui.confirmInstall() })
        },
        dismissButton = {
            TonalButton(text = "取消", width = 96.dp, onClick = { ui.dismissDialog() })
        },
    )
}
