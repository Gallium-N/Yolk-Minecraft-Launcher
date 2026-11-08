package dev.mayo.ymcl

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.app.LauncherSettings
import dev.mayo.ymcl.engine.HmclEngine
import dev.mayo.ymcl.platform.FileInstanceRepository
import dev.mayo.ymcl.platform.appDataDirectory
import dev.mayo.ymcl.ui.YmclApp
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

fun main() = application {
    val appRoot = appDataDirectory()
    // Shared game root. -Dymcl.game.root / YMCL_GAME_ROOT override it for QA
    // (e.g. pointing at the P4 built instances); otherwise use the setting.
    val gameRoot = System.getProperty("ymcl.game.root")
        ?: System.getenv("YMCL_GAME_ROOT")
        ?: LauncherSettings(appRoot).gameRootPath()
    val isolationEnabled = LauncherSettings(appRoot).isolationEnabled()

    // Hoisted so the focus listener and QA hook can reach the same state.
    val state = remember {
        AppState(
            engine = HmclEngine(appRoot),
            repositoryFactory = { root -> FileInstanceRepository(root, isolationEnabled) },
            gameRoot = gameRoot,
            isolationEnabled = isolationEnabled,
        )
    }

    val width = System.getProperty("ymcl.window.width")?.toIntOrNull() ?: 1280
    val height = System.getProperty("ymcl.window.height")?.toIntOrNull() ?: 800
    val autoLaunch = System.getProperty("ymcl.qa.autolaunch")

    // Do not pin the size through WindowState: a tiling compositor may map the
    // window larger first and re-applying a size would draw only in the corner.
    val windowState = rememberWindowState()

    Window(
        onCloseRequest = ::exitApplication,
        title = "YMCL",
        state = windowState,
        resizable = true,
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(1100, 700)
            window.setSize(width, height)
            window.setLocationRelativeTo(null)
        }
        LaunchedEffect(window) {
            window.addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    // XWayland + tiling WMs can resize the peer without the AWT
                    // content pane being revalidated; force layout + repaint.
                    window.contentPane.revalidate()
                    window.contentPane.repaint()
                }
            })
            // Refresh the real instance list when the window regains focus.
            window.addWindowFocusListener(object : WindowAdapter() {
                override fun windowGainedFocus(e: WindowEvent?) {
                    state.refresh()
                }
            })
        }
        // QA-only: exercise the exact same AppState.launch path a button uses,
        // without a physical click. Off for normal runs and screenshots.
        LaunchedEffect(autoLaunch) {
            autoLaunch?.takeIf { it.isNotBlank() }?.let { state.launch(it) }
        }
        YmclApp(state = state)
    }
}
