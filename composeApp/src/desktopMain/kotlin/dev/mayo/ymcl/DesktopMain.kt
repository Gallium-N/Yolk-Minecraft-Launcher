package dev.mayo.ymcl

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.engine.HmclEngine
import dev.mayo.ymcl.ui.YmclApp
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

fun main() = application {
    // Do not pin the initial size through WindowState: a tiling compositor may
    // map the window at 2446x1376 first, and re-applying 1280x800 during
    // recomposition is exactly what produces the "drawn only in the top-left"
    // symptom. Size is applied once below, after the minimum size is known.
    val windowState = rememberWindowState()

    Window(
        onCloseRequest = ::exitApplication,
        title = "YMCL",
        state = windowState,
        resizable = true,
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(1100, 700)
            window.setSize(1280, 800)
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
        }
        YmclApp(state = remember { AppState(HmclEngine()) })
    }
}
