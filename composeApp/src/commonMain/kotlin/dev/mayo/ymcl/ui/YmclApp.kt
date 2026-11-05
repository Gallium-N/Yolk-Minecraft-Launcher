package dev.mayo.ymcl.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mayo.ymcl.app.AppPage
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.ui.components.LocalReduceMotion
import dev.mayo.ymcl.ui.pages.DiagnosticsPage
import dev.mayo.ymcl.ui.pages.HomePage
import dev.mayo.ymcl.ui.pages.InstancesPage
import dev.mayo.ymcl.ui.pages.SettingsPage
import dev.mayo.ymcl.ui.theme.YmclTheme

@Composable
fun YmclApp(state: AppState) {
    YmclTheme(themeMode = state.themeMode) {
        val colors = MaterialTheme.colorScheme
        CompositionLocalProvider(LocalReduceMotion provides state.reduceMotion) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.surface),
            ) {
                // Below the supported minimum (1100x700, enforced by the AWT window)
                // fall back to a scrollable 1100x700 canvas instead of squeezing or
                // clipping the layout. Normal sizes never get these scroll modifiers.
                val needsHorizontal = maxWidth < 1100.dp
                val needsVertical = maxHeight < 700.dp
                val horizontal = rememberScrollState()
                val vertical = rememberScrollState()
                val canvasWidth = if (needsHorizontal) 1100.dp else maxWidth
                val canvasHeight = if (needsVertical) 700.dp else maxHeight

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (needsHorizontal) Modifier.horizontalScroll(horizontal) else Modifier)
                        .then(if (needsVertical) Modifier.verticalScroll(vertical) else Modifier),
                ) {
                    Row(
                        modifier = Modifier
                            .width(canvasWidth)
                            .height(canvasHeight)
                            .background(colors.surface),
                    ) {
                        YmclNavigationRail(
                            current = state.page,
                            onSelect = state::navigate,
                        )
                        // W_c = window width - 88 (rail) - 48 (24dp on each side).
                        // This box deliberately has no width cap: pages reflow with it.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                        ) {
                            Crossfade(
                                targetState = state.page,
                                animationSpec = if (state.reduceMotion) snap() else tween(durationMillis = 200),
                                label = "page-crossfade",
                            ) { page ->
                                when (page) {
                                    AppPage.HOME -> HomePage(state)
                                    AppPage.INSTANCES -> InstancesPage(state)
                                    AppPage.DIAGNOSTICS -> DiagnosticsPage(state)
                                    AppPage.SETTINGS -> SettingsPage(state)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
