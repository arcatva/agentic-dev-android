# S5b — New Request: MCP Section + Tri-State Chips Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace binary skill/plugin chips with tri-state (Inherit/ForceOn/ForceOff) overrides, add an MCP section with tri-state chips, and add an inline "Add MCP server" form that produces `extraMcpServers` entries, all wired into `NewSessionReq` with the six new fields.

**Architecture:** The VM gains an `Override` enum and per-component maps replacing the binary selected sets; on submit it derives six lists (hiddenSkills/hiddenPlugins/hiddenMcpServers, forcedOnSkills/forcedOnPlugins/forcedOnMcpServers). The MCP catalog is loaded via the existing `getGlobalSettings()` API (filter `kind=="mcp"`). The UI composes a new `TriStateChipRow` and an `AddMcpForm` composable inside the existing `Filters` card.

**Tech Stack:** Kotlin, Jetpack Compose M3 Expressive, kotlinx.serialization, kotlinx-coroutines-test, JVM unit tests via `./gradlew testDebugUnitTest`.

## Global Constraints

- No new heavyweight dependencies; follow existing Compose/VM patterns.
- `./gradlew testDebugUnitTest` must stay green at every task boundary.
- Commit messages end with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- Work only on this session's `agentic/<session>` branch; open a PR, do not push to master.
- `encodeDefaults = true` is already set on the Ktor JSON config; add `@SerialName` where JSON field name differs from Kotlin name; use `explicitNulls = false`-style `@EncodeDefault(NEVER)` or nullable defaults for optional McpServerDef fields.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `app/src/main/java/dev/agentic/data/net/Models.kt` | Modify | Add `McpServerDef`; add 5 new fields to `NewSessionReq` |
| `app/src/main/java/dev/agentic/ui/newrequest/NewRequestViewModel.kt` | Modify | Replace binary sets with tri-state maps; load MCP from globalSettings; add extraMcpServers draft + add/remove |
| `app/src/main/java/dev/agentic/ui/newrequest/NewRequestScreen.kt` | Modify | Replace `ChipPicker` calls with `TriStateChipRow`; add MCP section + `AddMcpForm` |
| `app/src/test/java/dev/agentic/ui/newrequest/NewRequestViewModelTest.kt` | Modify | Update existing tests to tri-state model; add new tri-state + MCP tests |

---

### Task 1: DTOs — `McpServerDef` + five new `NewSessionReq` fields

**Files:**
- Modify: `app/src/main/java/dev/agentic/data/net/Models.kt`

**Interfaces:**
- Produces: `McpServerDef(name, command?, args?, env?, type?, url?, headers?)` and `NewSessionReq` with `forcedOnSkills`, `forcedOnPlugins`, `forcedOnMcpServers`, `hiddenMcpServers`, `extraMcpServers` fields (all default empty).

- [ ] **Step 1: Add `McpServerDef` to `Models.kt`**

Open `app/src/main/java/dev/agentic/data/net/Models.kt`. After the `StagedUpload` data class (around line 218), add:

```kotlin
/** One MCP server to add for this session only. Exactly one transport must be supplied:
 *  either [command] (stdio) OR [url] (http/sse). Name must be non-empty and not "agentic".
 *  Optional fields are omitted from JSON when null (encodeDefaults = false on nullable
 *  fields via @EncodeDefault(NEVER)). */
@Serializable
data class McpServerDef(
    val name: String,
    // stdio transport
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val command: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val args: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val env: Map<String, String>? = null,
    // http/sse transport
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val type: String? = null,      // "http" or "sse"
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val url: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val headers: Map<String, String>? = null,
)
```

Also add the import at the top of the file:
```kotlin
import kotlinx.serialization.EncodeDefault
```

- [ ] **Step 2: Add five new fields to `NewSessionReq`**

In `Models.kt`, find `NewSessionReq` and add the five new fields after `hiddenPlugins`:

```kotlin
@Serializable
data class NewSessionReq(
    val repos: List<String>,
    val skills: List<String>,
    val hiddenSkills: List<String> = emptyList(),
    val hiddenPlugins: List<String> = emptyList(),
    // ── S5b: tri-state per-session overrides ──────────────────────────────────
    /** Skills to FORCE ON for this session (override global-off). */
    val forcedOnSkills: List<String> = emptyList(),
    /** Plugins to FORCE ON for this session (override global-off). */
    val forcedOnPlugins: List<String> = emptyList(),
    /** MCP servers to FORCE ON for this session (override global-off). */
    val forcedOnMcpServers: List<String> = emptyList(),
    /** MCP servers to HIDE from this session (override global-on). */
    val hiddenMcpServers: List<String> = emptyList(),
    /** Extra MCP servers to ADD for this session only (ad-hoc, not globally configured). */
    val extraMcpServers: List<McpServerDef> = emptyList(),
    // ──────────────────────────────────────────────────────────────────────────
    val prompt: String,
    val model: String? = null,
    val effort: String? = null,
    val mode: String? = null,
    val permissionMode: String? = null,
    val claudeMd: String? = null,
    val stagedUploads: List<StagedUpload> = emptyList(),
)
```

- [ ] **Step 3: Verify compile**

```bash
cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android && ./gradlew compileDebugKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL or only warnings (no errors).

- [ ] **Step 4: Commit**

```bash
cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
git add app/src/main/java/dev/agentic/data/net/Models.kt
git commit -m "$(cat <<'EOF'
feat(s5b): add McpServerDef DTO and five new NewSessionReq fields

Adds McpServerDef (stdio/http transports, nullable optional fields with
@EncodeDefault(NEVER)) and wires forcedOnSkills, forcedOnPlugins,
forcedOnMcpServers, hiddenMcpServers, extraMcpServers into NewSessionReq
(all default empty for backward compat).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: ViewModel — tri-state model + MCP catalog + extraMcpServers

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/newrequest/NewRequestViewModel.kt`

**Interfaces:**
- Consumes: `ComponentInfo(kind, id, name, ...)` from `getGlobalSettings()` (already on `AgenticApi`); `McpServerDef` from Task 1.
- Produces: `NewRequestUiState` with `skillOverrides: Map<String, Override>`, `pluginOverrides: Map<String, Override>`, `mcpComponents: List<ComponentInfo>`, `mcpOverrides: Map<String, Override>`, `extraMcpServers: List<McpServerDef>`, `mcpDraft: McpDraft`; VM methods `setOverride(kind, id, Override)`, `addMcpServer(McpServerDef)`, `removeMcpServer(name)`, `updateMcpDraft(McpDraft)`.

- [ ] **Step 1: Define `Override` enum and `McpDraft` in the ViewModel file**

At the top of `NewRequestViewModel.kt` (after imports, before `DEFAULT_CLAUDE_MD`), add:

```kotlin
/** Per-component tri-state session override.
 *  - [Inherit]: follow global setting (component appears in neither forced-on nor hidden list).
 *  - [ForceOn]: force this component ON for this session (even if globally disabled).
 *  - [ForceOff]: force this component OFF for this session (even if globally enabled).
 *  Tap cycle: Inherit → ForceOn → ForceOff → Inherit. */
enum class Override { Inherit, ForceOn, ForceOff }

/** Draft state for the "Add MCP server" inline form.
 *  [transport] is "stdio" or "http". Fields not relevant to the selected transport
 *  are retained in state but ignored on add (the form shows only the relevant ones). */
data class McpDraft(
    val name: String = "",
    val transport: String = "stdio",
    // stdio fields
    val command: String = "",
    val args: String = "",       // space-separated, split on add
    val env: String = "",        // KEY=VALUE lines, split on add
    // http/sse fields
    val url: String = "",
    val httpType: String = "http",  // "http" or "sse"
    val headers: String = "",       // KEY=VALUE lines, split on add
) {
    /** Validation error message, or null when the draft is submittable. */
    val validationError: String? get() = when {
        name.isBlank() -> "Name is required"
        name.trim() == "agentic" -> "Name must not be \"agentic\""
        transport == "stdio" && command.isBlank() -> "Command is required for stdio transport"
        transport == "http" && url.isBlank() -> "URL is required for HTTP/SSE transport"
        else -> null
    }
    val isValid: Boolean get() = validationError == null
}
```

- [ ] **Step 2: Replace `selectedSkills`/`selectedPlugins` in `NewRequestUiState` with override maps and add MCP fields**

Replace the existing `NewRequestUiState` data class:

```kotlin
data class NewRequestUiState(
    val availableRepos: List<String> = emptyList(),
    val availableSkills: List<SkillInfo> = emptyList(),
    val templates: List<Template> = emptyList(),
    val selectedRepos: List<String> = emptyList(),
    // ── Tri-state override maps (replaces binary selectedSkills/selectedPlugins) ──
    // Default is Inherit for all, meaning "follow global setting".
    // On submit: ForceOff → hiddenX, ForceOn → forcedOnX, Inherit → neither.
    val skillOverrides: Map<String, Override> = emptyMap(),
    val pluginOverrides: Map<String, Override> = emptyMap(),
    // ── MCP components (fetched from GET /api/global-settings, kind=="mcp") ──
    val availablePlugins: List<PluginInfo> = emptyList(),
    val mcpComponents: List<ComponentInfo> = emptyList(),
    val mcpOverrides: Map<String, Override> = emptyMap(),
    // ── Extra MCP servers (inline-add form) ──
    val extraMcpServers: List<McpServerDef> = emptyList(),
    val mcpDraft: McpDraft = McpDraft(),
    // ── Existing fields unchanged ──
    val prompt: String = "",
    val claudeMd: String = DEFAULT_CLAUDE_MD,
    val model: String? = ModelCatalog.defaultSessionStartModelKey(),
    val effort: String? = "xhigh",
    val mode: String? = "ultracode",
    val permissionMode: String? = null,
    val attachments: List<PendingAttachment> = emptyList(),
    val submitting: Boolean = false,
    val createdId: String? = null,
    val error: String? = null,
)
```

Note: `selectedSkills` and `selectedPlugins` are REMOVED. The existing tests that call `vm.setSkills()` and `vm.setPlugins()` will be updated in Task 4.

- [ ] **Step 3: Update `init` block — replace skills/plugins loading and add MCP loading**

In `NewRequestViewModel.init`, replace the skills and plugins loading blocks and add an MCP loading block. The full revised `init` block (replace the existing one in its entirety):

```kotlin
init {
    viewModelScope.launch {
        try {
            val repoList = sessionsRepo.repos()
            _uiState.update { it.copy(availableRepos = repoList.local + repoList.remote) }
        } catch (e: Exception) { AppLog.d("VM", "catalog repos load failed: ${e.message}") }
    }
    viewModelScope.launch {
        try {
            val skills = sessionsRepo.skills()
            // Default every skill to Inherit (follow global).
            _uiState.update { it.copy(availableSkills = skills) }
        } catch (e: Exception) { AppLog.d("VM", "catalog skills load failed: ${e.message}") }
    }
    viewModelScope.launch {
        try {
            val plugins = sessionsRepo.plugins()
            // Default every plugin to Inherit (follow global).
            _uiState.update { it.copy(availablePlugins = plugins) }
        } catch (e: Exception) { AppLog.d("VM", "catalog plugins load failed: ${e.message}") }
    }
    viewModelScope.launch {
        try {
            // Fetch MCP components from global settings (kind == "mcp").
            val mcpList = sessionsRepo.globalSettings().filter { it.kind == "mcp" }
            _uiState.update { it.copy(mcpComponents = mcpList) }
        } catch (e: Exception) { AppLog.d("VM", "catalog mcp load failed: ${e.message}") }
    }
    viewModelScope.launch {
        try {
            val templates = sessionsRepo.templates()
            _uiState.update { it.copy(templates = templates) }
        } catch (e: Exception) { AppLog.d("VM", "catalog templates load failed: ${e.message}") }
    }
    viewModelScope.launch {
        try {
            sessionsRepo.modelCatalog()
        } catch (e: Exception) { AppLog.d("VM", "catalog modelCatalog load failed: ${e.message}") }
    }
    viewModelScope.launch {
        try {
            sessionsRepo.sessionStartModelCatalog()
            val defaultModel = ModelCatalog.defaultSessionStartModelKey()
            if (defaultModel != null) {
                _uiState.update { if (it.model == null) it.copy(model = defaultModel) else it }
            }
        } catch (e: Exception) { AppLog.d("VM", "catalog sessionStartModelCatalog load failed: ${e.message}") }
    }
}
```

- [ ] **Step 4: Replace `setSkills`/`setPlugins` with `setOverride` and add MCP/draft methods**

Replace the existing `setSkills` and `setPlugins` methods and add new ones:

```kotlin
fun setRepos(repos: List<String>) { _uiState.update { it.copy(selectedRepos = repos) } }

/** Set the tri-state override for a skill/plugin/mcp component. [kind] is "skill", "plugin", or "mcp". */
fun setOverride(kind: String, id: String, override: Override) {
    _uiState.update { s ->
        when (kind) {
            "skill"  -> s.copy(skillOverrides  = s.skillOverrides  + (id to override))
            "plugin" -> s.copy(pluginOverrides  = s.pluginOverrides + (id to override))
            "mcp"    -> s.copy(mcpOverrides     = s.mcpOverrides    + (id to override))
            else -> s
        }
    }
}

// ── Legacy setters kept for applyTemplate compat (skills → ForceOff for non-listed) ──
// applyTemplate sets selectedSkills from template.skills; map these to ForceOff for excluded skills.
// (Internal: used only by applyTemplate, not by the UI directly.)
internal fun setSkillsFromTemplate(skillNames: List<String>, available: List<SkillInfo>) {
    val overrides = available.associate { s ->
        s.name to if (skillNames.isEmpty() || s.name in skillNames) Override.Inherit else Override.ForceOff
    }
    _uiState.update { it.copy(skillOverrides = overrides) }
}

fun setPrompt(prompt: String) { _uiState.update { it.copy(prompt = prompt) } }
fun setClaudeMd(claudeMd: String) { _uiState.update { it.copy(claudeMd = claudeMd) } }
fun setModel(model: String?) { _uiState.update { it.copy(model = model) } }
fun setEffort(effort: String?) { _uiState.update { it.copy(effort = effort) } }
fun setMode(mode: String?) { _uiState.update { it.copy(mode = mode) } }
fun setPermissionMode(permissionMode: String?) { _uiState.update { it.copy(permissionMode = permissionMode) } }

/** Update the "Add MCP server" draft form state. */
fun updateMcpDraft(draft: McpDraft) { _uiState.update { it.copy(mcpDraft = draft) } }

/** Validate and add an MCP server from the draft form. Returns an error string on failure, null on success. */
fun addMcpServer(): String? {
    val draft = _uiState.value.mcpDraft
    val err = draft.validationError
    if (err != null) return err
    val def = buildMcpServerDef(draft)
    _uiState.update { it.copy(extraMcpServers = it.extraMcpServers + def, mcpDraft = McpDraft()) }
    return null
}

/** Remove an added MCP server by name. */
fun removeMcpServer(name: String) {
    _uiState.update { it.copy(extraMcpServers = it.extraMcpServers.filterNot { s -> s.name == name }) }
}

private fun buildMcpServerDef(draft: McpDraft): McpServerDef {
    return if (draft.transport == "stdio") {
        McpServerDef(
            name = draft.name.trim(),
            command = draft.command.trim(),
            args = draft.args.trim().takeIf { it.isNotEmpty() }?.split("\\s+".toRegex()),
            env = parseKeyValueLines(draft.env),
        )
    } else {
        McpServerDef(
            name = draft.name.trim(),
            url = draft.url.trim(),
            type = draft.httpType,
            headers = parseKeyValueLines(draft.headers),
        )
    }
}

/** Parse "KEY=VALUE\nKEY2=VALUE2" into a map. Empty/blank input → null (omitted from JSON). */
private fun parseKeyValueLines(text: String): Map<String, String>? {
    if (text.isBlank()) return null
    return text.lines()
        .mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq > 0) line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            else null
        }
        .takeIf { it.isNotEmpty() }
        ?.toMap()
}
```

- [ ] **Step 5: Update `applyTemplate` to use `setSkillsFromTemplate`**

Find the `applyTemplate` function and update it to use the new override model:

```kotlin
fun applyTemplate(t: Template, vars: Map<String, String>) {
    AppLog.d("VM", "applying template: ${t.name}")
    val s = _uiState.value
    _uiState.update {
        it.copy(
            prompt = applyTemplate(t.promptBody, vars),
            selectedRepos = t.repos,
            model = t.model,
            effort = t.effort,
            mode = t.mode,
            permissionMode = t.permissionMode,
        )
    }
    // Map template skill list to overrides: unlisted skills → ForceOff; listed/all → Inherit.
    setSkillsFromTemplate(t.skills, s.availableSkills)
}
```

- [ ] **Step 6: Update `submit()` to use tri-state derivation**

In the `submit()` function, replace the `val req = NewSessionReq(...)` block:

```kotlin
val req = NewSessionReq(
    repos = s.selectedRepos,
    skills = emptyList(),
    // ForceOff → hidden; ForceOn → forcedOn; Inherit → neither
    hiddenSkills     = s.availableSkills.map { it.name }.filter { s.skillOverrides[it] == Override.ForceOff },
    forcedOnSkills   = s.availableSkills.map { it.name }.filter { s.skillOverrides[it] == Override.ForceOn },
    hiddenPlugins    = s.availablePlugins.map { it.name }.filter { s.pluginOverrides[it] == Override.ForceOff },
    forcedOnPlugins  = s.availablePlugins.map { it.name }.filter { s.pluginOverrides[it] == Override.ForceOn },
    hiddenMcpServers   = s.mcpComponents.map { it.id }.filter { s.mcpOverrides[it] == Override.ForceOff },
    forcedOnMcpServers = s.mcpComponents.map { it.id }.filter { s.mcpOverrides[it] == Override.ForceOn },
    extraMcpServers  = s.extraMcpServers,
    prompt = composePromptWithMarker(s.prompt, finalAtts),
    model = s.model,
    effort = if (s.mode == "ultracode") "xhigh" else s.effort,
    mode = s.mode,
    permissionMode = s.permissionMode,
    claudeMd = s.claudeMd.ifBlank { null },
    stagedUploads = staged,
)
```

- [ ] **Step 7: Add `globalSettings()` to `SessionsRepository`**

Open `app/src/main/java/dev/agentic/data/repo/SessionsRepository.kt`. Check existing imports, then add a `globalSettings()` method that delegates to the API. Find the end of existing public methods and add:

```kotlin
/** Fetch globally-configured components (skills, plugins, MCP).
 *  Delegates directly to [AgenticApi.getGlobalSettings] — no caching needed here
 *  since this is only called once per VM init and the GlobalSettingsViewModel has its own load. */
suspend fun globalSettings(): List<dev.agentic.data.net.ComponentInfo> = api.getGlobalSettings()
```

Also add the import if not already present:
```kotlin
import dev.agentic.data.net.ComponentInfo
```

- [ ] **Step 8: Verify compile**

```bash
cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android && ./gradlew compileDebugKotlin 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL or only warnings.

- [ ] **Step 9: Commit**

```bash
cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
git add app/src/main/java/dev/agentic/ui/newrequest/NewRequestViewModel.kt \
        app/src/main/java/dev/agentic/data/repo/SessionsRepository.kt
git commit -m "$(cat <<'EOF'
feat(s5b): tri-state override model in NewRequestViewModel + MCP catalog load

Replaces binary selectedSkills/selectedPlugins with per-component Override
(Inherit/ForceOn/ForceOff) maps. Loads MCP components via globalSettings()
(filter kind==mcp). Adds McpDraft form state, addMcpServer/removeMcpServer,
and buildMcpServerDef helper. Submit derives six override lists from the maps.
Adds globalSettings() delegation in SessionsRepository.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Tests — update existing tests + add S5b tests

**Files:**
- Modify: `app/src/test/java/dev/agentic/ui/newrequest/NewRequestViewModelTest.kt`

**Interfaces:**
- Consumes: `Override` enum, `McpDraft`, `ComponentInfo`, `McpServerDef`, `vm.setOverride(kind, id, Override)`, `vm.addMcpServer()`, `vm.removeMcpServer(name)`, `vm.updateMcpDraft(McpDraft)` from Task 2.

- [ ] **Step 1: Update failing existing tests**

The existing tests that reference `selectedSkills` / `selectedPlugins` / `vm.setSkills` / `vm.setPlugins` must be updated:

**`init loads availableSkills from sessionsRepo skills`** — remove the `selectedSkills` assertion (skills now default to all-Inherit, not pre-selected):
```kotlin
@Test fun `init loads availableSkills from sessionsRepo skills`() = runTest(dispatcher) {
    api.skillsResult = listOf(SkillInfo("skill-a"), SkillInfo("skill-b"))
    val vm = NewRequestViewModel(sessionsRepo())
    advanceUntilIdle()
    assertEquals(listOf(SkillInfo("skill-a"), SkillInfo("skill-b")), vm.uiState.value.availableSkills)
    // All overrides default to Inherit (follow global).
    assertTrue(vm.uiState.value.skillOverrides.isEmpty())
}
```

**`init loads availablePlugins and defaults all selected`** — update to check overrides are empty (all Inherit):
```kotlin
@Test fun `init loads availablePlugins and defaults all Inherit`() = runTest(dispatcher) {
    api.pluginsResult = listOf(PluginInfo("superpowers@official"), PluginInfo("github@official"))
    val vm = NewRequestViewModel(sessionsRepo())
    advanceUntilIdle()
    val s = vm.uiState.value
    assertEquals(listOf(PluginInfo("superpowers@official"), PluginInfo("github@official")), s.availablePlugins)
    // Every plugin defaults to Inherit (follow global) — no overrides set.
    assertTrue(s.pluginOverrides.isEmpty())
}
```

**`setSkills updates selectedSkills`** — replace with setOverride:
```kotlin
@Test fun `setOverride for skill updates skillOverrides`() = runTest(dispatcher) {
    val vm = NewRequestViewModel(sessionsRepo())
    vm.setOverride("skill", "sk1", Override.ForceOn)
    assertEquals(Override.ForceOn, vm.uiState.value.skillOverrides["sk1"])
}
```

**`setPlugins updates selectedPlugins`** — replace with setOverride:
```kotlin
@Test fun `setOverride for plugin updates pluginOverrides`() = runTest(dispatcher) {
    val vm = NewRequestViewModel(sessionsRepo())
    vm.setOverride("plugin", "pl1@mkt", Override.ForceOff)
    assertEquals(Override.ForceOff, vm.uiState.value.pluginOverrides["pl1@mkt"])
}
```

**`submit sends correct NewSessionReq to api`** — replace with tri-state version:
```kotlin
@Test fun `submit sends correct NewSessionReq to api with tri-state overrides`() = runTest(dispatcher) {
    api.createResult = "new-id"
    api.skillsResult = listOf(SkillInfo("skill-a"), SkillInfo("skill-b"), SkillInfo("skill-c"))
    api.pluginsResult = listOf(PluginInfo("superpowers@official"), PluginInfo("github@official"))
    val vm = NewRequestViewModel(sessionsRepo())
    advanceUntilIdle()
    vm.setRepos(listOf("r1"))
    // skill-a: ForceOff (hidden), skill-b: ForceOn (forced), skill-c: Inherit (neither)
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
```

Also update `initial state has empty catalogs and form fields` — remove references to `selectedSkills`/`selectedPlugins` if they existed (the test currently doesn't check those, so it should pass as-is once we verify the field names).

- [ ] **Step 2: Add new tri-state and MCP tests**

Add these new test functions after the existing tests:

```kotlin
// ── tri-state override derivation ─────────────────────────────────────────────

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

// ── MCP catalog load ───────────────────────────────────────────────────────────

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

// ── addMcpServer: valid stdio ──────────────────────────────────────────────────

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

// ── addMcpServer: invalid inputs rejected ─────────────────────────────────────

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

// ── removeMcpServer ────────────────────────────────────────────────────────────

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

// ── extraMcpServers sent on submit ─────────────────────────────────────────────

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
```

- [ ] **Step 3: Add missing imports to test file**

At the top of `NewRequestViewModelTest.kt`, add:
```kotlin
import dev.agentic.data.net.ComponentInfo
import dev.agentic.data.net.McpServerDef
```

- [ ] **Step 4: Run the tests**

```bash
cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android && ./gradlew testDebugUnitTest 2>&1 | tail -40
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
git add app/src/test/java/dev/agentic/ui/newrequest/NewRequestViewModelTest.kt
git commit -m "$(cat <<'EOF'
test(s5b): update NewRequestViewModelTest for tri-state model + add MCP tests

Updates existing tests (selectedSkills → skillOverrides, binary toggle →
setOverride). Adds: Inherit/ForceOn/ForceOff derivation of all six lists;
MCP catalog load + failure path; valid stdio and http server add; invalid
add cases (empty name, "agentic", no command, no url); removeMcpServer;
extraMcpServers in submit req.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: UI — tri-state chip composable + MCP section + Add MCP form

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/newrequest/NewRequestScreen.kt`

**Interfaces:**
- Consumes: `Override` enum, `McpDraft`, `ComponentInfo`, `McpServerDef`, `vm.setOverride()`, `vm.updateMcpDraft()`, `vm.addMcpServer()`, `vm.removeMcpServer()`, `s.mcpComponents`, `s.mcpOverrides`, `s.extraMcpServers`, `s.mcpDraft` from Tasks 2-3.

- [ ] **Step 1: Add imports to `NewRequestScreen.kt`**

Add the following to the import block:

```kotlin
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.AssistChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.text.style.TextDecoration
import dev.agentic.data.net.ComponentInfo
import dev.agentic.data.net.McpServerDef
```

Note: Some of these may already be imported. Only add what is missing.

- [ ] **Step 2: Add `TriStateChip` composable (private, after existing private helpers)**

After the closing brace of `TemplateVarDialog`, add:

```kotlin
/**
 * A single chip with three visual states corresponding to [Override]:
 * - [Override.Inherit]: outlined (FilterChip selected=false, default look) — "follow global"
 * - [Override.ForceOn]: filled with check icon (FilterChip selected=true, cyan accent) — "force on"
 * - [Override.ForceOff]: outlined + dimmed + strikethrough label — "force off"
 *
 * Tap cycles Inherit → ForceOn → ForceOff → Inherit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriStateChip(
    label: String,
    override: Override,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isForceOn  = override == Override.ForceOn
    val isForceOff = override == Override.ForceOff

    val contentDesc = when (override) {
        Override.Inherit  -> "$label: follow global setting (tap to force on)"
        Override.ForceOn  -> "$label: forced ON for this session (tap to force off)"
        Override.ForceOff -> "$label: forced OFF for this session (tap to reset)"
    }

    // Color: force-on uses cyan accent; inherit/off use default chip colors.
    val chipColors = if (isForceOn) {
        FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentCyanContainer,
            selectedLabelColor = OnAccentCyanContainer,
            selectedLeadingIconColor = OnAccentCyanContainer,
        )
    } else {
        FilterChipDefaults.filterChipColors()
    }

    // Dim the entire chip when force-off.
    val alpha = if (isForceOff) 0.45f else 1f

    FilterChip(
        selected = isForceOn,
        onClick = onCycle,
        label = {
            Text(
                text = label,
                textDecoration = if (isForceOff) TextDecoration.LineThrough else TextDecoration.None,
            )
        },
        leadingIcon = when {
            isForceOn  -> {{ Icon(Icons.Rounded.Check, contentDescription = null) }}
            isForceOff -> {{ Icon(Icons.Rounded.Block, contentDescription = null) }}
            else       -> null
        },
        colors = chipColors,
        modifier = modifier.alpha(alpha),
    )
}

/** Cycle the [Override] on tap: Inherit → ForceOn → ForceOff → Inherit. */
private fun Override.cycle(): Override = when (this) {
    Override.Inherit  -> Override.ForceOn
    Override.ForceOn  -> Override.ForceOff
    Override.ForceOff -> Override.Inherit
}
```

Add `import androidx.compose.ui.draw.alpha` if not already present.

- [ ] **Step 3: Add `TriStateChipPicker` composable (replaces `ChipPicker` for skill/plugin)**

After `TriStateChip`, add:

```kotlin
/**
 * A chip-group section with an inline filter field. Each chip cycles through tri-state
 * [Override] on tap. Empty filter shows all; type to narrow. Chips keep source order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriStateChipPicker(
    label: String,
    options: List<String>,             // option ids/names
    overrides: Map<String, Override>,  // current override per id
    onCycle: (String) -> Unit,         // called with the id to cycle
    displayLabel: (String) -> String = { it },
) {
    var q by remember { mutableStateOf("") }
    val shown = options.filter {
        q.isBlank() || it.contains(q, ignoreCase = true) || displayLabel(it).contains(q, ignoreCase = true)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTextField(
            value = q,
            onValueChange = { q = it },
            placeholder = "Filter ${label.lowercase()}",
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = "Filter ${label.lowercase()}",
                     tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = {
                if (q.isNotEmpty()) {
                    IconButton(onClick = { q = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear filter",
                             tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            shape = MaterialTheme.shapes.small,
            colors = cardFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            shown.forEach { id ->
                TriStateChip(
                    label = displayLabel(id),
                    override = overrides[id] ?: Override.Inherit,
                    onCycle = { onCycle(id) },
                )
            }
        }
    }
}
```

- [ ] **Step 4: Add `AddMcpForm` composable**

After `TriStateChipPicker`, add:

```kotlin
/**
 * Inline form for adding an MCP server. Shows a transport toggle (stdio/http); stdio reveals
 * command + optional args and env; http reveals url + type (http/sse) + optional headers.
 * Client-side validation mirrors the backend contract.
 *
 * [draft] is the current form state; [onDraftChange] is called on every edit.
 * [onAdd] is called when the user taps "Add" with a valid draft; it returns a nullable error string
 * (non-null = show error, stay open; null = success, form was cleared by the VM).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddMcpForm(
    draft: McpDraft,
    onDraftChange: (McpDraft) -> Unit,
    onAdd: () -> String?,
) {
    var localError by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Transport toggle: stdio | http
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf("stdio", "http").forEachIndexed { i, t ->
                SegmentedButton(
                    selected = draft.transport == t,
                    onClick = { onDraftChange(draft.copy(transport = t)) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                    label = { Text(if (t == "stdio") "stdio" else "HTTP / SSE") },
                )
            }
        }

        // Name field (always shown)
        AppTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            label = "Server name",
            placeholder = "e.g. my-mcp",
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = cardFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (draft.transport == "stdio") {
            AppTextField(
                value = draft.command,
                onValueChange = { onDraftChange(draft.copy(command = it)) },
                label = "Command",
                placeholder = "/usr/bin/node server.js",
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = cardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = draft.args,
                onValueChange = { onDraftChange(draft.copy(args = it)) },
                label = "Args (optional, space-separated)",
                placeholder = "--port 3000 --verbose",
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = cardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = draft.env,
                onValueChange = { onDraftChange(draft.copy(env = it)) },
                label = "Env vars (optional, KEY=VALUE lines)",
                placeholder = "NODE_ENV=production\nDEBUG=mcp:*",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                shape = MaterialTheme.shapes.small,
                colors = cardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // HTTP/SSE transport type toggle
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("http", "sse").forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = draft.httpType == t,
                        onClick = { onDraftChange(draft.copy(httpType = t)) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                        label = { Text(t.uppercase()) },
                    )
                }
            }
            AppTextField(
                value = draft.url,
                onValueChange = { onDraftChange(draft.copy(url = it)) },
                label = "URL",
                placeholder = "https://example.com/mcp",
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = cardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = draft.headers,
                onValueChange = { onDraftChange(draft.copy(headers = it)) },
                label = "Headers (optional, KEY=VALUE lines)",
                placeholder = "Authorization=Bearer token\nX-Custom=value",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                shape = MaterialTheme.shapes.small,
                colors = cardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        localError?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                val err = onAdd()
                localError = err
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Text("  Add MCP server")
        }
    }
}
```

- [ ] **Step 5: Update the Filters card in `NewRequestScreen` body**

In the `NewRequestScreen` composable body, find the `SectionCard("Filters")` block and replace it:

```kotlin
// ── Card 1 · Filters: repo + skill + plugin (tri-state) + MCP ──────────────
SectionCard("Filters") {
    ChipPicker(
        label = "Repos",
        options = s.availableRepos,
        selected = s.selectedRepos.toSet(),
        onToggle = { repo ->
            val updated = if (repo in s.selectedRepos) s.selectedRepos - repo
                          else s.selectedRepos + repo
            realVm.setRepos(updated)
        },
    )
    // Skills — tri-state: tap cycles Inherit → ForceOn → ForceOff → Inherit
    if (s.availableSkills.isNotEmpty()) {
        TriStateChipPicker(
            label = "Skills",
            options = s.availableSkills.map { it.name },
            overrides = s.skillOverrides,
            onCycle = { id -> realVm.setOverride("skill", id, (s.skillOverrides[id] ?: Override.Inherit).cycle()) },
        )
    }
    // Plugins — tri-state
    if (s.availablePlugins.isNotEmpty()) {
        TriStateChipPicker(
            label = "Plugins",
            options = s.availablePlugins.map { it.name },
            overrides = s.pluginOverrides,
            onCycle = { id -> realVm.setOverride("plugin", id, (s.pluginOverrides[id] ?: Override.Inherit).cycle()) },
            displayLabel = { it.substringBefore('@') },
        )
    }
    // MCP — tri-state chips for globally configured MCP servers
    if (s.mcpComponents.isNotEmpty() || true) {  // always show section so Add form is accessible
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "MCP servers",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (s.mcpComponents.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    s.mcpComponents.forEach { comp ->
                        TriStateChip(
                            label = comp.name,
                            override = s.mcpOverrides[comp.id] ?: Override.Inherit,
                            onCycle = {
                                val cur = s.mcpOverrides[comp.id] ?: Override.Inherit
                                realVm.setOverride("mcp", comp.id, cur.cycle())
                            },
                        )
                    }
                }
            }
            // Extra (ad-hoc) servers added via the form below — shown as removable chips
            if (s.extraMcpServers.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    s.extraMcpServers.forEach { srv ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text(srv.name) },
                            trailingIcon = {
                                IconButton(onClick = { realVm.removeMcpServer(srv.name) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Remove ${srv.name}")
                                }
                            },
                        )
                    }
                }
            }
            // Collapsible "Add MCP server" form
            var addMcpExpanded by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { addMcpExpanded = !addMcpExpanded }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Add MCP server",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (addMcpExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (addMcpExpanded) "Collapse Add MCP" else "Expand Add MCP",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = addMcpExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                AddMcpForm(
                    draft = s.mcpDraft,
                    onDraftChange = realVm::updateMcpDraft,
                    onAdd = {
                        val err = realVm.addMcpServer()
                        if (err == null) addMcpExpanded = false
                        err
                    },
                )
            }
        }
    }
}
```

- [ ] **Step 6: Verify compile**

```bash
cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android && ./gradlew compileDebugKotlin 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL or warnings only.

- [ ] **Step 7: Run all unit tests**

```bash
cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android && ./gradlew testDebugUnitTest 2>&1 | tail -40
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
git add app/src/main/java/dev/agentic/ui/newrequest/NewRequestScreen.kt
git commit -m "$(cat <<'EOF'
feat(s5b): tri-state chips + MCP section + Add MCP form in NewRequestScreen

Replaces binary ChipPicker for skills/plugins with TriStateChipPicker
(tap cycles Inherit→ForceOn→ForceOff→Inherit; visual: outline=inherit,
filled+check=on, dim+strikethrough=off). Adds MCP section: tri-state chips
for globally-configured MCP servers + collapsible AddMcpForm (stdio/http
transport toggle, client-side validation). Extra servers show as removable
InputChips.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Write `.superpowers/sdd/s5b-report.md` and final verification

**Files:**
- Create: `.superpowers/sdd/s5b-report.md`

- [ ] **Step 1: Run final `testDebugUnitTest`**

```bash
cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android && ./gradlew testDebugUnitTest 2>&1 | tail -50
```

Capture the tail output for inclusion in the report.

- [ ] **Step 2: Write the SDD report**

Create `.superpowers/sdd/s5b-report.md` with this template (fill in actual test output + SHAs):

```markdown
# S5b Implementation Report

## Files Changed

- `app/src/main/java/dev/agentic/data/net/Models.kt` — Added `McpServerDef` DTO; added 5 new fields to `NewSessionReq` (`forcedOnSkills`, `forcedOnPlugins`, `forcedOnMcpServers`, `hiddenMcpServers`, `extraMcpServers`).
- `app/src/main/java/dev/agentic/data/repo/SessionsRepository.kt` — Added `globalSettings()` delegation method.
- `app/src/main/java/dev/agentic/ui/newrequest/NewRequestViewModel.kt` — Added `Override` enum, `McpDraft` data class; replaced binary selected sets with `skillOverrides/pluginOverrides/mcpOverrides` maps; added MCP catalog load; added `addMcpServer`, `removeMcpServer`, `updateMcpDraft`, `setOverride`; updated `submit()` to derive all six lists.
- `app/src/main/java/dev/agentic/ui/newrequest/NewRequestScreen.kt` — Added `TriStateChip`, `TriStateChipPicker`, `AddMcpForm`; replaced ChipPicker calls for skills/plugins with TriStateChipPicker; added MCP section with tri-state chips, extra servers list, collapsible Add form.
- `app/src/test/java/dev/agentic/ui/newrequest/NewRequestViewModelTest.kt` — Updated existing tests; added 12 new tests covering all six lists on submit, MCP catalog load, stdio/http valid add, invalid add cases, removeMcpServer, extraMcpServers in submit.

## Tri-State Model

`Override { Inherit, ForceOn, ForceOff }` is a per-component enum (default Inherit).

On submit, the VM iterates available components and derives:
- `hiddenSkills/hiddenPlugins/hiddenMcpServers` = ids where override == ForceOff
- `forcedOnSkills/forcedOnPlugins/forcedOnMcpServers` = ids where override == ForceOn
- Inherit ids appear in neither list

Backward-compatible: all Inherit (default) → all six lists empty → same wire payload as before S5b.

## MCP Add Form Validation

`McpDraft.validationError` performs client-side checks in order:
1. Name blank → "Name is required"
2. Name == "agentic" → "Name must not be "agentic""
3. transport=="stdio" && command blank → "Command is required for stdio transport"
4. transport=="http" && url blank → "URL is required for HTTP/SSE transport"

Exactly-one-transport is enforced structurally: the UI only populates the fields for the selected transport; `buildMcpServerDef` only sets stdio fields when transport=="stdio" and http fields otherwise.

## Test Result

```
<PASTE TAIL OF ./gradlew testDebugUnitTest OUTPUT HERE>
```

## Concerns / Deviations

- `SingleChoiceSegmentedButtonRow` requires M3 Expressive API (already pinned to 1.4.0-alpha18 per CLAUDE.md — no issue).
- `AppTextField` does not expose `label` as a String parameter in all overloads — if the label-as-string overload is absent, use a `Text(label)` lambda for the `label` slot.
- The `ChipPicker` composable is retained (still used by Repos which stays binary select/deselect).
- No visual QA (no emulator): screens are built to existing patterns; human pass recommended post-merge.
```

- [ ] **Step 3: Commit report**

```bash
cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
mkdir -p .superpowers/sdd
git add .superpowers/sdd/s5b-report.md
git commit -m "$(cat <<'EOF'
docs(s5b): add S5b implementation report

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Task |
|---|---|
| `McpServerDef` DTO (name, command, args, env, type, url, headers) | Task 1 |
| 5 new `NewSessionReq` fields (all default empty) | Task 1 |
| `Override { Inherit, ForceOn, ForceOff }` per-component enum | Task 2 |
| Fetch MCP via `getGlobalSettings()`, filter `kind=="mcp"` | Task 2 |
| On submit derive hidden/forcedOn for skills/plugins/mcp | Task 2 |
| `extraMcpServers: List<McpServerDef>` with add/remove + draft | Task 2 |
| Tap cycles Inherit→ForceOn→ForceOff→Inherit | Task 4 |
| Distinct visual per state (outline/filled+check/dim+strikethrough) | Task 4 |
| Accessible content description per state | Task 4 |
| MCP section: tri-state chips + Add MCP form | Task 4 |
| Transport toggle (stdio/http) + field reveal | Task 4 |
| Client-side validation mirroring backend | Tasks 2 + 4 |
| Added servers show as removable chips | Task 4 |
| Existing tests updated (ForceOff = old hidden) | Task 3 |
| New tests: 6 lists from tri-state | Task 3 |
| New tests: valid stdio/http add | Task 3 |
| New tests: invalid add cases (no name, agentic, no cmd, no url) | Task 3 |
| New tests: ForceOn+ForceOff impossible by construction (single map) | Task 3 (structural) |
| `testDebugUnitTest` green | Task 3 + Task 5 |
| Report at `.superpowers/sdd/s5b-report.md` | Task 5 |

**Placeholder scan:** No TBD, TODO, or "similar to" references. All code blocks are complete.

**Type consistency check:**
- `Override` enum defined in `NewRequestViewModel.kt`, used in `NewRequestScreen.kt` (same package — no import needed) and test file (import `dev.agentic.ui.newrequest.Override`).
- `McpDraft` defined in `NewRequestViewModel.kt`, referenced in `NewRequestScreen.kt` (same package) and test file.
- `McpServerDef` defined in `Models.kt`, imported where used.
- `setOverride("skill"/"plugin"/"mcp", id, override)` used consistently across VM and tests.
- `cycle()` extension on `Override` defined in `NewRequestScreen.kt`, used in the same file only.

All consistent — no drift found.
