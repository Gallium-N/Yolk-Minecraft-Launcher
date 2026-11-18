package dev.mayo.ymcl

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.mayo.ymcl.app.AppPage
import dev.mayo.ymcl.app.AppState
import dev.mayo.ymcl.app.DesktopJavaInstallOps
import dev.mayo.ymcl.app.LauncherSettings
import dev.mayo.ymcl.app.MetaFetcher
import dev.mayo.ymcl.app.MetaRepository
import dev.mayo.ymcl.app.ModInstaller
import dev.mayo.ymcl.app.ModpackBackendImpl
import dev.mayo.ymcl.app.ModpackUiState
import dev.mayo.ymcl.app.ModrinthClient
import dev.mayo.ymcl.app.ModrinthService
import dev.mayo.ymcl.app.NewInstanceBackendImpl
import dev.mayo.ymcl.domain.LoaderKind
import dev.mayo.ymcl.engine.HmclEngine
import dev.mayo.ymcl.platform.FileInstanceRepository
import dev.mayo.ymcl.platform.FileMetaStore
import dev.mayo.ymcl.platform.HttpCfFetcher
import dev.mayo.ymcl.platform.HttpDownloadTransport
import dev.mayo.ymcl.platform.HttpMetaFetcher
import dev.mayo.ymcl.platform.ModrinthMetaFetcher
import dev.mayo.ymcl.platform.NativeDownloadFileSystem
import dev.mayo.ymcl.platform.ZipModpackArchive
import dev.mayo.ymcl.platform.appDataDirectory
import dev.mayo.ymcl.ui.YmclApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File

fun main() = application {
    val appRoot = appDataDirectory()
    val settings = LauncherSettings(appRoot)
    // Shared game root. -Dymcl.game.root / YMCL_GAME_ROOT override it for QA.
    val gameRoot = System.getProperty("ymcl.game.root")
        ?: System.getenv("YMCL_GAME_ROOT")
        ?: settings.gameRootPath()
    val isolationEnabled = settings.isolationEnabled()
    val source = settings.source()

    // ---- P4 Modrinth stack ----
    val modrinthFetcher = ModrinthMetaFetcher(log = ::println)
    val modrinthFs = NativeDownloadFileSystem(gameRoot)
    val transport = HttpDownloadTransport()
    val modrinthClient = ModrinthClient(source, modrinthFetcher, transport, ::println)
    val modrinthInstaller = ModInstaller(modrinthFs, { url -> modrinthClient.file(url) }, ::println)
    val modrinthService = ModrinthService(modrinthClient, modrinthInstaller, ::println)

    // ---- P5 modpack stack ----
    // now=0 keeps the P3 meta caches fresh, so the base install makes no meta
    // network calls except the pinned Fabric profile when it is not cached yet.
    // QA-only "断源": make network meta fetches fail while leaving the disk
    // cache intact, so the loader-list cache hit / 30s error paths can be
    // verified. Reuses the existing ymcl.qa.loader.unreachable switch (no new one).
    val metaFetcher: MetaFetcher = if (System.getProperty("ymcl.qa.loader.unreachable") == "1") {
        MetaFetcher { throw dev.mayo.ymcl.app.MetaFetchException("loader source unreachable (QA 断源)") }
    } else {
        HttpMetaFetcher()
    }
    val meta = MetaRepository(appRoot, source, FileMetaStore(), metaFetcher) { 0L }
    val packFs = NativeDownloadFileSystem(gameRoot)
    val loaderProcess = dev.mayo.ymcl.app.DesktopLoaderProcess(::println)
    val modpack = ModpackBackendImpl(
        gameRoot = gameRoot,
        source = source,
        fs = packFs,
        meta = meta,
        transport = transport,
        archiveFactory = { ZipModpackArchive(it) },
        cfFetcher = HttpCfFetcher(::println),
        apiKey = { settings.curseForgeApiKey() },
        proxyEnabled = { settings.allowCurseToolsProxy() },
        log = ::println,
        process = loaderProcess,
    )
    val newInstance = NewInstanceBackendImpl(
        gameRoot = gameRoot,
        source = source,
        meta = meta,
        transport = transport,
        process = loaderProcess,
        log = ::println,
    )

    // Hoisted so the focus listener, drop target and QA hooks can reach the same state.
    val state = remember {
        AppState(
            engine = HmclEngine(appRoot),
            repositoryFactory = { root -> FileInstanceRepository(root, isolationEnabled) },
            gameRoot = gameRoot,
            isolationEnabled = isolationEnabled,
            modrinth = modrinthService,
            modpack = modpack,
            newInstance = newInstance,
            javaInstaller = DesktopJavaInstallOps(appRoot, source, settings.load().preferBmclapi, ::println),
            initialCurseForgeApiKey = settings.curseForgeApiKey(),
            initialAllowCurseToolsProxy = settings.allowCurseToolsProxy(),
            loaderProbeTimeoutMs = System.getProperty("ymcl.qa.loader.timeoutMs")?.toLongOrNull() ?: 30_000L,
            persistModpackSettings = { key, proxy -> settings.saveModpackSettings(key, proxy) },
        )
    }

    val width = System.getProperty("ymcl.window.width")?.toIntOrNull() ?: 1280
    val height = System.getProperty("ymcl.window.height")?.toIntOrNull() ?: 800
    val autoLaunch = System.getProperty("ymcl.qa.autolaunch")
    val qaInstance = System.getProperty("ymcl.qa.modrinth.instance")
    val qaSearch = System.getProperty("ymcl.qa.modrinth.search")
    val qaDialog = System.getProperty("ymcl.qa.modrinth.dialog")
    val qaInstall = System.getProperty("ymcl.qa.modrinth.install")
    val qaPackFile = System.getProperty("ymcl.qa.modpack.file")
    val qaPackStart = System.getProperty("ymcl.qa.modpack.start") == "1"
    val qaNewVersion = System.getProperty("ymcl.qa.newinstance.version")
    val qaNewLoader = System.getProperty("ymcl.qa.newinstance.loader")
    val qaNewStart = System.getProperty("ymcl.qa.newinstance.start") == "1"
    val qaPage = System.getProperty("ymcl.qa.page")
    val qaGetContentTab = System.getProperty("ymcl.qa.getcontent.tab")?.toIntOrNull()

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
            // Drag a .mrpack/.zip onto the window -> open the import wizard.
            try {
                window.dropTarget = object : DropTarget() {
                    override fun drop(event: DropTargetDropEvent) {
                        try {
                            event.acceptDrop(DnDConstants.ACTION_COPY)
                            val data = event.transferable.getTransferData(DataFlavor.javaFileListFlavor)
                            val path = (data as? List<*>)?.firstOrNull { item ->
                                item is File &&
                                    (item.name.endsWith(".mrpack", true) || item.name.endsWith(".zip", true))
                            }?.let { (it as File).absolutePath }
                            event.dropComplete(true)
                            if (path != null) {
                                println("DROP modpack: $path")
                                state.openImport(path)
                            }
                        } catch (e: Exception) {
                            event.dropComplete(false)
                            println("DROP error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                println("DROP target unavailable: ${e.message}")
            }
        }
        // QA-only: open a specific page directly (acceptance screenshots).
        LaunchedEffect(qaPage) {
            if (qaPage != null) {
                AppPage.entries.firstOrNull { it.name.equals(qaPage, true) }?.let { state.navigate(it) }
            }
        }
        // QA-only: preselect the 获取内容 tab (0 = 安装游戏版本, 1 = 获取模组).
        LaunchedEffect(qaGetContentTab) {
            if (qaGetContentTab != null) state.getContentTab = qaGetContentTab
        }
        // QA-only: exercise the exact same AppState.launch path a button uses.
        LaunchedEffect(autoLaunch) {
            autoLaunch?.takeIf { it.isNotBlank() }?.let { state.launch(it) }
        }
        // QA-only: drive the real 获取内容 page through its normal state methods.
        LaunchedEffect(qaSearch, qaDialog, qaInstall) {
            if (qaSearch == null) return@LaunchedEffect
            qaInstance?.let { state.modrinthUi.selectedInstanceId = it }
            state.modrinthUi.query = qaSearch
            state.openGetMods()
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
        // QA-only: open the import wizard with a real pack file, optionally
        // auto-clicking 开始导入 once the confirm page is ready.
        LaunchedEffect(qaPackFile) {
            if (qaPackFile == null) return@LaunchedEffect
            state.openImport(qaPackFile)
            if (qaPackStart) {
                withTimeoutOrNull(30_000) {
                    while (state.modpackUi.stage != ModpackUiState.Stage.CONFIRM) delay(100)
                }
                delay(400)
                if (state.modpackUi.stage == ModpackUiState.Stage.CONFIRM) state.modpackUi.start()
            }
        }
        // QA-only: drive the new-instance wizard for the four acceptance screenshots.
        LaunchedEffect(qaNewVersion, qaNewLoader, qaNewStart) {
            if (qaNewVersion == null) return@LaunchedEffect
            state.navigate(AppPage.NEW_INSTANCE)
            withTimeoutOrNull(60_000) {
                while (state.newInstanceUi.loadingVersions || state.newInstanceUi.versions.isEmpty()) delay(100)
            }
            delay(300)
            val v = state.newInstanceUi.versions.firstOrNull { it.id == qaNewVersion } ?: return@LaunchedEffect
            state.newInstanceUi.selectVersion(v)
            if (qaNewLoader != null) {
                state.newInstanceUi.selectLoader(LoaderKind.fromId(qaNewLoader) ?: LoaderKind.VANILLA)
                withTimeoutOrNull(60_000) { while (state.newInstanceUi.loadingLoaders) delay(100) }
                delay(300)
            }
            if (qaNewStart) {
                state.newInstanceUi.nextToName()
                delay(300)
                state.newInstanceUi.start()
            }
        }
        YmclApp(state = state)
    }
}
