package dev.agentic.ui.newrequest

import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.net.ComponentInfo
import dev.agentic.data.net.McpServerDef
import dev.agentic.data.net.ModelEntry
import dev.agentic.data.net.RepoList
import dev.agentic.data.net.Template
import dev.agentic.data.repo.SessionsRepository
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

/**
 * Tests for [NewRequestViewModel]. Uses Dispatchers.setMain(testDispatcher) so that
 * viewModelScope runs on the test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewRequestViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var api: FakeAgenticApi
    private lateinit var repoScope: CoroutineScope

    @Before fun setUp() {
        dispatcher = StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        api = FakeAgenticApi()
        repoScope = CoroutineScope(dispatcher)
        // ModelCatalog is a global object — reset it before each test so that a warm catalog
        // from a previous test (e.g. `init loads model catalog`) can't leak into the initial
        // NewRequestUiState.model default value of the NEXT test (which would break
        // `init survives model catalog failure and leaves model null`).
        dev.agentic.ui.ModelCatalog.invalidate()
    }

    @After fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
        repoScope.cancel()
    }

    private fun sessionsRepo() = SessionsRepository(api, repoScope)

    // ── initial state ──────────────────────────────────────────────────────────

    @Test fun `initial state has empty catalogs and form fields`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        val s = vm.uiState.value
        assertTrue(s.availableRepos.isEmpty())
        assertTrue(s.availableSkillComponents.isEmpty())
        assertTrue(s.templates.isEmpty())
        assertEquals("", s.prompt)
        // Model starts null — the catalog loads asynchronously and the default is set from the
        // backend's `default: true` entry. Until then the slider shows "Default" (no override).
        assertNull(s.model)
        assertEquals("xhigh", s.effort)
        assertEquals("ultracode", s.mode)
        assertFalse(s.submitting)
        assertNull(s.createdId)
        assertNull(s.error)
    }

    // ── init loads catalogs ────────────────────────────────────────────────────

    @Test fun `init loads availableRepos from sessionsRepo repos`() = runTest(dispatcher) {
        api.reposResult = RepoList(local = listOf("localRepo"), remote = listOf("remoteRepo"))
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue("local repo should be in availableRepos", s.availableRepos.contains("localRepo"))
        assertTrue("remote repo should be in availableRepos", s.availableRepos.contains("remoteRepo"))
    }

    @Test fun `init loads availableSkillComponents from globalSettings`() = runTest(dispatcher) {
        api.globalSettingsResult = listOf(
            ComponentInfo(kind = "skill", id = "skill-a", name = "skill-a", globalEnabled = true),
            ComponentInfo(kind = "skill", id = "skill-b", name = "skill-b", globalEnabled = false),
        )
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle()
        val comps = vm.uiState.value.availableSkillComponents
        assertEquals(2, comps.size)
        assertEquals("skill-a", comps[0].name)
        assertEquals("skill-b", comps[1].name)
        // All overrides default to Inherit (follow global) — no map entries set.
        assertTrue(vm.uiState.value.skillOverrides.isEmpty())
    }


    @Test fun `init loads templates from sessionsRepo templates`() = runTest(dispatcher) {
        api.templatesResult = listOf(Template(name = "tmpl1", promptBody = "do {{task}}"))
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.templates.size)
        assertEquals("tmpl1", vm.uiState.value.templates.first().name)
    }

    @Test fun `init survives getTemplates failure and leaves templates empty`() = runTest(dispatcher) {
        api.getTemplatesException = java.io.IOException("server unavailable")
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle()
        assertTrue("templates should be empty on load failure", vm.uiState.value.templates.isEmpty())
    }

    @Test fun `init loads model catalog and sets default model`() = runTest(dispatcher) {
        api.modelsResult = listOf(
            ModelEntry(key = "claude-haiku-4-5-20251001", label = "Haiku 4.5", native = true, default = false, capability = 0.60f),
            ModelEntry(key = "claude-sonnet-4-6", label = "Sonnet 4.6", native = true, default = false, capability = 0.85f),
            ModelEntry(key = "claude-opus-4-8", label = "Opus 4.8", native = true, default = true, capability = 0.97f),
        )
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle()
        assertEquals("claude-opus-4-8", vm.uiState.value.model)
    }

    @Test fun `init survives model catalog failure and leaves model null`() = runTest(dispatcher) {
        api.modelsException = java.io.IOException("server unavailable")
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle()
        assertNull("model should stay null when catalog load fails", vm.uiState.value.model)
    }

    // ── init loads MCP components ──────────────────────────────────────────────



    // ── form field setters ─────────────────────────────────────────────────────

    @Test fun `setRepos updates selectedRepos`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setRepos(listOf("repoA", "repoB"))
        assertEquals(listOf("repoA", "repoB"), vm.uiState.value.selectedRepos)
    }

    @Test fun `setSkillOverride updates skillOverrides`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setSkillOverride("sk1", Override.ForceOn)
        assertEquals(Override.ForceOn, vm.uiState.value.skillOverrides["sk1"])
    }



    @Test fun `setPrompt updates prompt`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setPrompt("my prompt")
        assertEquals("my prompt", vm.uiState.value.prompt)
    }

    @Test fun `setClaudeMd updates claudeMd`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setClaudeMd("Run tests first.")
        assertEquals("Run tests first.", vm.uiState.value.claudeMd)
    }

    @Test fun `setModel updates model`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setModel("opus")
        assertEquals("opus", vm.uiState.value.model)
    }

    @Test fun `setEffort updates effort`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setEffort("high")
        assertEquals("high", vm.uiState.value.effort)
    }

    @Test fun `setMode updates mode`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setMode("fast")
        assertEquals("fast", vm.uiState.value.mode)
    }

    // ── applyTemplate ──────────────────────────────────────────────────────────

    @Test fun `applyTemplate fills prompt via domain applyTemplate and copies repos skills model effort mode`() = runTest(dispatcher) {
        val template = Template(
            name = "deploy",
            promptBody = "Deploy {{service}} to {{env}}",
            repos = listOf("infra-repo"),
            skills = listOf("kubectl"),
            model = "haiku",
            effort = "low",
            mode = "fast",
        )
        api.globalSettingsResult = listOf(
            ComponentInfo(kind = "skill", id = "kubectl",     name = "kubectl",     globalEnabled = true),
            ComponentInfo(kind = "skill", id = "other-skill", name = "other-skill", globalEnabled = true),
        )
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle()
        vm.applyTemplate(template, mapOf("service" to "api", "env" to "prod"))
        val s = vm.uiState.value
        assertEquals("Deploy api to prod", s.prompt)
        assertEquals(listOf("infra-repo"), s.selectedRepos)
        // "kubectl" is listed in template.skills → must be explicitly Inherit in the map.
        // No elvis fallback here: an absent key is null, not Override.Inherit, so this asserts
        // setSkillsFromTemplate actually wrote the entry (not just omitted it silently).
        assertEquals(Override.Inherit, s.skillOverrides["kubectl"])
        assertEquals(Override.ForceOff, s.skillOverrides["other-skill"])
        assertEquals("haiku", s.model)
        assertEquals("low", s.effort)
        assertEquals("fast", s.mode)
    }

    // ── submit success ─────────────────────────────────────────────────────────

    @Test fun `submit success sets createdId and clears submitting`() = runTest(dispatcher) {
        api.createResult = "created-123"
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setPrompt("do something")
        vm.submit()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals("created-123", s.createdId)
        assertFalse("submitting should be false after success", s.submitting)
        assertNull("error should be null on success", s.error)
    }

    // ── tri-state override derivation ─────────────────────────────────────────

    @Test fun `submit sends correct NewSessionReq with tri-state overrides`() = runTest(dispatcher) {
        api.createResult = "new-id"
        api.globalSettingsResult = listOf(
            ComponentInfo(kind = "skill",  id = "skill-a",             name = "skill-a",             globalEnabled = true),
            ComponentInfo(kind = "skill",  id = "skill-b",             name = "skill-b",             globalEnabled = true),
            ComponentInfo(kind = "skill",  id = "skill-c",             name = "skill-c",             globalEnabled = true),
            ComponentInfo(kind = "plugin", id = "superpowers@official", name = "superpowers@official", globalEnabled = true),
            ComponentInfo(kind = "plugin", id = "github@official",     name = "github@official",     globalEnabled = true),
        )
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle() // let catalogs load
        vm.setRepos(listOf("r1"))
        // skill-a: ForceOff (hidden), skill-b: ForceOn (forced-on), skill-c: Inherit (neither)
        vm.setSkillOverride("skill-a", Override.ForceOff)
        vm.setSkillOverride("skill-b", Override.ForceOn)
        vm.setPrompt("build it")
        vm.setModel("sonnet")
        vm.setEffort("medium")
        vm.setMode("auto")
        vm.submit()
        advanceUntilIdle()
        val req = api.createCalls.single()
        assertEquals(listOf("r1"), req.repos)
        // The whitelist `skills` field is dead post-cutover; gating is sent as override lists.
        assertEquals(emptyList<String>(), req.skills)
        assertEquals(listOf("skill-a"), req.hiddenSkills)
        assertEquals(listOf("skill-b"), req.forcedOnSkills)
        assertEquals(emptyList<String>(), req.hiddenPlugins)
        assertEquals(emptyList<String>(), req.forcedOnPlugins)
        assertEquals(emptyList<String>(), req.hiddenMcpServers)
        assertEquals(emptyList<String>(), req.forcedOnMcpServers)
        assertEquals(emptyList<McpServerDef>(), req.extraMcpServers)
        assertEquals("build it", req.prompt)
        assertEquals("sonnet", req.model)
        assertEquals("medium", req.effort)
        assertEquals("auto", req.mode)
    }

    /**
     * Exhaustive six-list derivation test. Sets a MIX across skills/plugins/mcp —
     * some ForceOn, some ForceOff, some Inherit — and asserts ALL SIX submit lists EXACTLY.
     * Inherit ids must appear in NEITHER the hidden nor the forced-on list.
     * This test fails if any list's filter (== ForceOff / == ForceOn) were swapped or dropped.
     */


    // ── binary toggle semantics ────────────────────────────────────────────────




    @Test fun `fresh request with no taps sends empty override lists`() = runTest(dispatcher) {
        api.createResult = "new-id"
        api.globalSettingsResult = listOf(
            ComponentInfo(kind = "skill",  id = "sk1",   name = "sk1",   globalEnabled = true),
            ComponentInfo(kind = "plugin", id = "pl1",   name = "pl1",   globalEnabled = false),
            ComponentInfo(kind = "mcp",    id = "mcp-a", name = "MCP A", globalEnabled = true),
        )
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle()
        // No taps — all Inherit
        vm.setPrompt("test")
        vm.submit()
        advanceUntilIdle()
        val req = api.createCalls.single()
        assertEquals(emptyList<String>(), req.hiddenSkills)
        assertEquals(emptyList<String>(), req.forcedOnSkills)
        assertEquals(emptyList<String>(), req.hiddenPlugins)
        assertEquals(emptyList<String>(), req.forcedOnPlugins)
        assertEquals(emptyList<String>(), req.hiddenMcpServers)
        assertEquals(emptyList<String>(), req.forcedOnMcpServers)
    }

    // ── McpDraft validation (the draft class is still used by the Settings add form) ──

    @Test fun `mcp draft validation accepts stdio and http, rejects bad drafts`() {
        assertNull(McpDraft(name = "my-mcp", transport = "stdio", command = "node").validationError)
        assertNull(McpDraft(name = "remote", transport = "http", url = "https://x.com/mcp").validationError)
        assertNotNull("empty name", McpDraft(name = "", transport = "stdio", command = "node").validationError)
        assertNotNull("blank name", McpDraft(name = "   ", transport = "stdio", command = "node").validationError)
        assertNotNull("reserved name", McpDraft(name = "agentic", transport = "stdio", command = "node").validationError)
        assertNotNull("blank transport", McpDraft(name = "x", transport = "", command = "node", url = "https://x").validationError)
        assertNotNull("stdio needs command", McpDraft(name = "x", transport = "stdio", command = "").validationError)
        assertNotNull("http needs url", McpDraft(name = "x", transport = "http", url = "").validationError)
        assertNotNull("non-http scheme", McpDraft(name = "x", transport = "http", url = "ftp://x.com").validationError)
        assertNotNull("no scheme", McpDraft(name = "x", transport = "http", url = "x.com/mcp").validationError)
        assertNotNull("empty host", McpDraft(name = "x", transport = "http", url = "https://").validationError)
        assertNotNull("inner whitespace", McpDraft(name = "x", transport = "http", url = "https://x .com").validationError)
        assertNull(McpDraft(name = "x", transport = "http", url = " https://x.com ").validationError)
    }

    // ── permissionMode ─────────────────────────────────────────────────────────

    @Test fun `submit includes permissionMode in NewSessionReq`() = runTest(dispatcher) {
        api.createResult = "new-id"
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setPrompt("do it")
        vm.setPermissionMode("plan")
        vm.submit()
        advanceUntilIdle()
        assertEquals("plan", api.createCalls.single().permissionMode)
    }

    @Test fun `setPermissionMode null sends null permissionMode in NewSessionReq`() = runTest(dispatcher) {
        api.createResult = "new-id"
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setPrompt("do it")
        vm.setPermissionMode(null)
        vm.submit()
        advanceUntilIdle()
        assertNull(api.createCalls.single().permissionMode)
    }

    // ── claudeMd ───────────────────────────────────────────────────────────────

    @Test fun `initial claudeMd is pre-filled with the default workflow guidance`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        assertEquals(DEFAULT_CLAUDE_MD, vm.uiState.value.claudeMd)
        assertTrue("default mentions the PR workflow", vm.uiState.value.claudeMd.contains("pull request"))
        assertTrue("default mentions the session branch", vm.uiState.value.claudeMd.contains("agentic/<session>"))
        assertTrue("default notes a repo CLAUDE.md may override the workflow",
            vm.uiState.value.claudeMd.contains("may OVERRIDE this workflow"))
    }

    @Test fun `submit sends the pre-filled default claudeMd when unedited`() = runTest(dispatcher) {
        api.createResult = "new-id"
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setPrompt("do it") // leave claudeMd at its pre-filled default
        vm.submit()
        advanceUntilIdle()
        assertEquals(DEFAULT_CLAUDE_MD, api.createCalls.single().claudeMd)
    }

    @Test fun `submit sends claudeMd in NewSessionReq when set`() = runTest(dispatcher) {
        api.createResult = "new-id"
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setPrompt("do it")
        vm.setClaudeMd("Run tests before committing.")
        vm.submit()
        advanceUntilIdle()
        assertEquals("Run tests before committing.", api.createCalls.single().claudeMd)
    }

    @Test fun `submit sends null claudeMd when blank`() = runTest(dispatcher) {
        api.createResult = "new-id"
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setPrompt("do it")
        vm.setClaudeMd("   ") // whitespace-only is treated as "no extra guidance"
        vm.submit()
        advanceUntilIdle()
        assertNull(api.createCalls.single().claudeMd)
    }

    // ── submit failure ─────────────────────────────────────────────────────────

    @Test fun `submit failure sets error and clears submitting, createdId stays null`() = runTest(dispatcher) {
        api.createException = java.io.IOException("timeout")
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setPrompt("something")
        vm.submit()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertNull("createdId must be null on failure", s.createdId)
        assertFalse("submitting must be false after failure", s.submitting)
        assertNotNull("error must be set on failure", s.error)
    }






    // ── end-to-end plugin submit tests with id != name ─────────────────────────
    // These tests MUST fail against pre-fix code that uses `it.name` instead of `it.id`.
    // The plugin id is "github@claude-plugins-official"; name is "github" (short form).


}
