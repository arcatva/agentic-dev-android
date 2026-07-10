# CRUD Frontend: Global Settings Add/Delete UI — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add "Add/Delete component" UI to the GlobalSettingsScreen — Add forms per section (Skills / Plugins / MCP) and long-press delete with confirm dialog on each ComponentChip.

**Architecture:** Six new API methods (add/delete for skill, plugin, mcp) are added to `AgenticApi`, `KtorAgenticApi`, and `FakeAgenticApi`. `GlobalSettingsViewModel` gains actions that call these methods and update state from the returned refreshed list, with a `pluginBusy` flag for the slow CLI operations. `GlobalSettingsScreen` gains per-section "Add" affordances (inline expandable forms) and an `onLongClick` on each chip that triggers a confirm dialog before calling the delete/uninstall action.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3 Expressive (1.4.0-alpha18), Ktor client, kotlinx.serialization, kotlinx.coroutines (test), JUnit 4

## Global Constraints

- Compose Material3 pinned to `1.4.0-alpha18` — do not bump.
- No new heavyweight dependencies.
- `./gradlew testDebugUnitTest` must pass before commit.
- Commit on the session branch; open a PR. Do not merge.
- Commit messages end with: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- MCP chips keep their existing read-only *toggle* behavior (no global toggle). MCP is now add/delete-able.
- All sections (Skills / Plugins / MCP) always show — even if empty — after CRUD lands.
- Plugin install/uninstall are SLOW (CLI shells out): use a 180s per-request timeout override; show a busy flag that disables interaction.
- Client-side validation: non-empty name; MCP exactly one transport, name ≠ "agentic"; plugin id non-empty, no leading dash. Show inline error on failure; show snackbar for API errors.
- Delete requires long-press (keeps tap = toggle), then a confirm dialog.

---

### Task 1: New API request/response models in Models.kt

**Files:**
- Modify: `app/src/main/java/dev/agentic/data/net/Models.kt`

**Interfaces:**
- Produces:
  - `data class AddSkillReq(val name: String, val description: String)` — body for POST /api/skills
  - `data class AddPluginReq(val id: String)` — body for POST /api/plugins
  - `McpServerDef` already exists — reused for POST /api/mcp-servers

- [ ] **Step 1: Read Models.kt to confirm McpServerDef and ComponentInfo exist**

  Open `app/src/main/java/dev/agentic/data/net/Models.kt`. Verify `McpServerDef` and `ComponentInfo` are defined. Note the `@OptIn(ExperimentalSerializationApi::class)` on `McpServerDef`.

- [ ] **Step 2: Add AddSkillReq and AddPluginReq at the bottom of Models.kt**

  Append below the last model:

  ```kotlin
  // ── Feature: Global Settings CRUD (S5c) ─────────────────────────────────────

  /** Body for POST /api/skills (add a new skill globally). */
  @Serializable
  data class AddSkillReq(val name: String, val description: String)

  /** Body for POST /api/plugins (install a plugin globally). */
  @Serializable
  data class AddPluginReq(val id: String)
  ```

- [ ] **Step 3: Verify the edit compiled cleanly (grep check)**

  ```bash
  grep -n "AddSkillReq\|AddPluginReq" app/src/main/java/dev/agentic/data/net/Models.kt
  ```
  Expected: two lines found.

- [ ] **Step 4: Commit**

  ```bash
  git add app/src/main/java/dev/agentic/data/net/Models.kt
  git commit -m "feat: add AddSkillReq and AddPluginReq serializable models

  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

---

### Task 2: New methods on AgenticApi interface

**Files:**
- Modify: `app/src/main/java/dev/agentic/data/net/AgenticApi.kt`

**Interfaces:**
- Produces:
  - `suspend fun addMcpServer(def: McpServerDef): List<ComponentInfo>` — default `= emptyList()`
  - `suspend fun deleteMcpServer(name: String): List<ComponentInfo>` — default `= emptyList()`
  - `suspend fun addSkill(name: String, description: String): List<ComponentInfo>` — default `= emptyList()`
  - `suspend fun deleteSkill(name: String): List<ComponentInfo>` — default `= emptyList()`
  - `suspend fun installPlugin(id: String): List<ComponentInfo>` — default `= emptyList()`
  - `suspend fun uninstallPlugin(id: String): List<ComponentInfo>` — default `= emptyList()`

- [ ] **Step 1: Add six interface methods in the Global Settings section of AgenticApi.kt**

  Find the comment `// ── Feature: Global Settings (S5a) ────────────────────────────────────────` in `AgenticApi.kt`. After the two existing methods (`getGlobalSettings` and `toggleGlobalComponent`), add:

  ```kotlin
  // ── Feature: Global Settings CRUD (S5c) ─────────────────────────────────────
  /** Add a skill globally. Returns the refreshed component list. Default impl = emptyList(). */
  suspend fun addSkill(name: String, description: String): List<ComponentInfo> = emptyList()
  /** Delete a skill globally by name. Returns the refreshed list. */
  suspend fun deleteSkill(name: String): List<ComponentInfo> = emptyList()
  /** Install a plugin globally (slow — CLI shells out). Returns the refreshed list. */
  suspend fun installPlugin(id: String): List<ComponentInfo> = emptyList()
  /** Uninstall a plugin globally. Returns the refreshed list. */
  suspend fun uninstallPlugin(id: String): List<ComponentInfo> = emptyList()
  /** Add a globally configured MCP server. Returns the refreshed list. */
  suspend fun addMcpServer(def: McpServerDef): List<ComponentInfo> = emptyList()
  /** Delete a globally configured MCP server by name. Returns the refreshed list. */
  suspend fun deleteMcpServer(name: String): List<ComponentInfo> = emptyList()
  ```

- [ ] **Step 2: Verify the edit**

  ```bash
  grep -n "addSkill\|deleteSkill\|installPlugin\|uninstallPlugin\|addMcpServer\|deleteMcpServer" app/src/main/java/dev/agentic/data/net/AgenticApi.kt
  ```
  Expected: 6 lines.

- [ ] **Step 3: Commit**

  ```bash
  git add app/src/main/java/dev/agentic/data/net/AgenticApi.kt
  git commit -m "feat: add six CRUD method declarations to AgenticApi interface

  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

---

### Task 3: Ktor implementations in KtorAgenticApi

**Files:**
- Modify: `app/src/main/java/dev/agentic/data/net/KtorAgenticApi.kt`

**Interfaces:**
- Consumes: `addSkill`, `deleteSkill`, `installPlugin`, `uninstallPlugin`, `addMcpServer`, `deleteMcpServer` from the interface (Task 2); `AddSkillReq`, `AddPluginReq`, `McpServerDef` from Models (Task 1).
- Produces: Ktor HTTP implementations of all six methods.

- [ ] **Step 1: Add implementations after the existing Global Settings section in KtorAgenticApi.kt**

  Find the comment `// ── Feature: Global Settings (S5a) ────────────────────────────────────────` in `KtorAgenticApi.kt`. After `toggleGlobalComponent`, add:

  ```kotlin
  // ── Feature: Global Settings CRUD (S5c) ─────────────────────────────────────
  override suspend fun addSkill(name: String, description: String): List<ComponentInfo> {
      return try {
          val r: List<ComponentInfo> = client.post("$baseUrl/api/skills") {
              auth(); contentType(ContentType.Application.Json); setBody(AddSkillReq(name, description))
          }.body()
          AppLog.d("API", "POST skills name=$name -> OK")
          r
      } catch (e: Exception) {
          AppLog.w("API", "POST skills -> FAILED: ${e.message}")
          throw e
      }
  }

  override suspend fun deleteSkill(name: String): List<ComponentInfo> {
      return try {
          val r: List<ComponentInfo> = client.delete("$baseUrl/api/skills/${name.encodeURLPathPart()}") { auth() }.body()
          AppLog.d("API", "DELETE skills/$name -> OK")
          r
      } catch (e: Exception) {
          AppLog.w("API", "DELETE skills/$name -> FAILED: ${e.message}")
          throw e
      }
  }

  override suspend fun installPlugin(id: String): List<ComponentInfo> {
      return try {
          // Plugin install shells out to the claude CLI — can take many seconds.
          // Override the per-request timeout to 180 s so the default 60 s cap doesn't fire.
          val r: List<ComponentInfo> = client.post("$baseUrl/api/plugins") {
              auth(); contentType(ContentType.Application.Json); setBody(AddPluginReq(id))
              timeout { requestTimeoutMillis = 180_000 }
          }.body()
          AppLog.d("API", "POST plugins id=$id -> OK")
          r
      } catch (e: Exception) {
          AppLog.w("API", "POST plugins -> FAILED: ${e.message}")
          throw e
      }
  }

  override suspend fun uninstallPlugin(id: String): List<ComponentInfo> {
      return try {
          val r: List<ComponentInfo> = client.delete("$baseUrl/api/plugins/${id.encodeURLPathPart()}") { auth() }.body()
          AppLog.d("API", "DELETE plugins/$id -> OK")
          r
      } catch (e: Exception) {
          AppLog.w("API", "DELETE plugins/$id -> FAILED: ${e.message}")
          throw e
      }
  }

  override suspend fun addMcpServer(def: McpServerDef): List<ComponentInfo> {
      return try {
          val r: List<ComponentInfo> = client.post("$baseUrl/api/mcp-servers") {
              auth(); contentType(ContentType.Application.Json); setBody(def)
          }.body()
          AppLog.d("API", "POST mcp-servers name=${def.name} -> OK")
          r
      } catch (e: Exception) {
          AppLog.w("API", "POST mcp-servers -> FAILED: ${e.message}")
          throw e
      }
  }

  override suspend fun deleteMcpServer(name: String): List<ComponentInfo> {
      return try {
          val r: List<ComponentInfo> = client.delete("$baseUrl/api/mcp-servers/${name.encodeURLPathPart()}") { auth() }.body()
          AppLog.d("API", "DELETE mcp-servers/$name -> OK")
          r
      } catch (e: Exception) {
          AppLog.w("API", "DELETE mcp-servers/$name -> FAILED: ${e.message}")
          throw e
      }
  }
  ```

- [ ] **Step 2: Verify**

  ```bash
  grep -n "override suspend fun addSkill\|override suspend fun deleteSkill\|override suspend fun installPlugin\|override suspend fun uninstallPlugin\|override suspend fun addMcpServer\|override suspend fun deleteMcpServer" app/src/main/java/dev/agentic/data/net/KtorAgenticApi.kt
  ```
  Expected: 6 lines.

- [ ] **Step 3: Commit**

  ```bash
  git add app/src/main/java/dev/agentic/data/net/KtorAgenticApi.kt
  git commit -m "feat: implement six CRUD API methods in KtorAgenticApi

  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

---

### Task 4: FakeAgenticApi stubs

**Files:**
- Modify: `app/src/test/java/dev/agentic/data/FakeAgenticApi.kt`

**Interfaces:**
- Consumes: the six methods from AgenticApi interface (Task 2).
- Produces: scriptable fakes for each:
  - `addSkillResult`, `addSkillCalls`, `addSkillException`
  - `deleteSkillResult`, `deleteSkillCalls`, `deleteSkillException`
  - `installPluginResult`, `installPluginCalls`, `installPluginException`
  - `uninstallPluginResult`, `uninstallPluginCalls`, `uninstallPluginException`
  - `addMcpServerResult`, `addMcpServerCalls`, `addMcpServerException`
  - `deleteMcpServerResult`, `deleteMcpServerCalls`, `deleteMcpServerException`

- [ ] **Step 1: Add imports for McpServerDef at the top of FakeAgenticApi.kt**

  Check whether `McpServerDef` is already imported. If not, add:
  ```kotlin
  import dev.agentic.data.net.McpServerDef
  ```

- [ ] **Step 2: Add the scriptable CRUD surface after the existing Global Settings section**

  After the `toggleGlobalComponent` override in `FakeAgenticApi.kt`, add:

  ```kotlin
  // ── Scriptable surface for GlobalSettings CRUD tests (S5c) ──────────────────
  var addSkillResult: List<ComponentInfo> = emptyList()
  var addSkillException: Exception? = null
  val addSkillCalls: MutableList<Pair<String, String>> = mutableListOf()
  override suspend fun addSkill(name: String, description: String): List<ComponentInfo> {
      addSkillCalls.add(name to description)
      addSkillException?.let { throw it }
      return addSkillResult
  }

  var deleteSkillResult: List<ComponentInfo> = emptyList()
  var deleteSkillException: Exception? = null
  val deleteSkillCalls: MutableList<String> = mutableListOf()
  override suspend fun deleteSkill(name: String): List<ComponentInfo> {
      deleteSkillCalls.add(name)
      deleteSkillException?.let { throw it }
      return deleteSkillResult
  }

  var installPluginResult: List<ComponentInfo> = emptyList()
  var installPluginException: Exception? = null
  val installPluginCalls: MutableList<String> = mutableListOf()
  override suspend fun installPlugin(id: String): List<ComponentInfo> {
      installPluginCalls.add(id)
      installPluginException?.let { throw it }
      return installPluginResult
  }

  var uninstallPluginResult: List<ComponentInfo> = emptyList()
  var uninstallPluginException: Exception? = null
  val uninstallPluginCalls: MutableList<String> = mutableListOf()
  override suspend fun uninstallPlugin(id: String): List<ComponentInfo> {
      uninstallPluginCalls.add(id)
      uninstallPluginException?.let { throw it }
      return uninstallPluginResult
  }

  var addMcpServerResult: List<ComponentInfo> = emptyList()
  var addMcpServerException: Exception? = null
  val addMcpServerCalls: MutableList<McpServerDef> = mutableListOf()
  override suspend fun addMcpServer(def: McpServerDef): List<ComponentInfo> {
      addMcpServerCalls.add(def)
      addMcpServerException?.let { throw it }
      return addMcpServerResult
  }

  var deleteMcpServerResult: List<ComponentInfo> = emptyList()
  var deleteMcpServerException: Exception? = null
  val deleteMcpServerCalls: MutableList<String> = mutableListOf()
  override suspend fun deleteMcpServer(name: String): List<ComponentInfo> {
      deleteMcpServerCalls.add(name)
      deleteMcpServerException?.let { throw it }
      return deleteMcpServerResult
  }
  ```

- [ ] **Step 3: Verify**

  ```bash
  grep -n "override suspend fun addSkill\|override suspend fun deleteSkill\|override suspend fun installPlugin\|override suspend fun uninstallPlugin\|override suspend fun addMcpServer\|override suspend fun deleteMcpServer" app/src/test/java/dev/agentic/data/FakeAgenticApi.kt
  ```
  Expected: 6 lines.

- [ ] **Step 4: Run tests to confirm no compilation break**

  ```bash
  ./gradlew testDebugUnitTest 2>&1 | tail -30
  ```
  Expected: BUILD SUCCESSFUL (existing tests pass, new stubs compile).

- [ ] **Step 5: Commit**

  ```bash
  git add app/src/test/java/dev/agentic/data/FakeAgenticApi.kt
  git commit -m "feat: add CRUD scriptable stubs to FakeAgenticApi

  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

---

### Task 5: GlobalSettingsViewModel CRUD actions

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/globalsettings/GlobalSettingsViewModel.kt`

**Interfaces:**
- Consumes: `addSkill`, `deleteSkill`, `installPlugin`, `uninstallPlugin`, `addMcpServer`, `deleteMcpServer` from AgenticApi (Task 2); `McpDraft` from `dev.agentic.ui.newrequest.NewRequestViewModel`.
- Produces on `GlobalSettingsUiState`:
  - `pluginBusy: Boolean = false` — true while installPlugin or uninstallPlugin is running.
- Produces new ViewModel actions (all call API, update `components` from returned list, set `error` on failure):
  - `fun addSkill(name: String, description: String)`
  - `fun deleteSkill(name: String)`
  - `fun installPlugin(id: String)` — sets/clears `pluginBusy`
  - `fun uninstallPlugin(id: String)` — sets/clears `pluginBusy`
  - `fun addMcpServer(def: McpServerDef): String?` — returns validation error or null; reuses `McpDraft.validationError` logic
  - `fun deleteMcpServer(name: String)`

- [ ] **Step 1: Add `pluginBusy` to GlobalSettingsUiState**

  In `GlobalSettingsUiState`, add the new field:

  ```kotlin
  data class GlobalSettingsUiState(
      val loading: Boolean = true,
      val components: List<ComponentInfo> = emptyList(),
      /** Transient error message shown in a snackbar; null when no error. */
      val error: String? = null,
      /** Set of component ids currently mid-toggle (prevents double-tap). */
      val toggling: Set<String> = emptySet(),
      /** True while installPlugin or uninstallPlugin is in flight (CLI is slow). */
      val pluginBusy: Boolean = false,
  )
  ```

- [ ] **Step 2: Add the six action methods to GlobalSettingsViewModel**

  Add these after `clearError()`:

  ```kotlin
  import dev.agentic.data.net.McpServerDef
  import dev.agentic.ui.newrequest.McpDraft
  ```

  Then in the class body:

  ```kotlin
  // ── CRUD actions ─────────────────────────────────────────────────────────────

  /** Add a skill globally. Calls the API and replaces the component list from the response. */
  fun addSkill(name: String, description: String) {
      viewModelScope.launch {
          try {
              val refreshed = api.addSkill(name, description)
              _uiState.update { it.copy(components = refreshed, error = null) }
          } catch (e: Exception) {
              _uiState.update { it.copy(error = "Failed to add skill: ${e.message}") }
          }
      }
  }

  /** Delete a skill by name globally. */
  fun deleteSkill(name: String) {
      viewModelScope.launch {
          try {
              val refreshed = api.deleteSkill(name)
              _uiState.update { it.copy(components = refreshed, error = null) }
          } catch (e: Exception) {
              _uiState.update { it.copy(error = "Failed to delete skill: ${e.message}") }
          }
      }
  }

  /** Install a plugin globally (slow — CLI). Sets pluginBusy while in flight. */
  fun installPlugin(id: String) {
      _uiState.update { it.copy(pluginBusy = true) }
      viewModelScope.launch {
          try {
              val refreshed = api.installPlugin(id)
              _uiState.update { it.copy(components = refreshed, pluginBusy = false, error = null) }
          } catch (e: Exception) {
              _uiState.update { it.copy(pluginBusy = false, error = "Failed to install plugin: ${e.message}") }
          }
      }
  }

  /** Uninstall a plugin globally (slow). Sets pluginBusy while in flight. */
  fun uninstallPlugin(id: String) {
      _uiState.update { it.copy(pluginBusy = true) }
      viewModelScope.launch {
          try {
              val refreshed = api.uninstallPlugin(id)
              _uiState.update { it.copy(components = refreshed, pluginBusy = false, error = null) }
          } catch (e: Exception) {
              _uiState.update { it.copy(pluginBusy = false, error = "Failed to uninstall plugin: ${e.message}") }
          }
      }
  }

  /** Add an MCP server globally. Returns a validation error string, or null on success (mirrors
   *  NewRequestViewModel.addMcpServer). Reuses McpDraft.validationError for client-side validation. */
  fun addMcpServer(draft: McpDraft): String? {
      val err = draft.validationError
      if (err != null) return err
      val def = buildMcpServerDef(draft)
      viewModelScope.launch {
          try {
              val refreshed = api.addMcpServer(def)
              _uiState.update { it.copy(components = refreshed, error = null) }
          } catch (e: Exception) {
              _uiState.update { it.copy(error = "Failed to add MCP server: ${e.message}") }
          }
      }
      return null
  }

  /** Delete an MCP server globally by name. */
  fun deleteMcpServer(name: String) {
      viewModelScope.launch {
          try {
              val refreshed = api.deleteMcpServer(name)
              _uiState.update { it.copy(components = refreshed, error = null) }
          } catch (e: Exception) {
              _uiState.update { it.copy(error = "Failed to delete MCP server: ${e.message}") }
          }
      }
  }

  // Mirrors NewRequestViewModel.buildMcpServerDef so we don't duplicate the parse logic
  // in the UI layer.
  private fun buildMcpServerDef(draft: McpDraft): McpServerDef {
      val envMap = draft.env.lines()
          .map { it.trim() }.filter { it.contains('=') }
          .associate { it.substringBefore('=') to it.substringAfter('=') }
          .ifEmpty { null }
      val headersMap = draft.headers.lines()
          .map { it.trim() }.filter { it.contains('=') }
          .associate { it.substringBefore('=') to it.substringAfter('=') }
          .ifEmpty { null }
      return if (draft.transport == "stdio") {
          McpServerDef(
              name = draft.name.trim(),
              command = draft.command.trim(),
              args = draft.args.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.ifEmpty { null },
              env = envMap,
          )
      } else {
          McpServerDef(
              name = draft.name.trim(),
              type = draft.httpType,
              url = draft.url.trim(),
              headers = headersMap,
          )
      }
  }
  ```

- [ ] **Step 3: Run tests to check compilation**

  ```bash
  ./gradlew testDebugUnitTest 2>&1 | tail -20
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

  ```bash
  git add app/src/main/java/dev/agentic/ui/globalsettings/GlobalSettingsViewModel.kt
  git commit -m "feat: add CRUD actions to GlobalSettingsViewModel with pluginBusy flag

  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

---

### Task 6: ViewModel unit tests

**Files:**
- Modify: `app/src/test/java/dev/agentic/ui/globalsettings/GlobalSettingsViewModelTest.kt`

**Interfaces:**
- Consumes: all six VM actions from Task 5; scriptable fake from Task 4; `McpDraft` from `dev.agentic.ui.newrequest.NewRequestViewModel`.
- Tests to add:
  1. `addSkill — calls API, updates components from returned list`
  2. `addSkill — API error surfaces error message`
  3. `deleteSkill — calls API with correct name, updates from returned list`
  4. `installPlugin — sets pluginBusy=true, clears on success`
  5. `installPlugin — API error clears pluginBusy and surfaces error`
  6. `uninstallPlugin — calls API, updates from returned list`
  7. `addMcpServer — validation: blank name returns error without calling API`
  8. `addMcpServer — validation: name 'agentic' returns error without calling API`
  9. `addMcpServer — stdio missing command returns error`
  10. `addMcpServer — valid stdio draft calls API, updates components`
  11. `addMcpServer — API error surfaces error`
  12. `deleteMcpServer — calls API with correct name, updates from returned list`
  13. `deleteMcpServer — API error surfaces error`

- [ ] **Step 1: Add the test import for McpDraft**

  At the top of `GlobalSettingsViewModelTest.kt`, add:
  ```kotlin
  import dev.agentic.ui.newrequest.McpDraft
  ```

- [ ] **Step 2: Add all 13 test methods**

  Append inside the class body, after the existing `clearError` and `getGlobalSettings call count` tests:

  ```kotlin
  // ── addSkill ──────────────────────────────────────────────────────────────────

  @Test fun `addSkill calls API and updates components from returned list`() = runTest(dispatcher) {
      api.globalSettingsResult = emptyList()
      val added = skill("s-new", "NewSkill", true)
      api.addSkillResult = listOf(added)

      val vm = vm()
      advanceUntilIdle()

      vm.addSkill("NewSkill", "A great skill")
      advanceUntilIdle()

      assertEquals(1, api.addSkillCalls.size)
      assertEquals("NewSkill", api.addSkillCalls[0].first)
      assertEquals("A great skill", api.addSkillCalls[0].second)
      assertEquals(listOf(added), vm.uiState.value.components)
      assertNull(vm.uiState.value.error)
  }

  @Test fun `addSkill API error surfaces error message`() = runTest(dispatcher) {
      api.globalSettingsResult = listOf(skill("s1", "Existing", true))
      api.addSkillException = RuntimeException("conflict")

      val vm = vm()
      advanceUntilIdle()

      vm.addSkill("Bad", "desc")
      advanceUntilIdle()

      val s = vm.uiState.value
      assertNotNull(s.error)
      assertTrue(s.error!!.contains("conflict"))
      // Components unchanged
      assertEquals(1, s.components.size)
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

  @Test fun `installPlugin sets pluginBusy true then clears on success`() = runTest(dispatcher) {
      api.globalSettingsResult = emptyList()
      val p1 = plugin("p1@npm", "myplugin", true)
      api.installPluginResult = listOf(p1)

      val vm = vm()
      advanceUntilIdle()

      // Start install but do NOT advance yet — verify busy flag is set.
      vm.installPlugin("p1@npm")
      // pluginBusy should be true synchronously (set before launch).
      assertTrue("pluginBusy must be true while install is in flight", vm.uiState.value.pluginBusy)

      advanceUntilIdle()

      val s = vm.uiState.value
      assertFalse("pluginBusy must clear after success", s.pluginBusy)
      assertEquals(1, s.components.size)
      assertEquals("p1@npm", api.installPluginCalls[0])
      assertNull(s.error)
  }

  @Test fun `installPlugin API error clears pluginBusy and surfaces error`() = runTest(dispatcher) {
      api.globalSettingsResult = emptyList()
      api.installPluginException = RuntimeException("CLI error")

      val vm = vm()
      advanceUntilIdle()

      vm.installPlugin("bad@npm")
      advanceUntilIdle()

      val s = vm.uiState.value
      assertFalse("pluginBusy must clear after error", s.pluginBusy)
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
  ```

- [ ] **Step 3: Run the test suite**

  ```bash
  ./gradlew testDebugUnitTest 2>&1 | tail -40
  ```
  Expected: `BUILD SUCCESSFUL` and all tests pass.

- [ ] **Step 4: Commit**

  ```bash
  git add app/src/test/java/dev/agentic/ui/globalsettings/GlobalSettingsViewModelTest.kt
  git commit -m "test: add 13 CRUD ViewModel unit tests for GlobalSettingsViewModelTest

  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

---

### Task 7: GlobalSettingsScreen UI — Add forms + long-press delete

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/globalsettings/GlobalSettingsScreen.kt`
- Modify: `app/src/main/java/dev/agentic/ui/components/ComponentChip.kt` (add optional `onLongClick`)

**Interfaces:**
- Consumes: `GlobalSettingsUiState.pluginBusy` (Task 5); VM actions `addSkill`, `deleteSkill`, `installPlugin`, `uninstallPlugin`, `addMcpServer`, `deleteMcpServer` (Task 5); `McpDraft` from `dev.agentic.ui.newrequest.NewRequestViewModel`.
- UX rules:
  - Each section header row: right-aligned "+ Add" `TextButton`; the row is a `Row` with header on the left and button on the right.
  - Skills: `+ Add` opens an inline expandable form with name + description fields + "Add Skill" button.
  - Plugins: `+ Add` opens an inline expandable form with a single id field (`plugin@marketplace`) + "Install" button; while `pluginBusy` the form shows a `LinearProgressIndicator` + disables the button.
  - MCP: `+ Add` opens the full `AddMcpForm` (extracted to a shared location — either import from NewRequestScreen or inline-duplicate in GlobalSettingsScreen; since the form is in a `private fun` in NewRequestScreen, we duplicate/adapt the relevant code in GlobalSettingsScreen).
  - Each chip gets `onLongClick` → show a confirm dialog → on confirm call delete/uninstall.
  - Confirm dialog text:
    - skill: "Delete skill <name>? This removes it globally."
    - plugin: "Uninstall plugin <id>? This removes it globally."
    - mcp: "Remove MCP server <name>? This removes it globally."

- [ ] **Step 1: Add `onLongClick` param to ComponentChip**

  In `ComponentChip.kt`, update the signature:

  ```kotlin
  @Composable
  fun ComponentChip(
      label: String,
      kind: String,
      effective: Boolean,
      onClick: () -> Unit,
      modifier: Modifier = Modifier,
      enabled: Boolean = true,
      readOnlyCaption: String? = null,
      onLongClick: (() -> Unit)? = null,   // <-- add this
  ) {
  ```

  And update the FilterChip to use `combinedClickable` instead of the `onClick` parameter. Add the import:
  ```kotlin
  import androidx.compose.foundation.ExperimentalFoundationApi
  import androidx.compose.foundation.combinedClickable
  import androidx.compose.foundation.interaction.MutableInteractionSource
  import androidx.compose.runtime.remember
  ```

  Change the FilterChip section:
  ```kotlin
  val interactionSource = remember { MutableInteractionSource() }
  Column(modifier = modifier.alpha(if (!enabled) 0.5f else 1f)) {
      FilterChip(
          selected = effective,
          onClick = { if (enabled) onClick() },
          label = { Text(label) },
          colors = chipColors,
          border = chipBorder,
          modifier = Modifier
              .semantics { contentDescription = accessDesc }
              .then(
                  if (onLongClick != null && enabled) {
                      Modifier.combinedClickable(
                          interactionSource = interactionSource,
                          indication = null,
                          onClick = { if (enabled) onClick() },
                          onLongClick = { onLongClick() },
                      )
                  } else Modifier
              ),
          interactionSource = interactionSource,
      )
      ...
  }
  ```

  Actually, `FilterChip` does not expose `combinedClickable` directly. A simpler approach that keeps code minimal: wrap the chip in a `Box` with `Modifier.combinedClickable` for long-press, let the chip handle normal clicks itself. Or, pass a `Modifier.pointerInput` for long-press detection. The simplest correct approach:

  Wrap the `FilterChip` inside a `Box` that has `combinedClickable`:
  ```kotlin
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
  @Composable
  fun ComponentChip(
      label: String,
      kind: String,
      effective: Boolean,
      onClick: () -> Unit,
      modifier: Modifier = Modifier,
      enabled: Boolean = true,
      readOnlyCaption: String? = null,
      onLongClick: (() -> Unit)? = null,
  ) {
      val accent = when (kind) { "skill" -> AccentCyan; "repo" -> AccentBlue; else -> PluginPurple }
      val chipColors = FilterChipDefaults.filterChipColors(
          selectedContainerColor = accent.copy(alpha = 0.14f),
          selectedLabelColor = accent,
          selectedLeadingIconColor = accent,
      )
      val chipBorder = FilterChipDefaults.filterChipBorder(
          enabled = enabled, selected = effective,
          selectedBorderColor = accent.copy(alpha = 0.9f), selectedBorderWidth = 1.dp,
      )
      val accessDesc = buildString {
          append(label)
          append(if (effective) ": on" else ": off")
          if (!enabled) append(", read-only") else append(" (tap to toggle)")
      }
      Column(modifier = modifier.alpha(if (!enabled) 0.5f else 1f)) {
          Box(
              modifier = if (onLongClick != null && enabled) {
                  Modifier.combinedClickable(onClick = { if (enabled) onClick() }, onLongClick = { onLongClick() })
              } else Modifier
          ) {
              FilterChip(
                  selected = effective,
                  onClick = { if (enabled && onLongClick == null) onClick() },
                  label = { Text(label) },
                  colors = chipColors,
                  border = chipBorder,
                  modifier = Modifier.semantics { contentDescription = accessDesc },
              )
          }
          if (readOnlyCaption != null) {
              Text(
                  text = readOnlyCaption,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(start = 4.dp, top = 2.dp),
              )
          }
      }
  }
  ```

  Note: when `combinedClickable` wraps the FilterChip, the chip's own `onClick` should only fire when there is no `onLongClick` wrapper, to avoid double-firing. The combinedClickable handles the click instead. Use `onClick = {}` on the FilterChip when onLongClick is set and let the Box handle it.

- [ ] **Step 2: Rewrite GlobalSettingsScreen.kt with Add forms and long-press delete**

  The full updated `GlobalSettingsScreen.kt` should:

  1. Import `McpDraft`, `Button`, `TextButton`, `OutlinedTextField` / `AppTextField`, `AlertDialog`, `LinearProgressIndicator`, `Row`, `fillMaxWidth`, `AnimatedVisibility`, `expandVertically`, `shrinkVertically`, `fadeIn`, `fadeOut`, `Arrangement.spacedBy`, `combinedClickable` (ExperimentalFoundationApi), `SingleChoiceSegmentedButtonRow`, `SegmentedButton`, `SegmentedButtonDefaults`.

  2. Show all three sections unconditionally (not gated on `isNotEmpty()`), each with a header row containing the label and a `+ Add` TextButton.

  3. For each section, track expand state via `var expanded by remember { mutableStateOf(false) }` and show an `AnimatedVisibility` containing the add form.

  4. In `ChipGroupSection` and inline chip loops, pass `onLongClick` to each `ComponentChip` that triggers a pending-delete state variable (a `ComponentInfo?`), and show a confirm `AlertDialog` when non-null.

  The detailed structure of the updated screen (fit into the existing composable architecture):

  - Section header row: `Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = CenterVertically) { SectionHeader(label); TextButton(onClick) { Icon(Add); Text("Add") } }`
  - Skills section: always show, add-form has name + description `AppTextField`s + "Add Skill" `Button`; chips get `onLongClick` triggering a `pendingDeleteSkill` state.
  - Plugins section: always show, add-form has single id `AppTextField` + "Install" `Button`; while `s.pluginBusy` show `LinearProgressIndicator` + disable the button; chips get `onLongClick` triggering `pendingDeletePlugin`.
  - MCP section: always show header; add-form embeds the full MCP form (transport toggle, name, stdio/http fields); chips get `onLongClick` triggering `pendingDeleteMcp`.
  - Delete confirm dialogs: three `AlertDialog`s, each showing the name and on confirm calling the appropriate VM method; on dismiss set the pending state back to null.

- [ ] **Step 3: Verify build**

  ```bash
  ./gradlew testDebugUnitTest 2>&1 | tail -20
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

  ```bash
  git add app/src/main/java/dev/agentic/ui/globalsettings/GlobalSettingsScreen.kt \
          app/src/main/java/dev/agentic/ui/components/ComponentChip.kt
  git commit -m "feat: add per-section Add forms and long-press delete to GlobalSettingsScreen

  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

---

### Task 8: Final build verification and report

**Files:**
- Create: `.superpowers/sdd/crud-frontend-report.md`

- [ ] **Step 1: Run full test suite**

  ```bash
  ./gradlew testDebugUnitTest 2>&1 | tail -30
  ```
  Record the tail output.

- [ ] **Step 2: Write the report**

  Create `.superpowers/sdd/crud-frontend-report.md` with:
  - API methods added (6 methods in interface + Ktor impl + fake).
  - Add-forms + long-press-delete UX description.
  - Busy/loading handling for plugin ops.
  - Test results (pass count, BUILD SUCCESSFUL or not).
  - Any concerns (e.g. ComponentChip click/long-press interaction subtleties).

- [ ] **Step 3: Commit the report**

  ```bash
  git add .superpowers/sdd/crud-frontend-report.md
  git commit -m "docs: add crud-frontend-report.md for CRUD UI implementation

  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

- [ ] **Step 4: Push and open PR**

  ```bash
  git push -u origin HEAD
  gh pr create --title "feat: add/delete component UI to GlobalSettingsScreen" \
    --body "$(cat <<'EOF'
  ## Summary
  - Adds 6 new API methods (add/delete for skill, plugin, MCP) to interface + KtorAgenticApi + FakeAgenticApi.
  - Extends GlobalSettingsViewModel with CRUD actions; `pluginBusy` flag gates slow CLI plugin ops.
  - GlobalSettingsScreen: per-section Add forms + long-press chip → confirm → delete/uninstall.
  - 13 new ViewModel unit tests; all existing tests green.

  ## Test plan
  - [ ] `./gradlew testDebugUnitTest` passes
  - [ ] Skills section: tap "+ Add" → fill name+description → "Add Skill" → chip appears
  - [ ] Plugins section: tap "+ Add" → fill id → "Install" → spinner shows while slow → chip appears
  - [ ] MCP section: tap "+ Add" → fill transport + fields → "Add MCP" → chip appears
  - [ ] Long-press any chip → confirm dialog appears → confirm → chip disappears
  - [ ] Cancel confirm dialog → chip unchanged

  🤖 Generated with [Claude Code](https://claude.com/claude-code)
  EOF
  )"
  ```
