package dev.mayo.ymcl.ui.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.ui.components.EmptyStateView
import dev.mayo.ymcl.ui.components.PageHeader

/**
 * P6 diagnostics is deferred (see HANDOFF §4). Until then this page is an
 * explicit "under development" placeholder — no fake conclusion, clues or
 * stack trace that could be mistaken for a real diagnosis. Uses the shared
 * EmptyStateView so every empty state in the app has one component.
 */
@Composable
fun DiagnosticsPage(state: AppState) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(title = "诊断", meta = "功能开发中")
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            EmptyStateView(
                title = "诊断功能开发中",
                message = "崩溃归因与 Mod 冲突定位会在后续版本提供",
                icon = Icons.Filled.Build,
            )
        }
    }
}
