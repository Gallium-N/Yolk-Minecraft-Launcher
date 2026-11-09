package dev.mayo.ymcl.ui.pages

import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.app.ModpackPlan
import dev.mayo.ymcl.app.ModpackUiState
import dev.mayo.ymcl.app.ModpackUiState.Stage
import dev.mayo.ymcl.platform.pickModpackFile
import dev.mayo.ymcl.ui.components.IconCircle
import dev.mayo.ymcl.ui.components.OutlinedPillButton
import dev.mayo.ymcl.ui.components.PageHeader
import dev.mayo.ymcl.ui.components.PrimaryButton
import dev.mayo.ymcl.ui.components.SectionHeader
import dev.mayo.ymcl.ui.components.TonalButton
import dev.mayo.ymcl.ui.icons.YmclIcons

@Composable
fun ImportPage(state: AppState) {
    val ui = state.modpackUi
    Column(Modifier.fillMaxSize()) {
        PageHeader(title = "导入整合包", meta = "mrpack · CurseForge zip")
        when (ui.stage) {
            Stage.PICK -> PickCard(ui)
            Stage.CONFIRM -> ConfirmCard(ui)
            Stage.IMPORTING -> ImportingCard(ui)
            Stage.DONE -> DoneCard(state)
            Stage.ERROR -> ErrorCard(ui)
        }
    }
}

@Composable
private fun PickCard(ui: ModpackUiState) {
    val colors = MaterialTheme.colorScheme
    var pathDraft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("选择整合包文件")
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconCircle(
                    icon = YmclIcons.Folder,
                    tint = colors.primary,
                    background = colors.primaryContainer,
                    size = 56.dp,
                    iconSize = 28.dp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "把 .mrpack 或 CurseForge .zip 拖到窗口上",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "也可以点下面的按钮选择文件，或在输入框里粘贴路径",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                PrimaryButton(
                    text = "选择文件",
                    width = 148.dp,
                    onClick = {
                        val p = pickModpackFile()
                        if (p != null) {
                            pathDraft = p
                            ui.prepare(p)
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHigh)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (pathDraft.isEmpty()) {
                    Text("整合包路径（.mrpack / .zip）", fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
                BasicTextField(
                    value = pathDraft,
                    onValueChange = { pathDraft = it },
                    singleLine = true,
                    cursorBrush = SolidColor(colors.primary),
                    textStyle = TextStyle(fontSize = 13.sp, color = colors.onSurface),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedPillButton(text = "解析", width = 96.dp, onClick = { ui.prepare(pathDraft) })
        }
        ui.parseError?.let { message ->
            Spacer(Modifier.height(12.dp))
            ErrorBanner(message)
        }
        if (ui.parsing) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在解析整合包…", fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ConfirmCard(ui: ModpackUiState) {
    val colors = MaterialTheme.colorScheme
    val p = ui.plan ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("确认导入", hint = p.source.id)
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surfaceContainerLow)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Column {
                Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${p.mcVersion} · ${loaderDisplay(p)} · ${p.source.id}",
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                InfoRow("目标实例 ID", p.suggestedId)
                InfoRow("游戏版本", p.mcVersion)
                InfoRow("模组加载器", loaderDisplay(p))
                InfoRow("整合包版本", p.packVersion.ifBlank { "—" })
                InfoRow("文件数", "${p.files.size} 个")
                InfoRow("总大小", formatBytes(p.totalBytes))
                InfoRow("覆盖文件", "${p.overrides.size} 个")
                InfoRow("跳过（纯服务端）", "${p.skippedServerOnly} 个")
                InfoRow("不安全路径丢弃", "${p.droppedUnsafe} 个")
            }
        }
        if (p.warnings.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionHeader("警告", hint = "${p.warnings.size} 条")
            Spacer(Modifier.height(8.dp))
            p.warnings.forEach { w ->
                Text("• $w", fontSize = 12.sp, lineHeight = 18.sp, color = colors.error)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(text = "开始导入", width = 148.dp, onClick = { ui.start() })
            Spacer(Modifier.width(12.dp))
            TonalButton(text = "取消", width = 96.dp, onClick = { ui.reset() })
        }
    }
}

@Composable
private fun ImportingCard(ui: ModpackUiState) {
    val colors = MaterialTheme.colorScheme
    val pr = ui.progress
    val fraction = if (pr.totalFiles > 0) pr.doneFiles.toFloat() / pr.totalFiles.toFloat() else 0f
    Column(Modifier.fillMaxSize()) {
        SectionHeader("正在导入")
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surfaceContainerLow)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Column {
                Text(pr.stage, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                InfoRow("文件", "${pr.doneFiles} / ${pr.totalFiles}")
                InfoRow("字节", "${formatBytes(pr.doneBytes)} / ${formatBytes(pr.totalBytes)}")
                InfoRow("当前文件", pr.currentFile ?: "—")
            }
        }
        Spacer(Modifier.height(20.dp))
        TonalButton(text = "取消导入", width = 128.dp, onClick = { ui.cancel() })
        Spacer(Modifier.height(8.dp))
        Text(
            "取消会停止下载并清理 .part，已解压的目录保留并提示「未完成」。",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun DoneCard(state: AppState) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        SectionHeader("导入完成")
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.primaryContainer)
                .padding(24.dp),
        ) {
            Text(
                "整合包已导入，新实例状态为「就绪」。",
                fontSize = 15.sp,
                color = colors.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = "查看实例",
            width = 148.dp,
            onClick = { state.navigate(dev.mayo.ymcl.app.AppPage.INSTANCES) },
        )
    }
}

@Composable
private fun ErrorCard(ui: ModpackUiState) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        SectionHeader("导入未完成")
        Spacer(Modifier.height(12.dp))
        ui.error?.let { ErrorBanner(it) }
        if (ui.failures.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionHeader("失败清单", hint = "${ui.failures.size} 条")
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                ui.failures.forEach { f ->
                    Text("• $f", fontSize = 12.sp, lineHeight = 18.sp, color = colors.error)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        if (ui.warnings.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionHeader("警告", hint = "${ui.warnings.size} 条")
            Spacer(Modifier.height(6.dp))
            Column(Modifier.height(96.dp).verticalScroll(rememberScrollState())) {
                ui.warnings.forEach { w ->
                    Text("• $w", fontSize = 11.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(text = "重新选择", width = 148.dp, onClick = { ui.reset() })
    }
}

@Composable
private fun ErrorBanner(message: String) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.errorContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(message, fontSize = 12.sp, lineHeight = 18.sp, color = colors.onErrorContainer)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.width(140.dp))
        Text(value, fontSize = 13.sp, color = colors.onSurface, modifier = Modifier.weight(1f))
    }
}

private fun loaderDisplay(plan: ModpackPlan): String {
    val name = when (plan.loaderKind.lowercase()) {
        "fabric" -> "Fabric"
        "forge" -> "Forge"
        "neoforge" -> "NeoForge"
        "quilt" -> "Quilt"
        else -> "原版"
    }
    return if (plan.loaderVersion.isNullOrBlank()) name else "$name ${plan.loaderVersion}"
}

private fun formatBytes(n: Long): String = when {
    n >= 1_048_576L -> "%.1f MB".format(n / 1_048_576.0)
    n >= 1024L -> "%.1f KB".format(n / 1024.0)
    else -> "$n B"
}
