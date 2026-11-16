package dev.mayo.ymcl.ui.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.ui.components.PageHeader

/**
 * P6 diagnostics is deferred (see HANDOFF §4). Until then this page is an
 * explicit "under development" placeholder — no fake conclusion, clues or
 * stack trace that could be mistaken for a real diagnosis.
 */
@Composable
fun DiagnosticsPage(state: AppState) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        PageHeader(title = "诊断", meta = "功能开发中")
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "诊断功能开发中",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "崩溃归因与 Mod 冲突定位会在后续版本提供",
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
