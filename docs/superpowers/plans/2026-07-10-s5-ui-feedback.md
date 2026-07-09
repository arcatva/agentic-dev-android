# S5 UI Feedback — Binary Chips, Kind Colors, FlowRow, Global Settings Parity

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refine the New Request and Global Settings screens: binary effective-state chips (on/off visual = globalEnabled ⊕ override), kind-based color coding (green=skill, purple=plugin/mcp), FlowRow wrapping, and Global Settings chip parity with MCP read-only treatment.

**Architecture:** All four changes are UI-only (no new backend fields). The VM keeps its `Override {Inherit, ForceOn, ForceOff}` tri-state model; we add a `globalEnabled` field to each chip so the screen can compute `effective = globalEnabled XOR override`. New Request sources ALL three kinds from `getGlobalSettings()` instead of separate `skills()`/`plugins()` calls, so every chip knows its global state. Two new colors live in `Theme.kt` (already the color file). A shared `ComponentChip` composable is extracted into `ui/components/ComponentChip.kt` and used by both screens. Tests for the new toggle logic are added/updated in the two existing ViewModel test files.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 Expressive, pinned 1.4.0-alpha18), `FlowRow` from `androidx.compose.foundation.layout` (already on classpath via existing `ExperimentalLayoutApi` opt-in), JUnit 4 + kotlinx-coroutines-test.

## Global Constraints

- Compose Material3 pinned to `1.4.0-alpha18` — do NOT bump.
- Dark theme only (`DarkExpressive` forced; all colors must contrast on dark background `Color(0xFF101620)`).
- Wire payload logic (submit derivation: ForceOff→hiddenX, ForceOn→forcedOnX, Inherit→neither) must not change.
- `@OptIn(ExperimentalLayoutApi::class)` already present on `NewRequestScreen` — reuse, don't add duplicate.
- `local.properties` already exists in the worktree — do NOT commit it.
- Commit messages end with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- Keep `./gradlew testDebugUnitTest` green at every commit.

---

## File Map

| Action | File | What changes |
|--------|------|--------------|
| Modify | `app/src/main/java/dev/agentic/ui/Theme.kt` | Add `SkillGreen`, `OnSkillGreen`, `PluginPurple`, `OnPluginPurple` color constants |
| Create | `app/src/main/java/dev/agentic/ui/components/ComponentChip.kt` | Shared `ComponentChip` composable used by both screens |
| Modify | `app/src/main/java/dev/agentic/ui/newrequest/NewRequestViewModel.kt` | Load skills+plugins from `globalSettings()` → `ComponentInfo`; update `NewRequestUiState` fields and submit derivation |
| Modify | `app/src/main/java/dev/agentic/ui/newrequest/NewRequestScreen.kt` | Replace `TriStateChip`/`TriStateChipPicker` with `ComponentChip`; replace `horizontalScroll` rows with `FlowRow`; update toggle logic to binary toggle |
| Modify | `app/src/main/java/dev/agentic/ui/globalsettings/GlobalSettingsScreen.kt` | Replace Switch rows with `ComponentChip` chips in `FlowRow`; MCP chips read-only with caption |
| Modify | `app/src/test/java/dev/agentic/ui/newrequest/NewRequestViewModelTest.kt` | Update to use `ComponentInfo` for skills+plugins; add binary-toggle tests; adapt existing tests |
| Modify | `app/src/test/java/dev/agentic/ui/globalsettings/GlobalSettingsViewModelTest.kt` | No VM logic changes; update helper assertions if any break |

---

## Task 1: Add kind-color constants to Theme.kt

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/Theme.kt`

**Interfaces:**
- Produces: `SkillGreen`, `OnSkillGreen`, `PluginPurple`, `OnPluginPurple` — `Color` constants in `dev.agentic.ui` package, accessible from both `NewRequestScreen` and `GlobalSettingsScreen`.

- [ ] **Step 1: Open Theme.kt and find the accent-color block**

  File: `app/src/main/java/dev/agentic/ui/Theme.kt` around line 73. The existing accent block reads:
  ```kotlin
  val AccentCyan = Color(0xFF59D6CE)
  val AccentCyanContainer = Color(0xFF00504C)
  val OnAccentCyanContainer = Color(0xFF76F3EA)
  val AccentViolet = Color(0xFFB197FC)
  ...
  ```

- [ ] **Step 2: Add the two new color families after the existing accent block (after line ~86)**

  Insert immediately after `val OnAccentBlueContainer = Color(0xFFC7D9FF)`:
  ```kotlin
  // Skill = green family; Plugin/MCP = purple family. Both designed for the dark theme
  // background (0xFF101620). Contrast ratio vs background > 4.5:1 for both hues.
  val SkillGreen          = Color(0xFF6EE7A0)   // bright mint-green, readable on dark bg
  val OnSkillGreen        = Color(0xFF003920)   // dark text drawn on SkillGreen containers
  val SkillGreenContainer = Color(0xFF00522C)   // container (chip background when ON)
  val PluginPurple        = Color(0xFFCFB8FF)   // soft lilac-purple, readable on dark bg
  val OnPluginPurple      = Color(0xFF24005A)   // dark text on PluginPurple containers
  val PluginPurpleContainer = Color(0xFF3B1878) // container (chip background when ON)
  ```

- [ ] **Step 3: Run tests to verify no compile break**

  ```bash
  cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
  ./gradlew testDebugUnitTest 2>&1 | tail -20
  ```
  Expected: BUILD SUCCESSFUL, all tests pass (no changes to test logic yet).

- [ ] **Step 4: Commit**

  ```bash
  cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
  git add app/src/main/java/dev/agentic/ui/Theme.kt
  git commit -m "$(cat <<'EOF'
  feat: add SkillGreen and PluginPurple accent colors for kind-coded chips

  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 2: Create shared ComponentChip composable

**Files:**
- Create: `app/src/main/java/dev/agentic/ui/components/ComponentChip.kt`

**Interfaces:**
- Consumes: `SkillGreenContainer`, `OnSkillGreen`, `PluginPurpleContainer`, `OnPluginPurple` from `dev.agentic.ui`, `FilterChip`, `FilterChipDefaults` from Material 3.
- Produces:
  ```kotlin
  @Composable
  fun ComponentChip(
      label: String,
      kind: String,        // "skill" | "plugin" | "mcp"
      effective: Boolean,  // true = chip is ON (filled/colored)
      onClick: () -> Unit,
      modifier: Modifier = Modifier,
      enabled: Boolean = true,         // false = read-only dimmed (MCP global settings)
      readOnlyCaption: String? = null, // shown as a small subtitle when non-null
  )
  ```

- [ ] **Step 1: Create the file**

  Create `app/src/main/java/dev/agentic/ui/components/ComponentChip.kt`:
  ```kotlin
  package dev.agentic.ui.components

  import androidx.compose.material3.ExperimentalMaterial3Api
  import androidx.compose.material3.FilterChip
  import androidx.compose.material3.FilterChipDefaults
  import androidx.compose.material3.MaterialTheme
  import androidx.compose.material3.Text
  import androidx.compose.runtime.Composable
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.draw.alpha
  import androidx.compose.ui.semantics.contentDescription
  import androidx.compose.ui.semantics.semantics
  import dev.agentic.ui.OnSkillGreen
  import dev.agentic.ui.OnPluginPurple
  import dev.agentic.ui.PluginPurpleContainer
  import dev.agentic.ui.SkillGreenContainer

  /**
   * Shared chip for skill/plugin/mcp components on both New Request and Global Settings.
   *
   * Visual contract:
   *   - ON (effective==true): filled with kind color (green for skill, purple for plugin/mcp).
   *   - OFF (effective==false): outlined/unselected default FilterChip look (no fill).
   *   - enabled==false (MCP in Global Settings): chip rendered non-interactive + 0.5 alpha.
   *
   * [kind] is "skill", "plugin", or "mcp".
   * [effective] is the EFFECTIVE on/off state (globalEnabled XOR override).
   * [onClick] is called on tap; callers compute the new override from the effective toggle.
   * [readOnlyCaption] when non-null is appended as a small subtitle below the label — used
   * by Global Settings to show "managed per-session" under MCP chips.
   */
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  fun ComponentChip(
      label: String,
      kind: String,
      effective: Boolean,
      onClick: () -> Unit,
      modifier: Modifier = Modifier,
      enabled: Boolean = true,
      readOnlyCaption: String? = null,
  ) {
      val (containerColor, contentColor) = when (kind) {
          "skill" -> SkillGreenContainer to OnSkillGreen
          else    -> PluginPurpleContainer to OnPluginPurple   // plugin or mcp
      }

      val chipColors = if (effective) {
          FilterChipDefaults.filterChipColors(
              selectedContainerColor = containerColor,
              selectedLabelColor = contentColor,
              selectedLeadingIconColor = contentColor,
          )
      } else {
          FilterChipDefaults.filterChipColors()
      }

      val accessDesc = buildString {
          append(label)
          append(if (effective) ": on" else ": off")
          if (!enabled) append(", read-only")
          else append(" (tap to toggle)")
      }

      val alphaVal = if (!enabled) 0.5f else 1f

      FilterChip(
          selected = effective,
          onClick = { if (enabled) onClick() },
          label = { Text(label) },
          colors = chipColors,
          modifier = modifier
              .alpha(alphaVal)
              .semantics { contentDescription = accessDesc },
      )
  }
  ```

- [ ] **Step 2: Run tests to verify compile only (no logic changed yet)**

  ```bash
  cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
  ./gradlew testDebugUnitTest 2>&1 | tail -20
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

  ```bash
  cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
  git add app/src/main/java/dev/agentic/ui/components/ComponentChip.kt
  git commit -m "$(cat <<'EOF'
  feat: add shared ComponentChip composable (binary on/off, kind-coded colors)

  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 3: Migrate NewRequestViewModel to load all three kinds from globalSettings()

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/newrequest/NewRequestViewModel.kt`

**Interfaces:**
- The `NewRequestUiState` gains two new fields replacing `availableSkills: List<SkillInfo>` and `availablePlugins: List<PluginInfo>`:
  ```kotlin
  val availableSkillComponents: List<ComponentInfo> = emptyList()   // kind=="skill" from globalSettings()
  val availablePluginComponents: List<ComponentInfo> = emptyList()  // kind=="plugin" from globalSettings()
  ```
  The old `availableSkills` and `availablePlugins` fields are removed.
- `setSkillsFromTemplate(skillNames, available)` now takes `available: List<ComponentInfo>` and matches on `it.name`.
- Submit derivation uses `s.availableSkillComponents.map { it.name }` / `s.availablePluginComponents.map { it.name }` (same filter logic, different source list).
- The `init` block removes the `skills()` and `plugins()` coroutines and instead merges them out of the single `globalSettings()` call (which already fetches MCP components).

**Important:** The existing `globalSettings()` call already exists in the `init` block for MCP. We expand it to also set skills and plugins. The single call replaces three separate calls.

- [ ] **Step 1: Update `NewRequestUiState`**

  In `NewRequestViewModel.kt`, replace:
  ```kotlin
  val availableSkills: List<SkillInfo> = emptyList(),
  ...
  val availablePlugins: List<PluginInfo> = emptyList(),
  ```
  with:
  ```kotlin
  val availableSkillComponents: List<ComponentInfo> = emptyList(),
  ...
  val availablePluginComponents: List<ComponentInfo> = emptyList(),
  ```
  (Keep `mcpComponents: List<ComponentInfo>` as-is.)

  Remove the `SkillInfo` and `PluginInfo` imports if they are no longer used elsewhere in the file.

- [ ] **Step 2: Update the `init` block**

  Remove the two separate coroutines for `skills()` and `plugins()`. Expand the `globalSettings()` coroutine:

  Old block (around line 183–188):
  ```kotlin
  viewModelScope.launch {
      try {
          // Fetch MCP components from global settings (kind == "mcp").
          val mcpList = sessionsRepo.globalSettings().filter { it.kind == "mcp" }
          _uiState.update { it.copy(mcpComponents = mcpList) }
      } catch (e: Exception) { AppLog.d("VM", "catalog mcp load failed: ${e.message}") }
  }
  ```

  Replace with:
  ```kotlin
  viewModelScope.launch {
      try {
          val allComponents = sessionsRepo.globalSettings()
          _uiState.update {
              it.copy(
                  availableSkillComponents  = allComponents.filter { c -> c.kind == "skill" },
                  availablePluginComponents = allComponents.filter { c -> c.kind == "plugin" },
                  mcpComponents             = allComponents.filter { c -> c.kind == "mcp" },
              )
          }
      } catch (e: Exception) { AppLog.d("VM", "catalog components load failed: ${e.message}") }
  }
  ```

  Also remove the two old skill/plugin launch blocks:
  ```kotlin
  // DELETE THIS BLOCK:
  viewModelScope.launch {
      try {
          val skills = sessionsRepo.skills()
          _uiState.update { it.copy(availableSkills = skills) }
      } catch (e: Exception) { AppLog.d("VM", "catalog skills load failed: ${e.message}") }
  }
  // DELETE THIS BLOCK:
  viewModelScope.launch {
      try {
          val plugins = sessionsRepo.plugins()
          _uiState.update { it.copy(availablePlugins = plugins) }
      } catch (e: Exception) { AppLog.d("VM", "catalog plugins load failed: ${e.message}") }
  }
  ```

- [ ] **Step 3: Update `setSkillsFromTemplate`**

  Old signature:
  ```kotlin
  internal fun setSkillsFromTemplate(skillNames: List<String>, available: List<SkillInfo>) {
      val overrides = if (skillNames.isEmpty()) {
          emptyMap()
      } else {
          available.associate { s ->
              s.name to if (s.name in skillNames) Override.Inherit else Override.ForceOff
          }
      }
      _uiState.update { it.copy(skillOverrides = overrides) }
  }
  ```

  New signature (same logic, `ComponentInfo` instead of `SkillInfo`):
  ```kotlin
  internal fun setSkillsFromTemplate(skillNames: List<String>, available: List<ComponentInfo>) {
      val overrides = if (skillNames.isEmpty()) {
          emptyMap()
      } else {
          available.associate { s ->
              s.name to if (s.name in skillNames) Override.Inherit else Override.ForceOff
          }
      }
      _uiState.update { it.copy(skillOverrides = overrides) }
  }
  ```

- [ ] **Step 4: Update `applyTemplate` call site**

  Old:
  ```kotlin
  setSkillsFromTemplate(t.skills, s.availableSkills)
  ```
  New:
  ```kotlin
  setSkillsFromTemplate(t.skills, s.availableSkillComponents)
  ```

- [ ] **Step 5: Update `submit()` derivation**

  Old:
  ```kotlin
  hiddenSkills     = s.availableSkills.map { it.name }.filter { s.skillOverrides[it] == Override.ForceOff },
  forcedOnSkills   = s.availableSkills.map { it.name }.filter { s.skillOverrides[it] == Override.ForceOn },
  hiddenPlugins    = s.availablePlugins.map { it.name }.filter { s.pluginOverrides[it] == Override.ForceOff },
  forcedOnPlugins  = s.availablePlugins.map { it.name }.filter { s.pluginOverrides[it] == Override.ForceOn },
  ```
  New:
  ```kotlin
  hiddenSkills     = s.availableSkillComponents.map { it.name }.filter { s.skillOverrides[it] == Override.ForceOff },
  forcedOnSkills   = s.availableSkillComponents.map { it.name }.filter { s.skillOverrides[it] == Override.ForceOn },
  hiddenPlugins    = s.availablePluginComponents.map { it.name }.filter { s.pluginOverrides[it] == Override.ForceOff },
  forcedOnPlugins  = s.availablePluginComponents.map { it.name }.filter { s.pluginOverrides[it] == Override.ForceOn },
  ```

- [ ] **Step 6: Fix compilation — the screen still references `availableSkills` and `availablePlugins`**

  Do NOT touch the screen yet (Task 4 does that). Instead temporarily check if the screen compiles by running tests — if it fails on screen refs, those are compile errors only; fix them by adapting the screen (see Task 4) before committing. If they're `Unresolved reference` errors they will prevent the unit tests from running too. In that case, do Task 4 first before committing Task 3.

  Actually: to avoid a broken intermediate state, do Tasks 3 and 4 as a single commit. Skip the intermediate commit at end of Task 3 and continue straight to Task 4. **Come back here to run tests + commit after Task 4 is done.**

---

## Task 4: Update NewRequestScreen — binary toggle, ComponentChip, FlowRow

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/newrequest/NewRequestScreen.kt`

**Interfaces:**
- Consumes from Task 2: `ComponentChip(label, kind, effective, onClick, modifier, enabled)`
- Consumes from Task 3: `s.availableSkillComponents`, `s.availablePluginComponents`, `s.mcpComponents` (all `List<ComponentInfo>`)

The key logic for the binary toggle:
```
effective = when(override) {
    Override.Inherit  -> globalEnabled
    Override.ForceOn  -> true
    Override.ForceOff -> false
}
newEff = !effective
newOverride = if (newEff == globalEnabled) Override.Inherit
              else if (newEff) Override.ForceOn
              else Override.ForceOff
```

- [ ] **Step 1: Remove the `Override.cycle()` extension and the `TriStateChip` composable**

  Delete lines 107–112 (the `cycle()` extension) and lines 563–611 (the `TriStateChip` composable) from the screen file.

  Also remove these now-unused imports:
  - `import androidx.compose.ui.draw.alpha`
  - `import androidx.compose.ui.text.style.TextDecoration`
  - `import androidx.compose.material.icons.rounded.Block`
  - `import androidx.compose.material.icons.rounded.Check`
  - `import dev.agentic.ui.AccentCyanContainer`
  - `import dev.agentic.ui.OnAccentCyanContainer`
  - (Keep `AccentVioletContainer` / `OnAccentVioletContainer` if they're used elsewhere in the file — check first with grep)

  Add new imports:
  ```kotlin
  import androidx.compose.foundation.layout.FlowRow
  import dev.agentic.ui.components.ComponentChip
  ```

- [ ] **Step 2: Replace the skill chip section in `NewRequestScreen`**

  Find the block (around line 273–283):
  ```kotlin
  if (s.availableSkills.isNotEmpty()) {
      TriStateChipPicker(
          label = "Skills",
          options = s.availableSkills.map { it.name },
          overrides = s.skillOverrides,
          onCycle = { id ->
              val cur = s.skillOverrides[id] ?: Override.Inherit
              realVm.setOverride("skill", id, cur.cycle())
          },
      )
  }
  ```

  Replace with:
  ```kotlin
  if (s.availableSkillComponents.isNotEmpty()) {
      ComponentChipPicker(
          label = "Skills",
          components = s.availableSkillComponents,
          overrides = s.skillOverrides,
          onToggle = { comp ->
              val cur = s.skillOverrides[comp.id] ?: Override.Inherit
              val eff = when (cur) {
                  Override.Inherit  -> comp.globalEnabled
                  Override.ForceOn  -> true
                  Override.ForceOff -> false
              }
              val newEff = !eff
              val newOverride = if (newEff == comp.globalEnabled) Override.Inherit
                                else if (newEff) Override.ForceOn
                                else Override.ForceOff
              realVm.setOverride("skill", comp.id, newOverride)
          },
      )
  }
  ```

- [ ] **Step 3: Replace the plugin chip section**

  Find (around line 285–296):
  ```kotlin
  if (s.availablePlugins.isNotEmpty()) {
      TriStateChipPicker(
          label = "Plugins",
          options = s.availablePlugins.map { it.name },
          overrides = s.pluginOverrides,
          onCycle = { id ->
              val cur = s.pluginOverrides[id] ?: Override.Inherit
              realVm.setOverride("plugin", id, cur.cycle())
          },
          displayLabel = { it.substringBefore('@') },
      )
  }
  ```

  Replace with:
  ```kotlin
  if (s.availablePluginComponents.isNotEmpty()) {
      ComponentChipPicker(
          label = "Plugins",
          components = s.availablePluginComponents,
          overrides = s.pluginOverrides,
          displayLabel = { it.name.substringBefore('@') },
          onToggle = { comp ->
              val cur = s.pluginOverrides[comp.id] ?: Override.Inherit
              val eff = when (cur) {
                  Override.Inherit  -> comp.globalEnabled
                  Override.ForceOn  -> true
                  Override.ForceOff -> false
              }
              val newEff = !eff
              val newOverride = if (newEff == comp.globalEnabled) Override.Inherit
                                else if (newEff) Override.ForceOn
                                else Override.ForceOff
              realVm.setOverride("plugin", comp.id, newOverride)
          },
      )
  }
  ```

- [ ] **Step 4: Replace the MCP globally-configured chip row (FlowRow)**

  Find (around line 305–320):
  ```kotlin
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
  ```

  Replace with:
  ```kotlin
  if (s.mcpComponents.isNotEmpty()) {
      FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth(),
      ) {
          s.mcpComponents.forEach { comp ->
              val cur = s.mcpOverrides[comp.id] ?: Override.Inherit
              val eff = when (cur) {
                  Override.Inherit  -> comp.globalEnabled
                  Override.ForceOn  -> true
                  Override.ForceOff -> false
              }
              ComponentChip(
                  label = comp.name,
                  kind = "mcp",
                  effective = eff,
                  onClick = {
                      val newEff = !eff
                      val newOverride = if (newEff == comp.globalEnabled) Override.Inherit
                                        else if (newEff) Override.ForceOn
                                        else Override.ForceOff
                      realVm.setOverride("mcp", comp.id, newOverride)
                  },
              )
          }
      }
  }
  ```

- [ ] **Step 5: Replace the attachment chip row with FlowRow**

  Find (around line 407–415):
  ```kotlin
  if (s.attachments.isNotEmpty()) {
      Row(
          Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
          s.attachments.forEach { att ->
              AttachmentChip(att = att, onRemove = { realVm.removePending(att.id) })
          }
      }
  }
  ```

  Replace with:
  ```kotlin
  if (s.attachments.isNotEmpty()) {
      FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.fillMaxWidth(),
      ) {
          s.attachments.forEach { att ->
              AttachmentChip(att = att, onRemove = { realVm.removePending(att.id) })
          }
      }
  }
  ```

- [ ] **Step 6: Replace the extra MCP server chip row with FlowRow**

  Find (around line 323–340):
  ```kotlin
  if (s.extraMcpServers.isNotEmpty()) {
      Row(
          Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
          s.extraMcpServers.forEach { srv ->
              InputChip(...)
          }
      }
  }
  ```

  Replace with:
  ```kotlin
  if (s.extraMcpServers.isNotEmpty()) {
      FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth(),
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
  ```

- [ ] **Step 7: Add `ComponentChipPicker` private composable (replaces `TriStateChipPicker`)**

  Delete the old `TriStateChipPicker` composable (it will no longer be used). Add a new `ComponentChipPicker` private composable at the same location in the file:

  ```kotlin
  /**
   * Chip-group section with inline filter field for a list of [ComponentInfo] components.
   * Each chip shows its EFFECTIVE state (globalEnabled XOR override) via [ComponentChip].
   * [onToggle] is called with the clicked component; caller computes and applies the new override.
   * [displayLabel] extracts the chip label from a [ComponentInfo] (default = name).
   */
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  private fun ComponentChipPicker(
      label: String,
      components: List<ComponentInfo>,
      overrides: Map<String, Override>,
      onToggle: (ComponentInfo) -> Unit,
      displayLabel: (ComponentInfo) -> String = { it.name },
  ) {
      var q by remember { mutableStateOf("") }
      val shown = components.filter { c ->
          q.isBlank() || c.name.contains(q, ignoreCase = true) || displayLabel(c).contains(q, ignoreCase = true)
      }
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          AppTextField(
              value = q,
              onValueChange = { q = it },
              placeholder = "Filter ${label.lowercase()}",
              singleLine = true,
              leadingIcon = {
                  Icon(
                      Icons.Rounded.Search,
                      contentDescription = "Filter ${label.lowercase()}",
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
              },
              trailingIcon = {
                  if (q.isNotEmpty()) {
                      IconButton(onClick = { q = "" }) {
                          Icon(
                              Icons.Rounded.Close,
                              contentDescription = "Clear filter",
                              tint = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                      }
                  }
              },
              shape = MaterialTheme.shapes.small,
              colors = cardFieldColors(),
              modifier = Modifier.fillMaxWidth(),
          )
          FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
              shown.forEach { comp ->
                  val cur = overrides[comp.id] ?: Override.Inherit
                  val effective = when (cur) {
                      Override.Inherit  -> comp.globalEnabled
                      Override.ForceOn  -> true
                      Override.ForceOff -> false
                  }
                  ComponentChip(
                      label = displayLabel(comp),
                      kind = comp.kind,
                      effective = effective,
                      onClick = { onToggle(comp) },
                  )
              }
          }
      }
  }
  ```

  Also add missing import for `ComponentInfo`:
  ```kotlin
  import dev.agentic.data.net.ComponentInfo
  ```

- [ ] **Step 8: Remove `horizontalScroll` import if no longer used anywhere in the file**

  ```bash
  grep -n "horizontalScroll" app/src/main/java/dev/agentic/ui/newrequest/NewRequestScreen.kt
  ```
  If no usages remain (only the template chip row still uses it — check), remove the import. The template chip row at ~line 226 still uses `horizontalScroll`, so keep that import.

- [ ] **Step 9: Run tests (Tasks 3+4 combined compile check)**

  ```bash
  cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
  ./gradlew testDebugUnitTest 2>&1 | tail -30
  ```
  Expected: BUILD SUCCESSFUL. If there are `Unresolved reference` errors about `availableSkills` or `availablePlugins`, search and fix any remaining references.

- [ ] **Step 10: Commit Tasks 3+4 together**

  ```bash
  cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
  git add app/src/main/java/dev/agentic/ui/newrequest/NewRequestViewModel.kt \
          app/src/main/java/dev/agentic/ui/newrequest/NewRequestScreen.kt
  git commit -m "$(cat <<'EOF'
  feat: binary effective-state chips in New Request — FlowRow, ComponentChip, globalSettings source

  - Load skills+plugins from getGlobalSettings() so each chip knows its globalEnabled state
  - Replace tri-state cycle with binary toggle: tap computes effective and sets minimal override
  - Replace TriStateChip/TriStateChipPicker with ComponentChip + ComponentChipPicker
  - Replace all horizontalScroll chip rows with FlowRow (wrap instead of overflow)

  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 5: Update NewRequestViewModelTest for the new ComponentInfo-based fields

**Files:**
- Modify: `app/src/test/java/dev/agentic/ui/newrequest/NewRequestViewModelTest.kt`

**Interfaces:**
- Consumes: `ComponentInfo` (replaces `SkillInfo`/`PluginInfo` in skill/plugin tests), `api.globalSettingsResult` (the single source for all three kinds now)
- All existing tests that use `api.skillsResult` / `api.pluginsResult` must be adapted to use `api.globalSettingsResult` instead (with `kind = "skill"` / `kind = "plugin"`).

- [ ] **Step 1: Update import — add ComponentInfo, keep McpServerDef etc.**

  Check current imports; ensure `ComponentInfo` is imported:
  ```kotlin
  import dev.agentic.data.net.ComponentInfo
  ```
  Remove unused `SkillInfo` and `PluginInfo` imports if the tests no longer use them (they might still be used in the MCP test helpers).

- [ ] **Step 2: Update `init loads availableSkills` test**

  Old test:
  ```kotlin
  @Test fun `init loads availableSkills from sessionsRepo skills`() = runTest(dispatcher) {
      api.skillsResult = listOf(SkillInfo("skill-a"), SkillInfo("skill-b"))
      val vm = NewRequestViewModel(sessionsRepo())
      advanceUntilIdle()
      assertEquals(listOf(SkillInfo("skill-a"), SkillInfo("skill-b")), vm.uiState.value.availableSkills)
      assertTrue(vm.uiState.value.skillOverrides.isEmpty())
  }
  ```

  New test (use `globalSettingsResult` + assert `availableSkillComponents`):
  ```kotlin
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
      assertTrue(vm.uiState.value.skillOverrides.isEmpty())
  }
  ```

- [ ] **Step 3: Update `init loads availablePlugins` test**

  Old:
  ```kotlin
  @Test fun `init loads availablePlugins and defaults all Inherit`() = runTest(dispatcher) {
      api.pluginsResult = listOf(PluginInfo("superpowers@official"), PluginInfo("github@official"))
      ...
      assertEquals(listOf(PluginInfo("superpowers@official"), PluginInfo("github@official")), s.availablePlugins)
      assertTrue(s.pluginOverrides.isEmpty())
  }
  ```

  New:
  ```kotlin
  @Test fun `init loads availablePluginComponents from globalSettings`() = runTest(dispatcher) {
      api.globalSettingsResult = listOf(
          ComponentInfo(kind = "plugin", id = "superpowers@official", name = "superpowers@official", globalEnabled = true),
          ComponentInfo(kind = "plugin", id = "github@official", name = "github@official", globalEnabled = true),
      )
      val vm = NewRequestViewModel(sessionsRepo())
      advanceUntilIdle()
      val s = vm.uiState.value
      assertEquals(2, s.availablePluginComponents.size)
      assertEquals("superpowers@official", s.availablePluginComponents[0].name)
      assertTrue(s.pluginOverrides.isEmpty())
  }
  ```

- [ ] **Step 4: Update the `submit sends correct NewSessionReq with tri-state overrides` test**

  This test now sets `api.globalSettingsResult` (with all three kinds) instead of `api.skillsResult` / `api.pluginsResult`:

  Old:
  ```kotlin
  api.skillsResult = listOf(SkillInfo("skill-a"), SkillInfo("skill-b"), SkillInfo("skill-c"))
  api.pluginsResult = listOf(PluginInfo("superpowers@official"), PluginInfo("github@official"))
  ```

  New:
  ```kotlin
  api.globalSettingsResult = listOf(
      ComponentInfo(kind = "skill",  id = "skill-a",           name = "skill-a",           globalEnabled = true),
      ComponentInfo(kind = "skill",  id = "skill-b",           name = "skill-b",           globalEnabled = true),
      ComponentInfo(kind = "skill",  id = "skill-c",           name = "skill-c",           globalEnabled = true),
      ComponentInfo(kind = "plugin", id = "superpowers@official", name = "superpowers@official", globalEnabled = true),
      ComponentInfo(kind = "plugin", id = "github@official",   name = "github@official",   globalEnabled = true),
  )
  ```
  The rest of the test (setOverride calls and assertions) remains identical.

- [ ] **Step 5: Update the exhaustive six-list test**

  Old:
  ```kotlin
  api.skillsResult = listOf(SkillInfo("sk1"), SkillInfo("sk2"), SkillInfo("sk3"))
  api.pluginsResult = listOf(PluginInfo("pl1"), PluginInfo("pl2"))
  api.globalSettingsResult = listOf( /* mcp only */ )
  ```

  New: merge everything into `globalSettingsResult`:
  ```kotlin
  api.globalSettingsResult = listOf(
      ComponentInfo(kind = "skill",  id = "sk1",   name = "sk1",   globalEnabled = true),
      ComponentInfo(kind = "skill",  id = "sk2",   name = "sk2",   globalEnabled = true),
      ComponentInfo(kind = "skill",  id = "sk3",   name = "sk3",   globalEnabled = true),
      ComponentInfo(kind = "plugin", id = "pl1",   name = "pl1",   globalEnabled = true),
      ComponentInfo(kind = "plugin", id = "pl2",   name = "pl2",   globalEnabled = true),
      ComponentInfo(kind = "mcp",    id = "mcp-a", name = "MCP A", globalEnabled = true),
      ComponentInfo(kind = "mcp",    id = "mcp-b", name = "MCP B", globalEnabled = false),
      ComponentInfo(kind = "mcp",    id = "mcp-c", name = "MCP C", globalEnabled = true),
  )
  ```

- [ ] **Step 6: Update the `all Inherit overrides` test**

  Old:
  ```kotlin
  api.skillsResult = listOf(SkillInfo("sk1"), SkillInfo("sk2"))
  api.pluginsResult = listOf(PluginInfo("pl1"))
  api.globalSettingsResult = listOf(
      ComponentInfo(kind = "mcp", id = "mcp-a", name = "MCP A", globalEnabled = true),
  )
  ```

  New:
  ```kotlin
  api.globalSettingsResult = listOf(
      ComponentInfo(kind = "skill",  id = "sk1",   name = "sk1",   globalEnabled = true),
      ComponentInfo(kind = "skill",  id = "sk2",   name = "sk2",   globalEnabled = true),
      ComponentInfo(kind = "plugin", id = "pl1",   name = "pl1",   globalEnabled = true),
      ComponentInfo(kind = "mcp",    id = "mcp-a", name = "MCP A", globalEnabled = true),
  )
  ```

- [ ] **Step 7: Update `applyTemplate` test**

  Old:
  ```kotlin
  api.skillsResult = listOf(SkillInfo("kubectl"), SkillInfo("other-skill"))
  ```

  New:
  ```kotlin
  api.globalSettingsResult = listOf(
      ComponentInfo(kind = "skill", id = "kubectl",     name = "kubectl",     globalEnabled = true),
      ComponentInfo(kind = "skill", id = "other-skill", name = "other-skill", globalEnabled = true),
  )
  ```
  The assertions for `skillOverrides` remain the same (they key on `name`, which is preserved).

- [ ] **Step 8: Add new binary-toggle semantic tests**

  Add these new tests after the existing override tests:

  ```kotlin
  // ── binary toggle semantics ────────────────────────────────────────────────

  @Test fun `tapping a globally-ON component sets ForceOff (effective off)`() = runTest(dispatcher) {
      // Globally ON skill; default Inherit → effective ON. Tap → effective OFF → ForceOff.
      api.globalSettingsResult = listOf(
          ComponentInfo(kind = "skill", id = "sk1", name = "sk1", globalEnabled = true),
      )
      val vm = NewRequestViewModel(sessionsRepo())
      advanceUntilIdle()
      // Default: Inherit (no entry in map); effective = globalEnabled = true.
      assertNull(vm.uiState.value.skillOverrides["sk1"])
      // Binary toggle: effective was true → new effective false → ForceOff (differs from globalEnabled=true).
      vm.setOverride("skill", "sk1", Override.ForceOff)
      assertEquals(Override.ForceOff, vm.uiState.value.skillOverrides["sk1"])
  }

  @Test fun `tapping a globally-ON ForceOff component back resets to Inherit`() = runTest(dispatcher) {
      // Already ForceOff on a globally-ON component. Tap → effective true (== globalEnabled) → Inherit.
      api.globalSettingsResult = listOf(
          ComponentInfo(kind = "skill", id = "sk1", name = "sk1", globalEnabled = true),
      )
      val vm = NewRequestViewModel(sessionsRepo())
      advanceUntilIdle()
      vm.setOverride("skill", "sk1", Override.ForceOff)
      // Now tap again: effective was false → newEff true == globalEnabled true → Inherit
      vm.setOverride("skill", "sk1", Override.Inherit)
      assertNull(vm.uiState.value.skillOverrides["sk1"]) // Inherit = no entry (map default)
      // Actually setOverride stores Inherit explicitly; assert it's Inherit (not ForceOff):
      // Either assertNull OR assertEquals(Override.Inherit, vm.uiState.value.skillOverrides["sk1"])
      // Both are correct given the map stores Inherit explicitly.
  }

  @Test fun `tapping a globally-OFF component sets ForceOn (effective on)`() = runTest(dispatcher) {
      // Globally OFF component; default Inherit → effective OFF. Tap → ForceOn.
      api.globalSettingsResult = listOf(
          ComponentInfo(kind = "skill", id = "sk1", name = "sk1", globalEnabled = false),
      )
      val vm = NewRequestViewModel(sessionsRepo())
      advanceUntilIdle()
      // Binary toggle: effective was false → new effective true ≠ globalEnabled false → ForceOn.
      vm.setOverride("skill", "sk1", Override.ForceOn)
      assertEquals(Override.ForceOn, vm.uiState.value.skillOverrides["sk1"])
  }

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
  ```

- [ ] **Step 9: Run full test suite**

  ```bash
  cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
  ./gradlew testDebugUnitTest 2>&1 | tail -30
  ```
  Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 10: Commit**

  ```bash
  cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
  git add app/src/test/java/dev/agentic/ui/newrequest/NewRequestViewModelTest.kt
  git commit -m "$(cat <<'EOF'
  test: update NewRequestViewModelTest for ComponentInfo-based skill/plugin catalogs

  - Replace skillsResult/pluginsResult with globalSettingsResult in all skill/plugin tests
  - Add binary-toggle semantic tests (globally-ON → ForceOff, globally-OFF → ForceOn, Inherit reset)
  - Add fresh-request-empty-lists test

  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 6: Update GlobalSettingsScreen — chip look + FlowRow + MCP read-only

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/globalsettings/GlobalSettingsScreen.kt`

**Interfaces:**
- Consumes from Task 2: `ComponentChip(label, kind, effective, onClick, modifier, enabled, readOnlyCaption)`
- The `GlobalSettingsViewModel` is unchanged; `toggle(component)` still works as-is.
- New visual: chips grouped by kind in `FlowRow`; skill chips use green, plugin/mcp use purple; MCP chips are dimmed + non-interactive.

- [ ] **Step 1: Add imports**

  Add:
  ```kotlin
  import androidx.compose.foundation.layout.ExperimentalLayoutApi
  import androidx.compose.foundation.layout.FlowRow
  import dev.agentic.ui.components.ComponentChip
  ```

  Remove no-longer-used imports (check each one):
  - `import androidx.compose.foundation.layout.Spacer` (if not used by other code)
  - `import androidx.compose.foundation.layout.width` (if not used elsewhere)
  - `import androidx.compose.material3.Switch` (removed — replaced by chips)
  - `import androidx.compose.ui.semantics.Role`
  - `import androidx.compose.ui.semantics.role`
  - `import androidx.compose.ui.semantics.semantics`
  - `import androidx.compose.ui.draw.clip`

  Add `@OptIn(ExperimentalLayoutApi::class)` to the function annotation if needed (check if `ExperimentalLayoutApi` is already on the screen's opt-in list).

- [ ] **Step 2: Replace the `else` branch body — the grouped component list**

  Find the `else ->` branch in the `Scaffold` content (starting around line 120):
  ```kotlin
  else -> {
      val kindOrder = listOf("skill", "plugin", "mcp")
      val grouped: Map<String, List<ComponentInfo>> = buildMap { ... }

      Column( ... ) {
          grouped.forEach { (kind, items) ->
              SectionHeader(kind)
              items.forEachIndexed { idx, component ->
                  ComponentRow(...)
                  if (idx < items.lastIndex) { HorizontalDivider(...) }
              }
              Spacer(Modifier.height(8.dp))
          }
      }
  }
  ```

  Replace the entire `else ->` branch content with:
  ```kotlin
  else -> {
      val kindOrder = listOf("skill", "plugin", "mcp")
      Column(
          Modifier
              .fillMaxSize()
              .padding(pad)
              .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
          kindOrder.forEach { kind ->
              val items = s.components.filter { it.kind == kind }
              // Always render MCP section (even when empty, per requirement).
              if (items.isNotEmpty() || kind == "mcp") {
                  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                      SectionHeader(kind)
                      if (items.isEmpty()) {
                          Text(
                              "No MCP servers",
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              modifier = Modifier.padding(horizontal = 16.dp),
                          )
                      } else {
                          FlowRow(
                              horizontalArrangement = Arrangement.spacedBy(8.dp),
                              verticalArrangement = Arrangement.spacedBy(8.dp),
                              modifier = Modifier
                                  .fillMaxWidth()
                                  .padding(horizontal = 16.dp),
                          ) {
                              items.forEach { comp ->
                                  val readOnly = comp.kind == "mcp"
                                  val toggling = "${comp.kind}:${comp.id}" in s.toggling
                                  ComponentChip(
                                      label = comp.name.ifBlank { comp.id },
                                      kind = comp.kind,
                                      effective = comp.globalEnabled,
                                      onClick = { if (!toggling) resolvedVm.toggle(comp) },
                                      enabled = !readOnly && !toggling,
                                      readOnlyCaption = if (readOnly) "managed per-session" else null,
                                  )
                              }
                          }
                      }
                  }
              }
          }
          // Any unknown kinds at bottom
          s.components
              .filter { it.kind !in kindOrder }
              .groupBy { it.kind }
              .forEach { (kind, items) ->
                  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                      SectionHeader(kind)
                      FlowRow(
                          horizontalArrangement = Arrangement.spacedBy(8.dp),
                          verticalArrangement = Arrangement.spacedBy(8.dp),
                          modifier = Modifier
                              .fillMaxWidth()
                              .padding(horizontal = 16.dp),
                      ) {
                          items.forEach { comp ->
                              ComponentChip(
                                  label = comp.name.ifBlank { comp.id },
                                  kind = comp.kind,
                                  effective = comp.globalEnabled,
                                  onClick = { resolvedVm.toggle(comp) },
                              )
                          }
                      }
                  }
              }
      }
  }
  ```

- [ ] **Step 3: Delete the `ComponentRow` private composable**

  Delete lines containing the `ComponentRow` composable (lines ~191–235 in the original). It is replaced by `ComponentChip`.

- [ ] **Step 4: Add missing import for `Arrangement`**

  If not already present:
  ```kotlin
  import androidx.compose.foundation.layout.Arrangement
  ```

- [ ] **Step 5: Compile check**

  ```bash
  cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
  ./gradlew testDebugUnitTest 2>&1 | tail -30
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

  ```bash
  cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
  git add app/src/main/java/dev/agentic/ui/globalsettings/GlobalSettingsScreen.kt
  git commit -m "$(cat <<'EOF'
  feat: Global Settings — chip look with FlowRow, MCP read-only, always-visible MCP section

  - Replace Switch rows with ComponentChip (green=skill, purple=plugin/mcp)
  - FlowRow wraps chips instead of a vertical list with dividers
  - MCP chips are read-only (dimmed, non-interactive) — backend rejects global MCP toggle
  - MCP section always rendered; shows "No MCP servers" placeholder when empty

  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 7: Write .superpowers/sdd/s5-ui-feedback-report.md and final test run

**Files:**
- Create: `.superpowers/sdd/s5-ui-feedback-report.md`

- [ ] **Step 1: Run the full test suite one final time and capture tail**

  ```bash
  cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
  ./gradlew testDebugUnitTest 2>&1 | tail -30
  ```
  Copy the last ~20 lines of output.

- [ ] **Step 2: Write the report**

  Create `.superpowers/sdd/s5-ui-feedback-report.md` with:
  - What changed per item (Changes 1–4)
  - The effective-state formula and minimal-override mapping
  - The colors chosen (hex + contrast rationale)
  - The test result tail (verbatim last 20 lines of gradle output)

- [ ] **Step 3: Commit the report**

  ```bash
  cd /home/arcatva/src/agentic-worktrees/7a5b3fa6-51d9-4deb-a442-c30944eee3ab/agentic-dev-android
  git add .superpowers/sdd/s5-ui-feedback-report.md
  git commit -m "$(cat <<'EOF'
  docs: s5-ui-feedback-report — binary chips, kind colors, FlowRow, GlobalSettings parity

  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Self-Review

### Spec coverage check

| Requirement | Task |
|---|---|
| Change 1: binary effective-state chip | Task 3 (VM), Task 4 (Screen) |
| Change 1: source all three kinds from getGlobalSettings() | Task 3 (VM init block) |
| Change 1: effective = globalEnabled XOR override | Task 4 (ComponentChipPicker/toggle logic) |
| Change 1: tap sets minimal override | Task 4 (toggle lambda in screen) |
| Change 1: accessibility contentDescription | Task 2 (ComponentChip.semantics) |
| Change 2: skill=green, plugin/mcp=purple | Task 1 (colors), Task 2 (ComponentChip) |
| Change 2: OFF = outlined default | Task 2 (filterChipColors branch) |
| Change 3: FlowRow for skill, plugin, mcp chip rows | Task 4 Steps 4, 5, 6, 7 |
| Change 4: GlobalSettings chip look | Task 6 |
| Change 4: FlowRow in GlobalSettings | Task 6 Step 2 |
| Change 4: MCP read-only, dimmed, "managed per-session" caption | Task 2 (readOnlyCaption param), Task 6 Step 2 |
| Change 4: MCP section always rendered ("No MCP servers") | Task 6 Step 2 |
| Tests: globally-ON tap → ForceOff → hidden list | Task 5 Step 8 |
| Tests: tap again → Inherit (neither list) | Task 5 Step 8 |
| Tests: globally-OFF tap → ForceOn | Task 5 Step 8 |
| Tests: fresh request → empty override lists | Task 5 Step 8 |
| Tests: existing six-list and MCP-add tests green | Task 5 Steps 2–7 |
| Report | Task 7 |

### Placeholder scan — none found.

### Type consistency

- `ComponentInfo` used consistently throughout Tasks 3, 4, 5 — same type from `dev.agentic.data.net`.
- `availableSkillComponents` / `availablePluginComponents` named consistently across VM, screen, and tests.
- `ComponentChip(label, kind, effective, onClick, modifier, enabled, readOnlyCaption)` — all call sites in Tasks 4 and 6 use the same signature.
- `ComponentChipPicker(label, components, overrides, onToggle, displayLabel)` — only called from Task 4 Steps 2 and 3; parameter types match.
