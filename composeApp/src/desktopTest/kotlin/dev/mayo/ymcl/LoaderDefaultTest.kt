package dev.mayo.ymcl

import dev.mayo.ymcl.app.BrokenInstall
import dev.mayo.ymcl.app.InstallStage
import dev.mayo.ymcl.app.NewInstanceBackend
import dev.mayo.ymcl.app.NewInstanceResult
import dev.mayo.ymcl.app.NewInstanceUiState
import dev.mayo.ymcl.domain.LoaderKind
import dev.mayo.ymcl.domain.VersionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * p0b-loader: vanilla must be a zero-loader-network default; switching to a
 * mod loader probes + caches; switching back hides the list and cancels.
 *
 * The UiState launches its work on Dispatchers.IO internally (not injected),
 * so tests poll observable state with a bounded deadline.
 */
class LoaderDefaultTest {

    private class FakeBackend(
        private val versionsByLoader: (LoaderKind, String) -> List<String> = { _, _ -> listOf("0.15.0") },
    ) : NewInstanceBackend {
        val loaderCalls = AtomicInteger(0)
        val calls = mutableListOf<Pair<LoaderKind, String>>()

        @Synchronized
        override fun loaderVersions(kind: LoaderKind, mcVersion: String): List<String> {
            loaderCalls.incrementAndGet()
            calls += kind to mcVersion
            return versionsByLoader(kind, mcVersion)
        }

        override fun existingIds(): Set<String> = emptySet()
        override fun loadVersions(): List<VersionEntry> = emptyList()
        override fun neoForgeReachable(): Boolean = true
        override fun install(
            mcVersion: String, entry: VersionEntry, instanceId: String, displayName: String,
            kind: LoaderKind, loaderVersion: String?, onStage: (InstallStage) -> Unit,
        ): NewInstanceResult = NewInstanceResult(instanceId, emptyList(), false)
        override fun cancel() {}
        override fun brokenInstalls(): List<BrokenInstall> = emptyList()
        override fun deleteBrokenInstall(instanceId: String): Boolean = false
    }

    private fun ui(backend: NewInstanceBackend) = NewInstanceUiState(
        backend = backend,
        onFinished = {},
        log = {},
    )

    private fun await(deadlineMs: Long = 2_000L, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + deadlineMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    private val v1201 = VersionEntry("1.20.1")

    @Test
    fun `selecting a version defaults to vanilla with no loader probe`() {
        val backend = FakeBackend()
        val state = ui(backend)
        state.selectVersion(v1201)

        assertEquals(LoaderKind.VANILLA, state.loaderKind)
        assertEquals(NewInstanceUiState.Probe.IDLE, state.probe)
        assertTrue(state.loaderVersions.isEmpty())
        assertEquals("no loader-list request on the vanilla default path", 0, backend.loaderCalls.get())
    }

    @Test
    fun `switching to vanilla hides the list and discards an in-flight probe result`() {
        // loaderVersions blocks until released, so the FABRIC probe is still
        // in flight when we switch back to vanilla.
        val gate = Object()
        var release = false
        val backend = FakeBackend { _, _ ->
            synchronized(gate) {
                while (!release) gate.wait(1_000)
            }
            listOf("0.15.0")
        }
        val state = ui(backend)
        state.selectVersion(v1201)

        state.selectLoader(LoaderKind.FABRIC)
        await { backend.loaderCalls.get() == 1 } // probe entered the blocking fetch
        assertEquals(1, backend.loaderCalls.get())

        // Switch back while the fetch is blocked: list hides, probe cancelled.
        state.selectLoader(LoaderKind.VANILLA)
        assertEquals(LoaderKind.VANILLA, state.loaderKind)
        assertEquals(NewInstanceUiState.Probe.IDLE, state.probe)
        assertTrue(state.loaderVersions.isEmpty())

        // Release the late result; the generation token must discard it.
        synchronized(gate) { release = true; gate.notifyAll() }
        await { backend.loaderCalls.get() == 1 }
        Thread.sleep(80)
        assertEquals(NewInstanceUiState.Probe.IDLE, state.probe)
        assertTrue("late result must not repopulate a vanilla list", state.loaderVersions.isEmpty())
    }

    @Test
    fun `loader list is cached per loader so switching away and back makes no second request`() {
        val backend = FakeBackend()
        val state = ui(backend)
        state.selectVersion(v1201)

        // First FABRIC selection fetches once.
        state.selectLoader(LoaderKind.FABRIC)
        await { state.probe == NewInstanceUiState.Probe.OK }
        assertEquals(1, backend.loaderCalls.get())
        assertEquals(listOf("0.15.0"), state.loaderVersions)

        // Switch to vanilla (hides list) then back to FABRIC: cache hit, no call.
        state.selectLoader(LoaderKind.VANILLA)
        assertTrue(state.loaderVersions.isEmpty())
        state.selectLoader(LoaderKind.FABRIC)
        assertEquals(NewInstanceUiState.Probe.OK, state.probe) // synchronous cache hit
        assertEquals(listOf("0.15.0"), state.loaderVersions)
        assertEquals("cache hit must not re-fetch", 1, backend.loaderCalls.get())

        // A different loader uses a different key → it does fetch.
        state.selectLoader(LoaderKind.QUILT)
        await { state.probe == NewInstanceUiState.Probe.OK }
        assertEquals(2, backend.loaderCalls.get())
        assertEquals(LoaderKind.QUILT to "1.20.1", backend.calls[1])
    }
}
