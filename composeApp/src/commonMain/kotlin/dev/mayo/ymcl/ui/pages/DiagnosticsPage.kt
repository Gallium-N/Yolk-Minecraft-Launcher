package dev.mayo.ymcl.ui.pages

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.app.DiagnosticsContent
import dev.mayo.ymcl.app.DiagnosticClue
import dev.mayo.ymcl.ui.components.OutlinedPillButton
import dev.mayo.ymcl.ui.components.PageHeader
import dev.mayo.ymcl.ui.components.PrimaryButton
import dev.mayo.ymcl.ui.components.TonalButton

private val MONO = FontFamily.Monospace

@Composable
fun DiagnosticsPage(state: AppState) {
    val conclusion = DiagnosticsContent.conclusion

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // RW = clamp(340, 480, 0.367 * W_c); the left column takes the rest.
        val rightWidth = (maxWidth * 0.367f).coerceIn(340.dp, 480.dp)
        val leftWidth = maxWidth - 24.dp - rightWidth

        Column(Modifier.fillMaxWidth()) {
            PageHeader(title = "诊断", meta = conclusion.lastCrashMeta)

            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    ConclusionCard()
                    Spacer(Modifier.height(16.dp))
                    ActionRow(leftWidth)
                    Spacer(Modifier.height(16.dp))
                    CluesCard()
                    Spacer(Modifier.height(24.dp))
                    DiagnosticsFooter()
                }
                Spacer(Modifier.width(24.dp))
                RawStackTracePanel(modifier = Modifier.width(rightWidth))
            }
        }
    }
}

@Composable
private fun ConclusionCard() {
    val colors = MaterialTheme.colorScheme
    val conclusion = DiagnosticsContent.conclusion

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(196.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceContainerLow)
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Text(
            text = "可能原因",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = conclusion.causeLine1,
            fontSize = 22.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
        )
        Text(
            text = conclusion.causeLine2,
            fontSize = 22.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(104.dp)
                    .height(26.dp)
                    .clip(CircleShape)
                    .background(colors.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "置信度 ${conclusion.confidence}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "证据 ${conclusion.evidenceCount} 条 · 崩溃于 ${conclusion.crashTime} · ${conclusion.loader}",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionRow(leftWidth: Dp) {
    val first = leftWidth * 0.337f
    val second = leftWidth * 0.36f
    val third = (leftWidth - first - second - 32.dp).coerceAtLeast(0.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryButton(text = "禁用 flywheel-1.0.4", width = first, onClick = { })
        Spacer(Modifier.width(16.dp))
        TonalButton(text = "降级 embeddium → 0.6.1", width = second, onClick = { })
        Spacer(Modifier.width(16.dp))
        OutlinedPillButton(text = "复制诊断报告", width = third, onClick = { })
    }
}

@Composable
private fun CluesCard() {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(268.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceContainerLow)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = "其他线索",
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        DiagnosticsContent.clues.forEach { clue ->
            ClueRow(clue)
        }
    }
}

@Composable
private fun ClueRow(clue: DiagnosticClue) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 9.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.primary),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.padding(top = 2.dp)) {
            Text(
                text = clue.title,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = clue.detail,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = clue.source,
            modifier = Modifier.padding(top = 4.dp),
            fontFamily = MONO,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiagnosticsFooter() {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedPillButton(text = "保存为 Markdown", width = 228.dp, onClick = { })
        Spacer(Modifier.width(16.dp))
        OutlinedPillButton(text = "重新分析", width = 196.dp, onClick = { })
        Spacer(Modifier.width(24.dp))
        Text(
            text = "分析耗时 0.8s · 本地规则库 v3",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun RawStackTracePanel(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val stack = DiagnosticsContent.stackTrace

    Column(
        modifier = modifier
            .height(608.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 20.dp)
                .height(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "原始堆栈",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .width(210.dp)
                    .height(24.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stack.fileName,
                    fontFamily = MONO,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(36.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            stack.lines.forEachIndexed { index, line ->
                val highlighted = index in stack.highlightedLineIndexes
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (highlighted) {
                                colors.secondaryContainer.copy(alpha = 0.55f)
                            } else {
                                colors.surfaceContainerLow.copy(alpha = 0f)
                            },
                        ),
                ) {
                    // Narrow panels fold long stack lines; the vertical scroll
                    // above keeps them inside the fixed 608dp panel.
                    Text(
                        text = line,
                        fontFamily = MONO,
                        fontSize = 10.5.sp,
                        lineHeight = 22.sp,
                        color = if (highlighted) colors.onSecondaryContainer else colors.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.outlineVariant.copy(alpha = 0.5f)),
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = "自动定位",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            stack.located.forEachIndexed { index, line ->
                Text(
                    text = line,
                    fontFamily = MONO,
                    fontSize = if (index == 0) 11.sp else 10.5.sp,
                    lineHeight = 20.sp,
                    color = if (index == 0) colors.onSurfaceVariant else colors.onSurface,
                )
            }
        }
    }
}
