package dev.agentic.ui.newrequest

import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.net.ComponentInfo
import dev.agentic.data.net.McpServerDef
import dev.agentic.data.net.ModelEntry
import dev.agentic.data.net.PluginInfo
import dev.agentic.data.net.RepoList
import dev.agentic.data.net.SkillInfo
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
        assertTrue(s.availableSkills.isEmpty())
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

    @Test fun `init loads availableSkills from sessionsRepo skills`() = runTest(dispatcher) {
        api.skillsResult = listOf(SkillInfo("skill-a"), SkillInfo("skill-b"))
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle()
        assertEquals(listOf(SkillInfo("skill-a"), SkillInfo("skill-b")), vm.uiState.value.availableSkills)
        // All overrides default to Inherit (follow global) — no map entries set.
        assertTrue(vm.uiState.value.skillOverrides.isEmpty())
    }

    @Test fun `init loads availablePlugins and defaults all Inherit`() = runTest(dispatcher) {
        api.pluginsResult = listOf(PluginInfo("superpowers@official"), PluginInfo("github@official"))
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(listOf(PluginInfo("superpowers@official"), PluginInfo("github@official")), s.availablePlugins)
        // Every plugin defaults to Inherit (follow global) — no overrides set.
        assertTrue(s.pluginOverrides.isEmpty())
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

    @Test fun `init loads MCP components from global settings filtering kind mcp`() = runTest(dispatcher) {
        api.globalSettingsResult = listOf(
            ComponentInfo(kind = "skill", id = "sk1", name = "Skill 1", globalEnabled = true),
            ComponentInfo(kind = "mcp",   id = "mcp-a", name = "MCP A", globalEnabled = true),
            ComponentInfo(kind = "plugin", id = "pl1", name = "Plugin 1", globalEnabled = false),
            ComponentInfo(kind = "mcp",   id = "mcp-b", name = "MCP B", globalEnabled = false),
        )
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle()
        val mcp = vm.uiState.value.mcpComponents
        assertEquals(2, mcp.size)
        assertEquals("mcp-a", mcp[0].id)
        assertEquals("mcp-b", mcp[1].id)
    }

    @Test fun `init survives getGlobalSettings failure and leaves mcpComponents empty`() = runTest(dispatcher) {
        api.getGlobalSettingsException = java.io.IOException("server unavailable")
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.mcpComponents.isEmpty())
    }

    // ── form field setters ─────────────────────────────────────────────────────

    @Test fun `setRepos updates selectedRepos`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setRepos(listOf("repoA", "repoB"))
        assertEquals(listOf("repoA", "repoB"), vm.uiState.value.selectedRepos)
    }

    @Test fun `setOverride for skill updates skillOverrides`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setOverride("skill", "sk1", Override.ForceOn)
        assertEquals(Override.ForceOn, vm.uiState.value.skillOverrides["sk1"])
    }

    @Test fun `setOverride for plugin updates pluginOverrides`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setOverride("plugin", "pl1@mkt", Override.ForceOff)
        assertEquals(Override.ForceOff, vm.uiState.value.pluginOverrides["pl1@mkt"])
    }

    @Test fun `setOverride for mcp updates mcpOverrides`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.setOverride("mcp", "mcp-a", Override.ForceOn)
        assertEquals(Override.ForceOn, vm.uiState.value.mcpOverrides["mcp-a"])
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
        api.skillsResult = listOf(SkillInfo("kubectl"), SkillInfo("other-skill"))
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle()
        vm.applyTemplate(template, mapOf("service" to "api", "env" to "prod"))
        val s = vm.uiState.value
        assertEquals("Deploy api to prod", s.prompt)
        assertEquals(listOf("infra-repo"), s.selectedRepos)
        // "kubectl" is listed in template.skills → Inherit; "other-skill" → ForceOff
        assertEquals(Override.Inherit, s.skillOverrides["kubectl"] ?: Override.Inherit)
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
        api.skillsResult = listOf(SkillInfo("skill-a"), SkillInfo("skill-b"), SkillInfo("skill-c"))
        api.pluginsResult = listOf(PluginInfo("superpowers@official"), PluginInfo("github@official"))
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle() // let catalogs load
        vm.setRepos(listOf("r1"))
        // skill-a: ForceOff (hidden), skill-b: ForceOn (forced-on), skill-c: Inherit (neither)
        vm.setOverride("skill", "skill-a", Override.ForceOff)
        vm.setOverride("skill", "skill-b", Override.ForceOn)
        // github: ForceOff (hidden), superpowers: Inherit (neither)
        vm.setOverride("plugin", "github@official", Override.ForceOff)
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
        assertEquals(listOf("github@official"), req.hiddenPlugins)
        assertEquals(emptyList<String>(), req.forcedOnPlugins)
        assertEquals(emptyList<String>(), req.hiddenMcpServers)
        assertEquals(emptyList<String>(), req.forcedOnMcpServers)
        assertEquals(emptyList<McpServerDef>(), req.extraMcpServers)
        assertEquals("build it", req.prompt)
        assertEquals("sonnet", req.model)
        assertEquals("medium", req.effort)
        assertEquals("auto", req.mode)
    }

    @Test fun `submit derives six override lists from Inherit ForceOn ForceOff selections`() = runTest(dispatcher) {
        api.createResult = "new-id"
        api.skillsResult = listOf(SkillInfo("sk1"), SkillInfo("sk2"), SkillInfo("sk3"))
        api.pluginsResult = listOf(PluginInfo("pl1"), PluginInfo("pl2"))
        api.globalSettingsResult = listOf(
            ComponentInfo(kind = "mcp", id = "mcp-a", name = "MCP A", globalEnabled = true),
            ComponentInfo(kind = "mcp", id = "mcp-b", name = "MCP B", globalEnabled = false),
            ComponentInfo(kind = "mcp", id = "mcp-c", name = "MCP C", globalEnabled = true),
        )
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle()
        // Skills: sk1=ForceOn, sk2=ForceOff, sk3=Inherit
        vm.setOverride("skill", "sk1", Override.ForceOn)
        vm.setOverride("skill", "sk2", Override.ForceOff)
        // Plugins: pl1=ForceOff, pl2=Inherit
        vm.setOverride("plugin", "pl1", Override.ForceOff)
        // MCP: mcp-a=Inherit, mcp-b=ForceOn, mcp-c=ForceOff
        vm.setOverride("mcp", "mcp-b", Override.ForceOn)
        vm.setOverride("mcp", "mcp-c", Override.ForceOff)
        vm.setPrompt("test")
        vm.submit()
        advanceUntilIdle()
        val req = api.createCalls.single()
        assertEquals(listOf("sk1"), req.forcedOnSkills)
        assertEquals(listOf("sk2"), req.hiddenSkills)
        assertEquals(listOf("pl1"), req.hiddenPlugins)
        assertEquals(emptyList<String>(), req.forcedOnPlugins)
        assertEquals(listOf("mcp-b"), req.forcedOnMcpServers)
        assertEquals(listOf("mcp-c"), req.hiddenMcpServers)
    }

    @Test fun `all Inherit overrides produce empty lists on submit`() = runTest(dispatcher) {
        api.createResult = "new-id"
        api.skillsResult = listOf(SkillInfo("sk1"), SkillInfo("sk2"))
        api.pluginsResult = listOf(PluginInfo("pl1"))
        api.globalSettingsResult = listOf(
            ComponentInfo(kind = "mcp", id = "mcp-a", name = "MCP A", globalEnabled = true),
        )
        val vm = NewRequestViewModel(sessionsRepo())
        advanceUntilIdle()
        // Leave all overrides at Inherit (default — don't call setOverride at all)
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

    // ── addMcpServer: valid stdio ──────────────────────────────────────────────

    @Test fun `addMcpServer valid stdio server populates extraMcpServers`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.updateMcpDraft(McpDraft(
            name = "my-mcp",
            transport = "stdio",
            command = "/usr/bin/node",
            args = "server.js --port 3000",
        ))
        val err = vm.addMcpServer()
        assertNull("addMcpServer should succeed", err)
        val extra = vm.uiState.value.extraMcpServers
        assertEquals(1, extra.size)
        assertEquals("my-mcp", extra[0].name)
        assertEquals("/usr/bin/node", extra[0].command)
        assertEquals(listOf("server.js", "--port", "3000"), extra[0].args)
        assertNull(extra[0].url)
    }

    @Test fun `addMcpServer valid http server populates extraMcpServers`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.updateMcpDraft(McpDraft(
            name = "remote-mcp",
            transport = "http",
            url = "https://example.com/mcp",
            httpType = "http",
        ))
        val err = vm.addMcpServer()
        assertNull("addMcpServer should succeed", err)
        val extra = vm.uiState.value.extraMcpServers
        assertEquals(1, extra.size)
        assertEquals("remote-mcp", extra[0].name)
        assertEquals("https://example.com/mcp", extra[0].url)
        assertEquals("http", extra[0].type)
        assertNull(extra[0].command)
    }

    @Test fun `addMcpServer clears draft on success`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.updateMcpDraft(McpDraft(name = "test-mcp", transport = "stdio", command = "node"))
        vm.addMcpServer()
        // Draft should be reset after successful add.
        val draft = vm.uiState.value.mcpDraft
        assertEquals("", draft.name)
        assertEquals("", draft.command)
    }

    // ── addMcpServer: invalid inputs rejected ─────────────────────────────────

    @Test fun `addMcpServer rejects empty name`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.updateMcpDraft(McpDraft(name = "", transport = "stdio", command = "node"))
        val err = vm.addMcpServer()
        assertNotNull("empty name should be rejected", err)
        assertTrue(vm.uiState.value.extraMcpServers.isEmpty())
    }

    @Test fun `addMcpServer rejects name agentic`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.updateMcpDraft(McpDraft(name = "agentic", transport = "stdio", command = "node"))
        val err = vm.addMcpServer()
        assertNotNull("name agentic should be rejected", err)
        assertTrue(vm.uiState.value.extraMcpServers.isEmpty())
    }

    @Test fun `addMcpServer rejects stdio with no command`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.updateMcpDraft(McpDraft(name = "my-mcp", transport = "stdio", command = ""))
        val err = vm.addMcpServer()
        assertNotNull("stdio without command should be rejected", err)
        assertTrue(vm.uiState.value.extraMcpServers.isEmpty())
    }

    @Test fun `addMcpServer rejects http with no url`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.updateMcpDraft(McpDraft(name = "my-mcp", transport = "http", url = ""))
        val err = vm.addMcpServer()
        assertNotNull("http without url should be rejected", err)
        assertTrue(vm.uiState.value.extraMcpServers.isEmpty())
    }

    // ── removeMcpServer ────────────────────────────────────────────────────────

    @Test fun `removeMcpServer removes added server by name`() = runTest(dispatcher) {
        val vm = NewRequestViewModel(sessionsRepo())
        vm.updateMcpDraft(McpDraft(name = "srvA", transport = "stdio", command = "node"))
        vm.addMcpServer()
        vm.updateMcpDraft(McpDraft(name = "srvB", transport = "stdio", command = "python"))
        vm.addMcpServer()
        assertEquals(2, vm.uiState.value.extraMcpServers.size)
        vm.removeMcpServer("srvA")
        assertEquals(1, vm.uiState.value.extraMcpServers.size)
        assertEquals("srvB", vm.uiState.value.extraMcpServers[0].name)
    }

    // ── extraMcpServers sent on submit ─────────────────────────────────────────

    @Test fun `submit includes extraMcpServers in NewSessionReq`() = runTest(dispatcher) {
        api.createResult = "new-id"
        val vm = NewRequestViewModel(sessionsRepo())
        vm.updateMcpDraft(McpDraft(name = "my-mcp", transport = "stdio", command = "/usr/bin/node"))
        vm.addMcpServer()
        vm.setPrompt("test")
        vm.submit()
        advanceUntilIdle()
        val req = api.createCalls.single()
        assertEquals(1, req.extraMcpServers.size)
        assertEquals("my-mcp", req.extraMcpServers[0].name)
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
}
