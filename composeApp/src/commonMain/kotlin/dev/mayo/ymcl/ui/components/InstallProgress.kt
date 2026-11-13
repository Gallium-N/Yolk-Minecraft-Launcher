package dev.mayo.ymcl.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mayo.ymcl.platform.nowMillis
import dev.mayo.ymcl.ui.motion.formatEta
import dev.mayo.ymcl.ui.motion.formatSpeed
import dev.mayo.ymcl.ui.motion.progressFraction
import dev.mayo.ymcl.ui.motion.rememberDownloadRateMeter
import dev.mayo.ymcl.ui.motion.rememberReduceMotion
import dev.mayo.ymcl.ui.motion.rememberStageTitleSlidePx
import dev.mayo.ymcl.ui.motion.spatial
import dev.mayo.ymcl.ui.motion.stageTitleTransform

/** Shared by the modpack import and the new-instance wizard (section G). */
@Composable
fun InstallProgressCard(
    stageName: String,
    done: Int,
    total: Int,
    bytes: Long,
    totalBytes: Long = 0L,
    current: String?,
    cancelLabel: String = "取消导入",
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val reduceMotion = rememberReduceMotion()
    val slidePx = rememberStageTitleSlidePx()
    // Real byte fraction drives the bar, so it glides continuously while files
    // download instead of sitting frozen between the 7 stage ticks.
    val fraction = progressFraction(done, total, bytes, totalBytes)
    // Monotonic target: a downloader reset() must never pull the bar backwards.
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(fraction) { if (fraction > target) target = fraction }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = spatial(reduceMotion),
        label = "install-progress",
    )
    val meter = rememberDownloadRateMeter()
    LaunchedEffect(bytes, totalBytes) { meter.update(bytes, nowMillis()) }
    val speed = formatSpeed(meter.bytesPerSec)
    val eta = formatEta(totalBytes - bytes, meter.bytesPerSec)
    val downloaded = if (totalBytes > 0L) {
        "${formatBytes(bytes)} / ${formatBytes(totalBytes)}"
    } else {
        formatBytes(bytes)
    }

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surfaceContainerLow)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Column {
                AnimatedContent(
                    targetState = stageName,
                    transitionSpec = { stageTitleTransform(reduceMotion, slidePx) },
                    label = "stage-title",
                ) { name ->
                    Text(
                        name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                ProgressRow("阶段进度", "$done / $total")
                ProgressRow("已下载", downloaded)
                AnimatedValueRow("下载速度", speed, reduceMotion, slidePx)
                AnimatedValueRow("预计剩余", eta, reduceMotion, slidePx)
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

/** Data-driven value change: fades + slides when the live number changes. */
@Composable
private fun AnimatedValueRow(label: String, value: String, reduceMotion: Boolean, slidePx: Int) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.width(140.dp))
        AnimatedContent(
            targetState = value,
            transitionSpec = { stageTitleTransform(reduceMotion, slidePx) },
            modifier = Modifier.weight(1f),
            label = "progress-live-value",
        ) { v ->
            Text(v, fontSize = 13.sp, color = colors.onSurface, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun formatBytes(n: Long): String = when {
    n >= 1_048_576L -> "%.1f MB".format(n / 1_048_576.0)
    n >= 1024L -> "%.1f KB".format(n / 1024.0)
    else -> "$n B"
}
