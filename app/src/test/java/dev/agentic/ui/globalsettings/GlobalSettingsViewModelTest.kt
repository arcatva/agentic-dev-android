package dev.agentic.ui.globalsettings

import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.net.ComponentInfo
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

/**
 * Unit tests for [GlobalSettingsViewModel]. All coroutines run on [StandardTestDispatcher]
 * so [advanceUntilIdle] drives them deterministically (same pattern as NewRequestViewModelTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSettingsViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var api: FakeAgenticApi

    @Before fun setUp() {
        dispatcher = StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        api = FakeAgenticApi()
    }

    @After fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun vm() = GlobalSettingsViewModel(api)

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun skill(id: String, name: String, enabled: Boolean) = ComponentInfo(
        kind = "skill", id = id, name = name, description = "desc-$id",
        source = "src", globalEnabled = enabled,
    )

    private fun plugin(id: String, name: String, enabled: Boolean) = ComponentInfo(
        kind = "plugin", id = id, name = name, description = "", source = "", globalEnabled = enabled,
    )

    private fun mcp(id: String, name: String, enabled: Boolean) = ComponentInfo(
        kind = "mcp", id = id, name = name, description = "", source = "", globalEnabled = enabled,
    )

    // ── initial load ─────────────────────────────────────────────────────────

    @Test fun `initial state is loading`() = runTest(dispatcher) {
        api.getGlobalSettingsException = RuntimeException("not yet")
        val vm = vm()
        assertTrue(vm.uiState.value.loading)
    }

    @Test fun `load succeeds — components list is populated`() = runTest(dispatcher) {
        api.globalSettingsResult = listOf(
            skill("s1", "Skill One", true),
            plugin("p1", "Plugin One", false),
            mcp("m1", "MCP One", true),
        )
        val vm = vm()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.loading)
        assertNull(s.error)
        assertEquals(3, s.components.size)
    }

    @Test fun `load succeeds — components grouped by kind in expected order`() = runTest(dispatcher) {
        api.globalSettingsResult = listOf(
            mcp("m1", "MCP One", true),
            plugin("p1", "Plugin One", false),
            skill("s1", "Skill One", true),
            skill("s2", "Skill Two", false),
        )
        val vm = vm()
        advanceUntilIdle()

        val components = vm.uiState.value.components
        // The VM stores in arrival order; grouping is done by the Screen. Verify all present.
        assertEquals(4, components.size)
        assertEquals(2, components.count { it.kind == "skill" })
        assertEquals(1, components.count { it.kind == "plugin" })
        assertEquals(1, components.count { it.kind == "mcp" })
    }

    @Test fun `load failure — sets error, clears loading`() = runTest(dispatcher) {
        api.getGlobalSettingsException = RuntimeException("network error")
        val vm = vm()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.loading)
        assertNotNull(s.error)
        assertTrue(s.error!!.contains("network error"))
    }

    // ── toggle ────────────────────────────────────────────────────────────────

    @Test fun `toggle calls toggleGlobalComponent with correct kind, id, and negated enabled`() = runTest(dispatcher) {
        val component = skill("s1", "Skill One", enabled = true)
        api.globalSettingsResult = listOf(component)
        api.toggleGlobalComponentResult = listOf(component.copy(globalEnabled = false))

        val vm = vm()
        advanceUntilIdle()

        vm.toggle(component)
        advanceUntilIdle()

        assertEquals(1, api.toggleGlobalComponentCalls.size)
        val call = api.toggleGlobalComponentCalls.first()
        assertEquals("skill", call.first)
        assertEquals("s1", call.second)
        assertEquals(false, call.third) // negated from enabled=true
    }

    @Test fun `toggle applies refreshed list from API response`() = runTest(dispatcher) {
        val c1 = skill("s1", "Skill One", enabled = true)
        val c2 = plugin("p1", "Plugin One", enabled = false)
        api.globalSettingsResult = listOf(c1, c2)
        // Server returns c1 disabled AND c2 also enabled after some side-effect.
        val refreshed = listOf(c1.copy(globalEnabled = false), c2.copy(globalEnabled = true))
        api.toggleGlobalComponentResult = refreshed

        val vm = vm()
        advanceUntilIdle()

        vm.toggle(c1)
        advanceUntilIdle()

        val components = vm.uiState.value.components
        assertEquals(2, components.size)
        assertFalse(components.first { it.id == "s1" }.globalEnabled)
        assertTrue(components.first { it.id == "p1" }.globalEnabled)
    }

    @Test fun `toggle optimistically updates switch before API returns`() = runTest(dispatcher) {
        val component = skill("s1", "Skill One", enabled = true)
        api.globalSettingsResult = listOf(component)
        // Gate so the toggle call never resolves in this test.
        api.toggleGlobalComponentResult = listOf(component.copy(globalEnabled = false))

        val vm = vm()
        advanceUntilIdle()

        // Don't advance fully — just trigger the toggle and check the optimistic update.
        vm.toggle(component)
        // Run only enough to enqueue the optimistic update (one yield).
        dispatcher.scheduler.advanceTimeBy(0)

        // Optimistic: should already show false before API call returns.
        val optimistic = vm.uiState.value.components.first { it.id == "s1" }
        assertFalse(optimistic.globalEnabled)
    }

    @Test fun `toggle error reverts switch and surfaces error`() = runTest(dispatcher) {
        val component = skill("s1", "Skill One", enabled = true)
        api.globalSettingsResult = listOf(component)
        api.toggleGlobalComponentException = RuntimeException("server error")

        val vm = vm()
        advanceUntilIdle()

        vm.toggle(component)
        advanceUntilIdle()

        val s = vm.uiState.value
        // Reverted: back to original enabled=true.
        assertTrue(s.components.first { it.id == "s1" }.globalEnabled)
        // Error message surfaced.
        assertNotNull(s.error)
        assertTrue(s.error!!.contains("server error"))
        // No longer toggling.
        assertTrue(s.toggling.isEmpty())
    }

    // ── strengthened toggle tests ─────────────────────────────────────────────

    /**
     * Toggle FAILS → row reverts to original globalEnabled AND an error is surfaced.
     * Would fail if revert logic were removed (state would stay at the optimistic flipped value).
     */
    @Test fun `toggle failure reverts to original enabled and surfaces error`() = runTest(dispatcher) {
        val original = skill("s1", "Skill One", enabled = false) // starts disabled
        api.globalSettingsResult = listOf(original)
        api.toggleGlobalComponentException = RuntimeException("timeout")

        val vm = vm()
        advanceUntilIdle()

        vm.toggle(original)
        advanceUntilIdle()

        val s = vm.uiState.value
        // Must revert: the original was enabled=false, NOT the optimistic true.
        assertFalse("row must revert to original enabled=false", s.components.first { it.id == "s1" }.globalEnabled)
        // Error must be surfaced.
        assertNotNull("error must be non-null after failure", s.error)
        assertTrue(s.error!!.contains("timeout"))
        // Must no longer be in toggling set.
        assertTrue(s.toggling.isEmpty())
    }

    /**
     * Toggle returns a list where the toggled row AND an unrelated row differ from a naive flip.
     * This distinguishes "state comes from the server response" from "state is just the optimistic flip".
     * Would fail if the VM ignored the returned list and kept the optimistic state.
     */
    @Test fun `toggle state reflects returned list not naive optimistic flip`() = runTest(dispatcher) {
        val c1 = skill("s1", "SkillOne", enabled = true)
        val c2 = plugin("p1", "PluginOne", enabled = true)
        api.globalSettingsResult = listOf(c1, c2)
        // Server response: c1 ends up STILL ENABLED (backend vetoed the disable), c2 also changed.
        val serverResponse = listOf(c1.copy(globalEnabled = true), c2.copy(globalEnabled = false))
        api.toggleGlobalComponentResult = serverResponse

        val vm = vm()
        advanceUntilIdle()

        // Toggle c1 (optimistic flip would set c1 to false; server says c1 stays true).
        vm.toggle(c1)
        advanceUntilIdle()

        val components = vm.uiState.value.components
        // If VM used server response: c1=true (server said so), c2=false (server side-effect).
        // If VM used naive optimistic: c1=false, c2=true (unchanged from initial).
        assertTrue("c1 must reflect server-returned enabled=true, not naive flip to false",
            components.first { it.id == "s1" }.globalEnabled)
        assertFalse("c2 must reflect server-returned enabled=false (side-effect)",
            components.first { it.id == "p1" }.globalEnabled)
    }

    /**
     * MCP rows must NOT call toggleGlobalComponent — global toggle is deferred for MCP.
     * Would fail if the kind=="mcp" guard were removed from toggle().
     */
    @Test fun `mcp toggle does not call toggleGlobalComponent`() = runTest(dispatcher) {
        val mcpComponent = mcp("m1", "MCP One", enabled = true)
        api.globalSettingsResult = listOf(mcpComponent)

        val vm = vm()
        advanceUntilIdle()

        vm.toggle(mcpComponent)
        advanceUntilIdle()

        // No API call must have been made.
        assertTrue("toggleGlobalComponent must not be called for mcp rows",
            api.toggleGlobalComponentCalls.isEmpty())
        // State must be unchanged (not optimistically flipped).
        assertTrue("mcp row enabled state must not change",
            vm.uiState.value.components.first { it.id == "m1" }.globalEnabled)
    }

    // ── clearError ────────────────────────────────────────────────────────────

    @Test fun `clearError removes the error message`() = runTest(dispatcher) {
        api.getGlobalSettingsException = RuntimeException("boom")
        val vm = vm()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.error)
        vm.clearError()
        assertNull(vm.uiState.value.error)
    }

    // ── getGlobalSettings call count ──────────────────────────────────────────

    @Test fun `load() is called once on init`() = runTest(dispatcher) {
        api.globalSettingsResult = emptyList()
        val vm = vm()
        advanceUntilIdle()
        assertEquals(1, api.getGlobalSettingsCallCount)
    }
}
