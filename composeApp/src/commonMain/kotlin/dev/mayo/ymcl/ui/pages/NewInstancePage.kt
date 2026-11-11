package dev.mayo.ymcl.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.app.BrokenInstall
import dev.mayo.ymcl.app.NewInstanceUiState
import dev.mayo.ymcl.app.NewInstanceUiState.Step
import dev.mayo.ymcl.domain.LoaderKind
import dev.mayo.ymcl.domain.VersionEntry
import dev.mayo.ymcl.ui.components.InstallProgressCard
import dev.mayo.ymcl.ui.components.OutlinedPillButton
import dev.mayo.ymcl.ui.components.PageHeader
import dev.mayo.ymcl.ui.components.PrimaryButton
import dev.mayo.ymcl.ui.components.SectionHeader
import dev.mayo.ymcl.ui.components.TonalButton

@Composable
fun NewInstancePage(state: AppState) {
    val ui = state.newInstanceUi
    LaunchedEffect(Unit) { if (ui.versions.isEmpty()) ui.loadVersions() }
    DeleteBrokenDialog(ui)
    Column(Modifier.fillMaxSize()) {
        PageHeader(title = "新建实例", meta = "安装游戏版本 · ${ui.versions.size} 个版本")
        when (ui.step) {
            Step.VERSION -> VersionStep(ui)
            Step.LOADER -> LoaderStep(ui)
            Step.NAME -> NameStep(ui)
            Step.INSTALLING -> InstallingStep(ui)
            Step.DONE -> DoneStep(state)
            Step.ERROR -> ErrorStep(ui)
        }
    }
}

@Composable
private fun VersionStep(ui: NewInstanceUiState) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        SectionHeader("选择游戏版本", hint = "来源：Mojang 版本清单（含缓存 / 离线回退）")
        Spacer(Modifier.height(12.dp))
        BrokenInstallsCard(ui)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchPill(ui.versionQuery, { ui.versionQuery = it }, "搜索版本号，例如 1.20.1", Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            OutlinedPillButton(
                text = if (ui.showAllTypes) "全部类型" else "仅正式版",
                width = 120.dp,
                onClick = { ui.showAllTypes = !ui.showAllTypes },
            )
        }
        Spacer(Modifier.height(12.dp))
        when {
            ui.loadingVersions -> LoadingRow("正在拉取版本清单…")
            ui.versionsError != null -> ErrorBox(ui.versionsError!!, onRetry = { ui.loadVersions() })
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                ui.visibleVersions.take(400).forEach { v -> VersionRow(v, onClick = { ui.selectVersion(v) }) }
                if (ui.visibleVersions.isEmpty()) {
                    Text("没有匹配的版本", fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Unfinished installs (`installState: broken`) left by a cancelled/failed run.
 * They can be resumed from the error step, or discarded here with confirmation.
 */
@Composable
private fun BrokenInstallsCard(ui: NewInstanceUiState) {
    val colors = MaterialTheme.colorScheme
    if (ui.brokenInstalls.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(colors.errorContainer.copy(alpha = 0.18f))
            .border(1.dp, colors.error.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            "未完成的安装 · ${ui.brokenInstalls.size} 个",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text("已下载文件可在下次安装时复用；删除会移除整个实例目录，不可恢复。", fontSize = 11.sp, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        ui.brokenInstalls.forEach { BrokenInstallRow(it, onDelete = { ui.requestDelete(it.id) }) }
        ui.deleteError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 11.sp, color = colors.error)
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun BrokenInstallRow(item: BrokenInstall, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.displayName, fontSize = 13.sp, color = colors.onSurface)
            Text(item.path, fontSize = 10.sp, color = colors.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        OutlinedPillButton(
            text = "删除",
            width = 72.dp,
            height = 32.dp,
            borderColor = colors.error.copy(alpha = 0.6f),
            onClick = onDelete,
        )
    }
}

/** Confirmation shown before deleting one unfinished install. */
@Composable
private fun DeleteBrokenDialog(ui: NewInstanceUiState) {
    val id = ui.pendingDeleteId ?: return
    val colors = MaterialTheme.colorScheme
    val item = ui.brokenInstalls.firstOrNull { it.id == id }
    AlertDialog(
        onDismissRequest = { ui.dismissDelete() },
        title = {
            Text("删除未完成的安装？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                Text("将永久删除这个未完成安装的实例目录及其已下载文件，此操作不可撤销。", fontSize = 13.sp, color = colors.onSurface)
                Spacer(Modifier.height(10.dp))
                Text(item?.displayName ?: id, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                item?.let { Text(it.path, fontSize = 11.sp, color = colors.onSurfaceVariant) }
            }
        },
        confirmButton = {
            PrimaryButton(text = "确认删除", width = 116.dp, onClick = { ui.confirmDelete() })
        },
        dismissButton = {
            TonalButton(text = "取消", width = 88.dp, onClick = { ui.dismissDelete() })
        },
    )
}

@Composable
private fun VersionRow(v: VersionEntry, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(v.id, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface, modifier = Modifier.weight(1f))
        TypeBadge(v.type)
        Spacer(Modifier.width(12.dp))
        Text(v.releaseTime.take(10), fontSize = 11.sp, color = colors.onSurfaceVariant)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun TypeBadge(type: String) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) { Text(type, fontSize = 10.sp, color = colors.onSurfaceVariant) }
}

@Composable
private fun LoaderStep(ui: NewInstanceUiState) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("加载器（可选）", hint = ui.selectedVersion?.id ?: "")
        Spacer(Modifier.height(16.dp))
        Row {
            LoaderKind.entries.forEach { kind ->
                val disabled = kind == LoaderKind.NEOFORGE && !ui.neoForgeReachable
                val selected = ui.loaderKind == kind
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                disabled -> colors.surfaceContainerLow
                                selected -> colors.primary
                                else -> colors.surfaceContainerHigh
                            },
                        )
                        .clickable(enabled = !disabled) { ui.selectLoader(kind) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        kind.displayName,
                        fontSize = 12.sp,
                        color = when {
                            disabled -> colors.outlineVariant
                            selected -> colors.onPrimary
                            else -> colors.onSurface
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // The disabled NeoForge chip can never be clicked, so surface the reason
        // here instead of only setting it from selectLoader (acceptance #17).
        if (!ui.neoForgeReachable) {
            Text(
                "NeoForge 官方源不可达（已探测），请检查网络或代理；该选项已置灰。",
                fontSize = 12.sp,
                color = colors.error,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (ui.loaderStatus.isNotEmpty()) {
            Text(ui.loaderStatus, fontSize = 12.sp, color = colors.error)
            Spacer(Modifier.height(8.dp))
        }
        if (ui.loadingLoaders) LoadingRow("正在拉取 ${ui.loaderKind.displayName} 版本列表…")
        if (ui.loaderKind != LoaderKind.VANILLA && ui.loaderVersions.isNotEmpty()) {
            Text("默认选最新稳定：${ui.selectedLoaderVersion ?: "—"}", fontSize = 12.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Column {
                ui.loaderVersions.take(8).forEach { v ->
                    Row(
                        Modifier.fillMaxWidth().height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (v == ui.selectedLoaderVersion) colors.secondaryContainer else colors.surfaceContainerLow)
                            .clickable { ui.selectLoaderVersion(v) }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Text(v, fontSize = 12.sp, color = colors.onSurface) }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(text = "下一步：实例名", width = 168.dp, onClick = { ui.nextToName() })
    }
}

@Composable
private fun NameStep(ui: NewInstanceUiState) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("实例名", hint = ui.loaderKind.displayName)
        Spacer(Modifier.height(16.dp))
        SearchPill(ui.displayName, { ui.updateName(it) }, "显示名称", Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        InfoRow("instanceId（冲突自动递增）", ui.instanceId)
        InfoRow("目标目录（只读）", ui.targetDir)
        InfoRow("游戏版本", ui.selectedVersion?.id ?: "—")
        InfoRow("加载器", "${ui.loaderKind.displayName} ${ui.selectedLoaderVersion ?: ""}".trim())
        Spacer(Modifier.height(24.dp))
        Row {
            TonalButton(text = "上一步", width = 96.dp, onClick = { ui.back() })
            Spacer(Modifier.width(12.dp))
            PrimaryButton(text = "开始安装", width = 148.dp, onClick = { ui.start() })
        }
    }
}

@Composable
private fun InstallingStep(ui: NewInstanceUiState) {
    Column(Modifier.fillMaxSize()) {
        SectionHeader("正在安装", hint = ui.instanceId)
        Spacer(Modifier.height(16.dp))
        InstallProgressCard(
            stageName = ui.stage.name,
            done = ui.stage.done,
            total = ui.stage.total,
            bytes = ui.stage.bytes,
            current = null,
            cancelLabel = "取消安装",
            onCancel = { ui.cancel() },
        )
    }
}

@Composable
private fun DoneStep(state: AppState) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        SectionHeader("安装完成")
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(colors.primaryContainer).padding(24.dp),
        ) {
            Text("实例已就绪，正在跳转到实例页。", fontSize = 15.sp, color = colors.onPrimaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(text = "查看实例", width = 148.dp, onClick = { state.navigate(dev.mayo.ymcl.app.AppPage.INSTANCES) })
    }
}

@Composable
private fun ErrorStep(ui: NewInstanceUiState) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        SectionHeader("安装未完成")
        Spacer(Modifier.height(12.dp))
        ui.error?.let { ErrorBox(it, onRetry = null) }
        if (ui.failures.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionHeader("失败清单", hint = "${ui.failures.size} 条")
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                ui.failures.forEach { Text("• $it", fontSize = 12.sp, color = colors.error) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row {
            PrimaryButton(text = "继续安装", width = 148.dp, onClick = { ui.start(resume = true) })
            Spacer(Modifier.width(12.dp))
            TonalButton(text = "重新选择", width = 120.dp, onClick = { ui.reset() })
            Spacer(Modifier.width(12.dp))
            OutlinedPillButton(
                text = "删除未完成的安装",
                width = 148.dp,
                borderColor = colors.error.copy(alpha = 0.6f),
                onClick = { ui.requestDelete(ui.lastInstanceId) },
            )
        }
        ui.deleteError?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 11.sp, color = colors.error)
        }
    }
}

@Composable
private fun SearchPill(value: String, onValueChange: (String) -> Unit, hint: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.height(44.dp).clip(CircleShape)
            .background(colors.surfaceContainerHigh).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, fontSize = 13.sp, color = colors.onSurfaceVariant)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                cursorBrush = SolidColor(colors.primary),
                textStyle = TextStyle(fontSize = 13.sp, color = colors.onSurface),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LoadingRow(text: String) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun ErrorBox(message: String, onRetry: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(colors.errorContainer).padding(16.dp),
    ) {
        Column {
            Text(message, fontSize = 12.sp, lineHeight = 18.sp, color = colors.onErrorContainer)
            if (onRetry != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedPillButton(text = "重试", width = 96.dp, onClick = onRetry)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.width(230.dp))
        Text(value, fontSize = 13.sp, color = colors.onSurface, modifier = Modifier.weight(1f))
    }
}
