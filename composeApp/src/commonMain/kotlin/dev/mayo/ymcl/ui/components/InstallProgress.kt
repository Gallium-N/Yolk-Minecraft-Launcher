package dev.mayo.ymcl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared by the modpack import and the new-instance wizard (section G). */
@Composable
fun InstallProgressCard(
    stageName: String,
    done: Int,
    total: Int,
    bytes: Long,
    current: String?,
    cancelLabel: String = "取消导入",
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fraction = if (total > 0) done.toFloat() / total.toFloat() else 0f
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surfaceContainerLow)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Column {
                Text(stageName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                ProgressRow("阶段进度", "$done / $total")
                ProgressRow("已下载", formatBytes(bytes))
                ProgressRow("当前文件", current ?: "—")
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TonalButton(text = cancelLabel, width = 128.dp, onClick = onCancel)
            Spacer(Modifier.width(12.dp))
            Text(
                "取消会停止下载并清理 .part，已下文件保留，下次安装复用。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProgressRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.width(140.dp))
        Text(value, fontSize = 13.sp, color = colors.onSurface, modifier = Modifier.weight(1f))
    }
}

private fun formatBytes(n: Long): String = when {
    n >= 1_048_576L -> "%.1f MB".format(n / 1_048_576.0)
    n >= 1024L -> "%.1f KB".format(n / 1024.0)
    else -> "$n B"
}
