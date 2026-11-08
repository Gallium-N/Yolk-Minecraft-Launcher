package dev.mayo.ymcl

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.mayo.ymcl.app.AppPage
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.app.LauncherSettings
import dev.mayo.ymcl.app.ModInstaller
import dev.mayo.ymcl.app.ModrinthClient
import dev.mayo.ymcl.app.ModrinthService
import dev.mayo.ymcl.engine.HmclEngine
import dev.mayo.ymcl.platform.FileInstanceRepository
import dev.mayo.ymcl.platform.HttpDownloadTransport
import dev.mayo.ymcl.platform.ModrinthMetaFetcher
import dev.mayo.ymcl.platform.NativeDownloadFileSystem
import dev.mayo.ymcl.platform.appDataDirectory
import dev.mayo.ymcl.ui.YmclApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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

    // Modrinth stack: platform fetcher (UA + 429) -> client (JSON/bytes via the
    // existing MetaFetcher/DownloadTransport interfaces) -> resolver -> installer.
    val source = LauncherSettings(appRoot).source()
    val modrinthFetcher = ModrinthMetaFetcher(log = ::println)
    val modrinthFs = NativeDownloadFileSystem(gameRoot)
    val modrinthClient = ModrinthClient(source, modrinthFetcher, HttpDownloadTransport(), ::println)
    val modrinthInstaller = ModInstaller(modrinthFs, { url -> modrinthClient.file(url) }, ::println)
    val modrinthService = ModrinthService(modrinthClient, modrinthInstaller, ::println)

    // Hoisted so the focus listener and QA hook can reach the same state.
    val state = remember {
        AppState(
            engine = HmclEngine(appRoot),
            repositoryFactory = { root -> FileInstanceRepository(root, isolationEnabled) },
            gameRoot = gameRoot,
            isolationEnabled = isolationEnabled,
            modrinth = modrinthService,
        )
    }

    val width = System.getProperty("ymcl.window.width")?.toIntOrNull() ?: 1280
    val height = System.getProperty("ymcl.window.height")?.toIntOrNull() ?: 800
    val autoLaunch = System.getProperty("ymcl.qa.autolaunch")
    val qaInstance = System.getProperty("ymcl.qa.modrinth.instance")
    val qaSearch = System.getProperty("ymcl.qa.modrinth.search")
    val qaDialog = System.getProperty("ymcl.qa.modrinth.dialog")
    val qaInstall = System.getProperty("ymcl.qa.modrinth.install")

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
        // QA-only: drive the real 获取内容 page through its normal state methods.
        // Off unless -Dymcl.qa.modrinth.search is set.
        LaunchedEffect(qaSearch, qaDialog, qaInstall) {
            if (qaSearch == null) return@LaunchedEffect
            qaInstance?.let { state.modrinthUi.selectedInstanceId = it }
            state.modrinthUi.query = qaSearch
            state.navigate(AppPage.GET_CONTENT)
            withTimeoutOrNull(45_000) { while (!state.modrinthUi.loading) delay(50) }
            withTimeoutOrNull(45_000) { while (state.modrinthUi.loading) delay(200) }
            delay(500)
            if (qaDialog == "1") {
                val row = state.modrinthUi.results.firstOrNull { it.hit.slug == qaSearch }
                    ?: state.modrinthUi.results.firstOrNull()
                row?.let { state.modrinthUi.openInstall(it) }
                withTimeoutOrNull(45_000) { while (state.modrinthUi.loading) delay(200) }
                delay(500)
            }
            if (qaInstall == "1") {
                withTimeoutOrNull(45_000) { while (state.modrinthUi.dialog == null) delay(200) }
                delay(300)
                state.modrinthUi.confirmInstall()
            }
        }
        YmclApp(state = state)
    }
}
