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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mayo.ymcl.app.AppPage
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.app.BrokenInstall
import dev.mayo.ymcl.app.NewInstanceUiState
import dev.mayo.ymcl.app.NewInstanceUiState.Probe
import dev.mayo.ymcl.app.NewInstanceUiState.Step
import dev.mayo.ymcl.domain.LoaderKind
import dev.mayo.ymcl.domain.VersionEntry
import dev.mayo.ymcl.ui.components.ErrorBanner
import dev.mayo.ymcl.ui.components.InstallProgressCard
import dev.mayo.ymcl.ui.components.LoadingSkeleton
import dev.mayo.ymcl.ui.components.OutlinedPillButton
import dev.mayo.ymcl.ui.components.PageHeader
import dev.mayo.ymcl.ui.components.PrimaryButton
import dev.mayo.ymcl.ui.components.SectionHeader
import dev.mayo.ymcl.ui.components.TonalButton
import dev.mayo.ymcl.ui.motion.ExitBeforeRemove
import dev.mayo.ymcl.ui.motion.MotionVisibility
import dev.mayo.ymcl.ui.motion.rememberReduceMotion

/** Standalone wizard page (kept for the QA page entry and deep links). */
@Composable
fun NewInstancePage(state: AppState) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(title = "新建实例", meta = "安装游戏版本 · ${state.newInstanceUi.versions.size} 个版本")
        NewInstanceWizard(state)
    }
}

/**
 * The wizard body without its own page header, so the 获取内容 → 安装游戏版本
 * tab can embed the exact same flow (P0.2 §1). No install logic lives here.
 */
@Composable
fun NewInstanceWizard(state: AppState) {
    val ui = state.newInstanceUi
    val reduceMotion = rememberReduceMotion()
    // Scene 7: the exit animation runs while this id is set; the backend delete
    // is only triggered from the animation's finished callback.
    var exitingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (ui.versions.isEmpty() && !ui.loadingVersions) ui.loadVersions()
    }
    DeleteBrokenDialog(
        ui = ui,
        visible = exitingId == null,
        onConfirm = { exitingId = ui.pendingDeleteId },
    )
    val finishDelete = {
        ui.confirmDelete()
        exitingId = null
    }
    Column(Modifier.fillMaxSize()) {
        when (ui.step) {
            Step.VERSION -> VersionStep(ui, exitingId, reduceMotion, finishDelete)
            Step.LOADER -> LoaderStep(ui)
            Step.NAME -> NameStep(ui)
            Step.INSTALLING -> InstallingStep(ui, reduceMotion)
            Step.DONE -> DoneStep(state)
            Step.ERROR -> ErrorStep(ui, exitingId != null, reduceMotion, finishDelete)
        }
    }
}

@Composable
private fun VersionStep(
    ui: NewInstanceUiState,
    exitingId: String?,
    reduceMotion: Boolean,
    onDeleteExitFinished: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        SectionHeader("选择游戏版本")
        Spacer(Modifier.height(12.dp))
        BrokenInstallsCard(ui, exitingId, reduceMotion, onDeleteExitFinished)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchPill(ui.versionQuery, { ui.versionQuery = it }, "搜索版本", Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            OutlinedPillButton(
                text = if (ui.showAllTypes) "全部类型" else "仅正式版",
                width = 120.dp,
                onClick = { ui.showAllTypes = !ui.showAllTypes },
            )
        }
        Spacer(Modifier.height(12.dp))
        when {
            ui.loadingVersions -> LoadingSkeleton(rows = 3)
            ui.versionsError != null -> ErrorBanner(
                message = ui.versionsError!!,
                onRetry = { ui.loadVersions() },
            )
            // Version list scrolls INSIDE a weight(1f) bounded area; list-internal.
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                ui.visibleVersions.take(400).forEach { v ->
                    val selected = ui.selectedVersion?.id == v.id
                    VersionRow(
                        v = v,
                        selected = selected,
                        onClick = { ui.selectVersion(v) },
                    )
                }
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
private fun BrokenInstallsCard(
    ui: NewInstanceUiState,
    exitingId: String?,
    reduceMotion: Boolean,
    onExitFinished: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    MotionVisibility(visible = ui.brokenInstalls.isNotEmpty(), reduceMotion = reduceMotion) {
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
            Text(
                "删除会移除整个实例目录，不可恢复。",
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            ui.brokenInstalls.forEach { item ->
                ExitBeforeRemove(
                    exiting = exitingId == item.id,
                    reduceMotion = reduceMotion,
                    onExitFinished = onExitFinished,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BrokenInstallRow(item, onDelete = { ui.requestDelete(item.id) })
                }
            }
            ui.deleteError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 11.sp, color = colors.error)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
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
private fun DeleteBrokenDialog(ui: NewInstanceUiState, visible: Boolean, onConfirm: () -> Unit) {
    val id = ui.pendingDeleteId ?: return
    if (!visible) return
    val colors = MaterialTheme.colorScheme
    val item = ui.brokenInstalls.firstOrNull { it.id == id }
    AlertDialog(
        onDismissRequest = { ui.dismissDelete() },
        title = {
            Text("删除未完成的安装？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                Text("将永久删除该目录及已下载文件，不可撤销。", fontSize = 13.sp, color = colors.onSurface)
                Spacer(Modifier.height(10.dp))
                Text(item?.displayName ?: id, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                item?.let { Text(it.path, fontSize = 11.sp, color = colors.onSurfaceVariant) }
            }
        },
        confirmButton = {
            PrimaryButton(text = "确认删除", width = 116.dp, onClick = onConfirm)
        },
        dismissButton = {
            TonalButton(text = "取消", width = 88.dp, onClick = { ui.dismissDelete() })
        },
    )
}

@Composable
private fun VersionRow(v: VersionEntry, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            v.id,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            modifier = Modifier.weight(1f),
        )
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
    Column(Modifier.fillMaxSize()) {
        SelectedVersionBar(ui)
        Spacer(Modifier.height(16.dp))
        SectionHeader("加载器", hint = ui.loaderKind.displayName)
        Spacer(Modifier.height(12.dp))
        LoaderChips(ui)
        // The greyed-out NeoForge chip can never be clicked; surface the reason
        // here as a hint, never as the whole content area.
        if (!ui.neoForgeReachable) {
            Spacer(Modifier.height(8.dp))
            Text("NeoForge 官方源不可达，已置灰。", fontSize = 12.sp, color = colors.error)
        }
        Spacer(Modifier.height(16.dp))

        val probing = ui.probe == Probe.LOADING || ui.loadingLoaders
        when {
            probing -> LoadingSkeleton(rows = 3)
            ui.probeError.isNotEmpty() -> ErrorBanner(
                message = ui.probeError,
                onRetry = { ui.retryProbe() },
                onBack = { ui.backToVersions() },
            )
            ui.loaderStatus.isNotEmpty() -> ErrorBanner(
                message = ui.loaderStatus,
                onRetry = { ui.selectLoader(ui.loaderKind) },
                onBack = { ui.backToVersions() },
            )
            ui.probe == Probe.OK -> ProbeVersionsList(ui)
            ui.loaderVersions.isNotEmpty() -> LoaderVersionsList(ui)
            else -> Text("原版无需加载器版本。", fontSize = 12.sp, color = colors.onSurfaceVariant)
        }

        Spacer(Modifier.weight(1f))
        Row {
            TonalButton(text = "返回", width = 96.dp, onClick = { ui.backToVersions() })
            Spacer(Modifier.width(12.dp))
            PrimaryButton(text = "下一步", width = 148.dp, onClick = { ui.nextToName() })
        }
    }
}

/** Selected version + a spinner on the right while loaders are being probed. */
@Composable
private fun SelectedVersionBar(ui: NewInstanceUiState) {
    val colors = MaterialTheme.colorScheme
    val loading = ui.probe == Probe.LOADING || ui.loadingLoaders
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.secondaryContainer)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "已选 ${ui.selectedVersion?.id ?: "—"}",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun LoaderChips(ui: NewInstanceUiState) {
    val colors = MaterialTheme.colorScheme
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
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ProbeVersionsList(ui: NewInstanceUiState) {
    val colors = MaterialTheme.colorScheme
    Text("可用 Fabric 构建", fontSize = 12.sp, color = colors.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        ui.probeVersions.forEach { v ->
            Box(
                Modifier.fillMaxWidth().height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceContainerLow)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text(v, fontSize = 12.sp, color = colors.onSurface) }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.LoaderVersionsList(ui: NewInstanceUiState) {
    val colors = MaterialTheme.colorScheme
    Text("默认选最新稳定：${ui.selectedLoaderVersion ?: "—"}", fontSize = 12.sp, color = colors.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
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

@Composable
private fun NameStep(ui: NewInstanceUiState) {
    Column(Modifier.fillMaxSize()) {
        SectionHeader("实例名", hint = ui.loaderKind.displayName)
        Spacer(Modifier.height(16.dp))
        SearchPill(ui.displayName, { ui.updateName(it) }, "显示名称", Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        InfoRow("instanceId", ui.instanceId)
        InfoRow("目标目录", ui.targetDir)
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
private fun InstallingStep(ui: NewInstanceUiState, reduceMotion: Boolean) {
    // Scene 7 (cancel): the progress card fades + shrinks out BEFORE ui.cancel()
    // stops the download and marks the install as unfinished.
    var cancelExiting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        SectionHeader("正在安装", hint = ui.instanceId)
        Spacer(Modifier.height(16.dp))
        ExitBeforeRemove(
            exiting = cancelExiting,
            reduceMotion = reduceMotion,
            onExitFinished = { ui.cancel() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            InstallProgressCard(
                stageName = ui.stage.name,
                done = ui.stage.done,
                total = ui.stage.total,
                bytes = ui.stage.bytes,
                totalBytes = ui.stage.totalBytes,
                current = ui.stage.currentFile.takeIf { it.isNotBlank() },
                cancelLabel = "取消安装",
                onCancel = { cancelExiting = true },
            )
        }
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
            Text("实例已就绪，跳转到实例页。", fontSize = 15.sp, color = colors.onPrimaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(text = "查看实例", width = 148.dp, onClick = { state.navigate(AppPage.INSTANCES) })
    }
}

@Composable
private fun ErrorStep(
    ui: NewInstanceUiState,
    exiting: Boolean,
    reduceMotion: Boolean,
    onExitFinished: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ExitBeforeRemove(
        exiting = exiting,
        reduceMotion = reduceMotion,
        onExitFinished = onExitFinished,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            SectionHeader("安装未完成")
            Spacer(Modifier.height(12.dp))
            ui.error?.let { ErrorBanner(message = it) }
            if (ui.failures.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SectionHeader("失败清单", hint = "${ui.failures.size} 条")
                // Failure list scrolls INSIDE a weight(1f) bounded area; list-internal.
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    ui.failures.forEach { Text("• $it", fontSize = 12.sp, color = colors.error) }
                }
            }
            Spacer(Modifier.weight(1f))
            Row {
                PrimaryButton(text = "继续安装", width = 148.dp, onClick = { ui.start(resume = true) })
                Spacer(Modifier.width(12.dp))
                TonalButton(text = "重新选择", width = 120.dp, onClick = { ui.reset() })
            }
            ui.deleteError?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 11.sp, color = colors.error)
            }
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
private fun InfoRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.width(230.dp))
        Text(value, fontSize = 13.sp, color = colors.onSurface, modifier = Modifier.weight(1f))
    }
}
