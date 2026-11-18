package dev.mayo.ymcl.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.mayo.ymcl.app.AppPage
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.ui.components.JavaInstallProgressDialog
import dev.mayo.ymcl.ui.components.JavaMissingDialog
import dev.mayo.ymcl.ui.components.LocalReduceMotion
import dev.mayo.ymcl.ui.motion.effects
import dev.mayo.ymcl.ui.motion.motionQaOverride
import dev.mayo.ymcl.ui.motion.rememberReduceMotion
import dev.mayo.ymcl.ui.motion.rememberSharedAxisSlidePx
import dev.mayo.ymcl.ui.motion.sharedAxisDirection
import dev.mayo.ymcl.ui.motion.sharedAxisTransform
import dev.mayo.ymcl.ui.pages.DiagnosticsPage
import dev.mayo.ymcl.ui.pages.GetContentPage
import dev.mayo.ymcl.ui.pages.HomePage
import dev.mayo.ymcl.ui.pages.ImportPage
import dev.mayo.ymcl.ui.pages.InstancesPage
import dev.mayo.ymcl.ui.pages.NewInstancePage
import dev.mayo.ymcl.ui.pages.SettingsPage
import dev.mayo.ymcl.ui.theme.YmclTheme

@Composable
fun YmclApp(state: AppState) {
    YmclTheme(themeMode = state.themeMode) {
        val colors = MaterialTheme.colorScheme
        CompositionLocalProvider(
            LocalReduceMotion provides (state.reduceMotion || motionQaOverride),
        ) {
            val reduceMotion = rememberReduceMotion()
            // The only auto-playing animation: one 200ms fade on first draw.
            var entered by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { entered = true }
            val enterAlpha by animateFloatAsState(
                targetValue = if (entered) 1f else 0f,
                animationSpec = effects(reduceMotion),
                label = "first-screen-fade",
            )
            val slidePx = rememberSharedAxisSlidePx()

            // Root content area: Box + padding only. NO scroll modifier here.
            // A page-level verticalScroll used to wrap this whole Row (nav + page),
            // so the mouse wheel pushed the Hero card and the nav rail together on
            // short/DPI-scaled windows. Pages now own their internal list/log scroll.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.surface)
                    .graphicsLayer { alpha = enterAlpha },
            ) {
                Row(Modifier.fillMaxSize()) {
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
                        if (reduceMotion) {
                            // Instant swap: no transition frame can exist.
                            PageContent(state.page, state)
                        } else {
                            AnimatedContent(
                                targetState = state.page,
                                transitionSpec = {
                                    sharedAxisTransform(
                                        reduceMotion = reduceMotion,
                                        direction = sharedAxisDirection(
                                            initialState.ordinal,
                                            targetState.ordinal,
                                        ),
                                        slidePx = slidePx,
                                    )
                                },
                                label = "page-shared-axis",
                            ) { page ->
                                PageContent(page, state)
                            }
                        }
                    }
                }
                // Global Java dialogs: missing-runtime prompt + install step ladder.
                JavaMissingDialog(state)
                JavaInstallProgressDialog(state)
            }
        }
    }
}

@Composable
private fun PageContent(page: AppPage, state: AppState) {
    when (page) {
        AppPage.HOME -> HomePage(state)
        AppPage.INSTANCES -> InstancesPage(state)
        AppPage.GET_CONTENT -> GetContentPage(state)
        AppPage.IMPORT -> ImportPage(state)
        AppPage.NEW_INSTANCE -> NewInstancePage(state)
        AppPage.DIAGNOSTICS -> DiagnosticsPage(state)
        AppPage.SETTINGS -> SettingsPage(state)
    }
}
