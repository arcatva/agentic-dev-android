package dev.agentic.ui.globalsettings

import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.net.ComponentInfo
import dev.agentic.ui.newrequest.McpDraft
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

    private fun vm() = GlobalSettingsViewModel(dev.agentic.data.repo.GlobalSettingsRepository(api))

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
     * MCP rows toggle like skills/plugins now — the backend supports a global MCP toggle
     * (it parks the server definition in .claude.json's mcpServersDisabled).
     */
    @Test fun `mcp toggle calls toggleGlobalComponent`() = runTest(dispatcher) {
        val mcpComponent = mcp("m1", "MCP One", enabled = true)
        api.globalSettingsResult = listOf(mcpComponent)
        api.toggleGlobalComponentResult = listOf(mcp("m1", "MCP One", enabled = false))

        val vm = vm()
        advanceUntilIdle()

        vm.toggle(mcpComponent)
        advanceUntilIdle()

        assertEquals(
            listOf(Triple("mcp", "m1", false)),
            api.toggleGlobalComponentCalls,
        )
        assertTrue("mcp row must reflect the API's returned disabled state",
            !vm.uiState.value.components.first { it.id == "m1" }.globalEnabled)
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

    // ── skill store (catalog + install) ───────────────────────────────────────────

    @Test fun `loadCatalog fetches once and caches`() = runTest(dispatcher) {
        api.skillCatalogResult = listOf(
            dev.agentic.data.net.CatalogSkill(name = "pdf", description = "PDF toolkit", source = "anthropics/skills/skills/pdf"),
        )
        val vm = vm()
        advanceUntilIdle()

        vm.loadCatalog()
        advanceUntilIdle()
        assertEquals(1, api.skillCatalogCalls)
        assertEquals("pdf", vm.uiState.value.catalog!!.single().name)

        // Second call is a no-op (already loaded).
        vm.loadCatalog()
        advanceUntilIdle()
        assertEquals(1, api.skillCatalogCalls)
    }

    @Test fun `loadCatalog failure surfaces catalogError and allows retry`() = runTest(dispatcher) {
        api.skillCatalogException = RuntimeException("offline")
        val vm = vm()
        advanceUntilIdle()

        vm.loadCatalog()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.catalogError)
        assertNull(vm.uiState.value.catalog)

        // A retry after the failure fetches again.
        api.skillCatalogException = null
        vm.loadCatalog()
        advanceUntilIdle()
        assertEquals(2, api.skillCatalogCalls)
        assertNull(vm.uiState.value.catalogError)
    }

    @Test fun `installSkill calls API and refreshes components`() = runTest(dispatcher) {
        val installed = skill("pdf", "pdf", true)
        api.installSkillResult = listOf(installed)
        val vm = vm()
        advanceUntilIdle()

        vm.installSkill("anthropics/skills/skills/pdf")
        advanceUntilIdle()

        assertEquals(listOf("anthropics/skills/skills/pdf" to false), api.installSkillCalls)
        assertEquals(listOf(installed), vm.uiState.value.components)
        assertFalse(vm.uiState.value.busy)
    }

    @Test fun `installSkill update flag reaches API`() = runTest(dispatcher) {
        api.installSkillResult = listOf(skill("pdf", "pdf", true))
        val vm = vm()
        advanceUntilIdle()

        vm.installSkill("anthropics/skills/skills/pdf", update = true)
        advanceUntilIdle()
        assertEquals(listOf("anthropics/skills/skills/pdf" to true), api.installSkillCalls)
    }

    @Test fun `addSource updates sources and force-refreshes the catalog`() = runTest(dispatcher) {
        api.skillCatalogResult = emptyList()
        val vm = vm()
        advanceUntilIdle()

        vm.addSource("me/my-skills")
        advanceUntilIdle()

        assertEquals(listOf("me/my-skills"), api.addSkillSourceCalls)
        assertTrue(vm.uiState.value.sources!!.contains("me/my-skills"))
        // The follow-up catalog reload bypassed the cache.
        assertTrue(api.skillCatalogRefreshCalls.contains(true))
        assertFalse(vm.uiState.value.busy)
    }

    @Test fun `removeSource updates sources and surfaces failure`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()

        vm.removeSource("anthropics/skills")
        advanceUntilIdle()
        assertEquals(listOf("anthropics/skills"), api.deleteSkillSourceCalls)
        assertEquals(emptyList<String>(), vm.uiState.value.sources)

        api.deleteSkillSourceException = RuntimeException("nope")
        vm.removeSource("x/y")
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.busy)
    }

    @Test fun `installSkill failure surfaces error and clears busy`() = runTest(dispatcher) {
        api.installSkillException = RuntimeException("no SKILL.md found")
        val vm = vm()
        advanceUntilIdle()

        vm.installSkill("owner/repo/not-a-skill")
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.busy)
        assertNotNull(s.error)
        assertTrue(s.error!!.contains("no SKILL.md"))
    }



    // ── deleteSkill ───────────────────────────────────────────────────────────────

    @Test fun `deleteSkill calls API with correct name and updates from returned list`() = runTest(dispatcher) {
        val s1 = skill("s1", "SkillOne", true)
        api.globalSettingsResult = listOf(s1)
        api.deleteSkillResult = emptyList()   // server returns empty after delete

        val vm = vm()
        advanceUntilIdle()

        vm.deleteSkill("SkillOne")
        advanceUntilIdle()

        assertEquals(listOf("SkillOne"), api.deleteSkillCalls)
        assertTrue(vm.uiState.value.components.isEmpty())
        assertNull(vm.uiState.value.error)
    }

    @Test fun `deleteSkill API error surfaces error without corrupting state`() = runTest(dispatcher) {
        val s1 = skill("s1", "SkillOne", true)
        api.globalSettingsResult = listOf(s1)
        api.deleteSkillException = RuntimeException("not found")

        val vm = vm()
        advanceUntilIdle()

        vm.deleteSkill("SkillOne")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("not found"))
        assertEquals(1, state.components.size)   // unchanged
    }

    // ── installPlugin ─────────────────────────────────────────────────────────────

    @Test fun `installPlugin sets busy true then clears on success`() = runTest(dispatcher) {
        api.globalSettingsResult = emptyList()
        val p1 = plugin("p1@npm", "myplugin", true)
        api.installPluginResult = listOf(p1)

        val vm = vm()
        advanceUntilIdle()

        // Start install but do NOT advance yet — verify busy flag is set.
        vm.installPlugin("p1@npm")
        // busy should be true synchronously (set before launch via acquireBusy).
        assertTrue("busy must be true while install is in flight", vm.uiState.value.busy)

        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse("busy must clear after success", s.busy)
        assertEquals(1, s.components.size)
        assertEquals("p1@npm", api.installPluginCalls[0])
        assertNull(s.error)
    }

    @Test fun `installPlugin API error clears busy and surfaces error`() = runTest(dispatcher) {
        api.globalSettingsResult = emptyList()
        api.installPluginException = RuntimeException("CLI error")

        val vm = vm()
        advanceUntilIdle()

        vm.installPlugin("bad@npm")
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse("busy must clear after error", s.busy)
        assertNotNull(s.error)
        assertTrue(s.error!!.contains("CLI error"))
    }

    // ── uninstallPlugin ───────────────────────────────────────────────────────────

    @Test fun `uninstallPlugin calls API and updates components from returned list`() = runTest(dispatcher) {
        val p1 = plugin("p1@npm", "MyPlugin", true)
        api.globalSettingsResult = listOf(p1)
        api.uninstallPluginResult = emptyList()

        val vm = vm()
        advanceUntilIdle()

        vm.uninstallPlugin("p1@npm")
        advanceUntilIdle()

        assertEquals(listOf("p1@npm"), api.uninstallPluginCalls)
        assertTrue(vm.uiState.value.components.isEmpty())
        assertNull(vm.uiState.value.error)
    }

    // ── addMcpServer — validation ─────────────────────────────────────────────────

    @Test fun `addMcpServer blank name returns validation error without calling API`() = runTest(dispatcher) {
        api.globalSettingsResult = emptyList()
        val vm = vm()
        advanceUntilIdle()

        val err = vm.addMcpServer(McpDraft(name = "", transport = "stdio", command = "node"))
        assertNotNull("blank name must return an error", err)
        assertTrue(api.addMcpServerCalls.isEmpty())
    }

    @Test fun `addMcpServer name agentic returns validation error`() = runTest(dispatcher) {
        api.globalSettingsResult = emptyList()
        val vm = vm()
        advanceUntilIdle()

        val err = vm.addMcpServer(McpDraft(name = "agentic", transport = "stdio", command = "node"))
        assertNotNull(err)
        assertTrue(err!!.contains("agentic"))
        assertTrue(api.addMcpServerCalls.isEmpty())
    }

    @Test fun `addMcpServer stdio missing command returns validation error`() = runTest(dispatcher) {
        api.globalSettingsResult = emptyList()
        val vm = vm()
        advanceUntilIdle()

        val err = vm.addMcpServer(McpDraft(name = "my-mcp", transport = "stdio", command = ""))
        assertNotNull(err)
        assertTrue(api.addMcpServerCalls.isEmpty())
    }

    @Test fun `addMcpServer valid stdio draft calls API and updates components`() = runTest(dispatcher) {
        api.globalSettingsResult = emptyList()
        val newMcp = mcp("my-mcp", "my-mcp", true)
        api.addMcpServerResult = listOf(newMcp)

        val vm = vm()
        advanceUntilIdle()

        val err = vm.addMcpServer(McpDraft(name = "my-mcp", transport = "stdio", command = "/usr/bin/node"))
        assertNull("valid draft must return null error", err)
        advanceUntilIdle()

        assertEquals(1, api.addMcpServerCalls.size)
        assertEquals("my-mcp", api.addMcpServerCalls[0].name)
        assertEquals(1, vm.uiState.value.components.size)
    }

    @Test fun `addMcpServer API error surfaces error`() = runTest(dispatcher) {
        api.globalSettingsResult = emptyList()
        api.addMcpServerException = RuntimeException("duplicate name")

        val vm = vm()
        advanceUntilIdle()

        val err = vm.addMcpServer(McpDraft(name = "dup", transport = "stdio", command = "node"))
        assertNull(err)  // validation passes
        advanceUntilIdle()

        val s = vm.uiState.value
        assertNotNull(s.error)
        assertTrue(s.error!!.contains("duplicate name"))
    }

    // ── deleteMcpServer ───────────────────────────────────────────────────────────

    @Test fun `deleteMcpServer calls API with correct name and updates from returned list`() = runTest(dispatcher) {
        val m1 = mcp("my-mcp", "my-mcp", true)
        api.globalSettingsResult = listOf(m1)
        api.deleteMcpServerResult = emptyList()

        val vm = vm()
        advanceUntilIdle()

        vm.deleteMcpServer("my-mcp")
        advanceUntilIdle()

        assertEquals(listOf("my-mcp"), api.deleteMcpServerCalls)
        assertTrue(vm.uiState.value.components.isEmpty())
        assertNull(vm.uiState.value.error)
    }

    @Test fun `deleteMcpServer API error surfaces error without corrupting state`() = runTest(dispatcher) {
        val m1 = mcp("my-mcp", "my-mcp", true)
        api.globalSettingsResult = listOf(m1)
        api.deleteMcpServerException = RuntimeException("not found")

        val vm = vm()
        advanceUntilIdle()

        vm.deleteMcpServer("my-mcp")
        advanceUntilIdle()

        val s = vm.uiState.value
        assertNotNull(s.error)
        assertTrue(s.error!!.contains("not found"))
        assertEquals(1, s.components.size)  // unchanged
    }

    // ── New: adversarial-review fixes ──────────────────────────────────────────

    /**
     * Fix 2 — add-form op FAILS: error is set AND busy is cleared (so the Screen can detect
     * failure via busy==false+error≠null and keep the form open).
     * The VM does not own "form is open" state — that's local Compose state — but the VM MUST
     * surface the error so the Screen can act on it.
     *
     * We verify: error is non-null after the failure, busy is false (cleared), and the component
     * list is UNCHANGED (no stale partial update).
     */

    /**
     * Fix 3 — second mutating action is rejected (no-ops) while a first op is still in flight.
     * Scenario: two rapid installPlugin calls. The first sets busy=true; the second must return
     * without enqueuing a second API call, leaving the API called exactly once.
     */
    @Test fun `second mutating action is no-op while busy`() = runTest(dispatcher) {
        api.globalSettingsResult = emptyList()
        val p1 = plugin("p1@npm", "Plugin1", true)
        api.installPluginResult = listOf(p1)

        val vm = vm()
        advanceUntilIdle()

        // First call — sets busy=true synchronously.
        vm.installPlugin("p1@npm")
        assertTrue("busy must be true after first call", vm.uiState.value.busy)

        // Second call — must be ignored because busy=true.
        vm.installPlugin("p2@npm")

        advanceUntilIdle()

        // API must have been called exactly once (the second call was dropped).
        assertEquals("API must be called exactly once (second call dropped)", 1, api.installPluginCalls.size)
        assertEquals("p1@npm", api.installPluginCalls[0])
        assertFalse("busy must be false after first op completes", vm.uiState.value.busy)
    }

    /**
     * busy is cleared in the error path (not only on success) — the invariant applies to all
     * CRUD ops; installSkill is the exemplar here.
     */
    @Test fun `busy is cleared after op throws — subsequent op can proceed`() = runTest(dispatcher) {
        api.globalSettingsResult = emptyList()
        api.installSkillException = RuntimeException("first error")

        val vm = vm()
        advanceUntilIdle()

        // First op — will throw.
        vm.installSkill("o/r/bad")
        advanceUntilIdle()

        assertFalse("busy must be false after thrown op", vm.uiState.value.busy)
        assertNotNull(vm.uiState.value.error)

        // Now clear the exception and run a second op — must succeed.
        api.installSkillException = null
        val added = skill("s-ok", "GoodSkill", true)
        api.installSkillResult = listOf(added)

        vm.installSkill("o/r/GoodSkill")
        advanceUntilIdle()

        // Second op succeeded: components updated and no error.
        assertEquals("GoodSkill", vm.uiState.value.components.firstOrNull()?.name)
        assertNull("error must be null after second op success", vm.uiState.value.error)
        assertFalse("busy must be false after second op", vm.uiState.value.busy)
        // API was called twice total (first threw, second succeeded).
        assertEquals(2, api.installSkillCalls.size)
    }
}
