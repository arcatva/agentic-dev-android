package dev.agentic.ui.login

import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.FakeLanScanner
import dev.agentic.data.FakeSettingsStore
import dev.agentic.data.net.DiscoveredServer
import dev.agentic.data.net.ScanUpdate
import dev.agentic.data.repo.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var api: FakeAgenticApi
    private lateinit var settings: FakeSettingsStore
    private lateinit var scanner: FakeLanScanner
    private lateinit var repoScope: CoroutineScope

    @Before fun setUp() {
        dispatcher = StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        api = FakeAgenticApi()
        settings = FakeSettingsStore()
        scanner = FakeLanScanner()
        repoScope = CoroutineScope(dispatcher)
    }

    @After fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
        repoScope.cancel()
    }

    private fun makeRepo() = AuthRepository(api, settings, repoScope)
    private fun makeVm(initialHost: String = "") = LoginViewModel(makeRepo(), scanner, initialHost)

    private fun server(ip: String, ms: Long) = DiscoveredServer(ip = ip, latencyMs = ms)

    // ── initial state ──────────────────────────────────────────────────────────

    @Test fun `initial state starts on the chooser, empty fields, not done`() = runTest(dispatcher) {
        val vm = makeVm()
        val s = vm.uiState.value
        assertEquals(LoginStep.Chooser, s.step)
        assertEquals("", s.password)
        assertFalse(s.busy)
        assertNull(s.error)
        assertFalse(s.done)
    }

    @Test fun `initialHost prefills the manual host field`() = runTest(dispatcher) {
        val vm = makeVm(initialHost = "http://1.2.3.4:7420")
        assertEquals("http://1.2.3.4:7420", vm.uiState.value.host)
    }

    // ── navigation ───────────────────────────────────────────────────────────

    @Test fun `goTo and back move between steps`() = runTest(dispatcher) {
        val vm = makeVm()
        vm.goTo(LoginStep.Manual)
        assertEquals(LoginStep.Manual, vm.uiState.value.step)
        vm.back()
        assertEquals(LoginStep.Chooser, vm.uiState.value.step)
    }

    @Test fun `navigating between steps clears a stale submit error`() = runTest(dispatcher) {
        api.loginException = java.io.IOException("network down")
        val vm = makeVm()
        vm.goTo(LoginStep.Manual)
        vm.onHost("http://host"); vm.onPassword("pass"); vm.submit()
        advanceUntilIdle()
        assertNotNull("precondition: a failed submit set an error", vm.uiState.value.error)
        vm.goTo(LoginStep.Scan)
        assertNull("error must not linger when switching sub-screens", vm.uiState.value.error)
    }

    // ── auto-scan on open ──────────────────────────────────────────────────────

    @Test fun `scan runs automatically on construction and populates results`() = runTest(dispatcher) {
        scanner.updates = listOf(
            ScanUpdate.Found(server("192.168.1.10", 12)),
            ScanUpdate.Found(server("192.168.1.23", 31)),
            ScanUpdate.Progress(253, 253),
            ScanUpdate.Done,
        )
        val vm = makeVm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(1, scanner.scanCount)
        assertFalse("scanning ends after Done", s.scanning)
        assertEquals(listOf("https://192.168.1.10:7420", "https://192.168.1.23:7420"), s.results.map { it.baseUrl })
    }

    @Test fun `results are sorted by latency ascending`() = runTest(dispatcher) {
        scanner.updates = listOf(
            ScanUpdate.Found(server("192.168.1.23", 31)),
            ScanUpdate.Found(server("192.168.1.10", 12)),
            ScanUpdate.Done,
        )
        val vm = makeVm()
        advanceUntilIdle()
        assertEquals(listOf("192.168.1.10", "192.168.1.23"), vm.uiState.value.results.map { it.ip })
    }

    @Test fun `a single result is auto-selected on Done`() = runTest(dispatcher) {
        scanner.updates = listOf(ScanUpdate.Found(server("192.168.1.10", 12)), ScanUpdate.Done)
        val vm = makeVm()
        advanceUntilIdle()
        assertEquals("https://192.168.1.10:7420", vm.uiState.value.selectedHost)
    }

    @Test fun `multiple results are not auto-selected`() = runTest(dispatcher) {
        scanner.updates = listOf(
            ScanUpdate.Found(server("192.168.1.10", 12)),
            ScanUpdate.Found(server("192.168.1.23", 31)),
            ScanUpdate.Done,
        )
        val vm = makeVm()
        advanceUntilIdle()
        assertNull(vm.uiState.value.selectedHost)
    }

    @Test fun `NotOnLan sets the flag and stops scanning`() = runTest(dispatcher) {
        scanner.updates = listOf(ScanUpdate.NotOnLan)
        val vm = makeVm()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.notOnLan)
        assertFalse(vm.uiState.value.scanning)
    }

    @Test fun `rescan re-runs the scanner and clears prior results`() = runTest(dispatcher) {
        scanner.updates = listOf(ScanUpdate.Found(server("192.168.1.10", 12)), ScanUpdate.Done)
        val vm = makeVm()
        advanceUntilIdle()
        vm.rescan()
        advanceUntilIdle()
        assertEquals(2, scanner.scanCount)
        assertEquals(1, vm.uiState.value.results.size)
    }

    @Test fun `onSelectServer sets the selected host`() = runTest(dispatcher) {
        val vm = makeVm()
        vm.onSelectServer("http://192.168.1.42:7420")
        assertEquals("http://192.168.1.42:7420", vm.uiState.value.selectedHost)
    }

    // ── password visibility ──────────────────────────────────────────────────

    @Test fun `togglePasswordVisible flips the flag`() = runTest(dispatcher) {
        val vm = makeVm()
        assertFalse(vm.uiState.value.passwordVisible)
        vm.togglePasswordVisible()
        assertTrue(vm.uiState.value.passwordVisible)
    }

    // ── submit (manual) ──────────────────────────────────────────────────────

    @Test fun `manual submit success sets done`() = runTest(dispatcher) {
        api.loginTokenResult = "tok"
        val vm = makeVm()
        vm.goTo(LoginStep.Manual)
        vm.onHost("http://host"); vm.onPassword("pass")
        vm.submit()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.done); assertFalse(s.busy); assertNull(s.error)
    }

    @Test fun `manual submit failure sets a clean host hint`() = runTest(dispatcher) {
        api.loginException = java.io.IOException("network down")
        val vm = makeVm()
        vm.goTo(LoginStep.Manual)
        vm.onHost("http://host"); vm.onPassword("pass"); vm.submit()
        advanceUntilIdle()
        val err = vm.uiState.value.error ?: ""
        assertFalse(s_leak(err))
        assertTrue(err.contains("reach the server", ignoreCase = true))
        assertFalse(vm.uiState.value.done)
    }
    private fun s_leak(err: String) = err.contains("AppError") || err.contains("Network(")

    // ── submit (scan) ──────────────────────────────────────────────────────────

    @Test fun `scan submit logs in with the selected server's baseUrl`() = runTest(dispatcher) {
        api.loginTokenResult = "tok"
        val vm = makeVm()
        vm.goTo(LoginStep.Scan)
        vm.onSelectServer("http://192.168.1.10:7420")
        vm.onPassword("pass")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        assertEquals("http://192.168.1.10:7420", settings.host) // AuthRepository.login persisted it
    }

    @Test fun `scan submit with no selection is a no-op`() = runTest(dispatcher) {
        api.loginTokenResult = "tok"
        val vm = makeVm()
        vm.goTo(LoginStep.Scan)
        vm.onPassword("pass")
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.done)
        assertFalse(vm.uiState.value.busy)
    }
}
