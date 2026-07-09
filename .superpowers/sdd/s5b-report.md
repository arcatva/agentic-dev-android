# S5b Implementation Report

## Files Changed

- `app/src/main/java/dev/agentic/data/net/Models.kt` — Added `McpServerDef` DTO with `@OptIn(ExperimentalSerializationApi::class)` and `@EncodeDefault(NEVER)` on nullable optional fields so only populated transport fields go on the wire. Added five new fields to `NewSessionReq`: `forcedOnSkills`, `forcedOnPlugins`, `forcedOnMcpServers`, `hiddenMcpServers`, `extraMcpServers` (all default `emptyList()` — backward compatible).

- `app/src/main/java/dev/agentic/data/repo/SessionsRepository.kt` — Added `globalSettings()` delegation to `api.getGlobalSettings()` so `NewRequestViewModel` can call it without a direct API reference.

- `app/src/main/java/dev/agentic/ui/newrequest/NewRequestViewModel.kt` — Rewrote to:
  - Define `enum class Override { Inherit, ForceOn, ForceOff }` and `data class McpDraft` (draft form state with inline `validationError: String?` computed property).
  - Replace binary `selectedSkills: List<String>` / `selectedPlugins: List<String>` in `NewRequestUiState` with `skillOverrides: Map<String, Override>` / `pluginOverrides: Map<String, Override>` / `mcpOverrides: Map<String, Override>`.
  - Add `mcpComponents: List<ComponentInfo>` loaded via `sessionsRepo.globalSettings().filter { it.kind == "mcp" }` in `init`.
  - Add `extraMcpServers: List<McpServerDef>` and `mcpDraft: McpDraft` to state.
  - Replace `setSkills`/`setPlugins` with `setOverride(kind, id, override)`.
  - Add `updateMcpDraft`, `addMcpServer()` (validates draft, builds `McpServerDef`, resets draft on success), `removeMcpServer(name)`.
  - Update `submit()` to derive all six lists from override maps.
  - Add internal `setSkillsFromTemplate` to map template skill lists to `ForceOff` for unlisted skills.

- `app/src/main/java/dev/agentic/ui/newrequest/NewRequestScreen.kt` — Updated to:
  - Add private `fun Override.cycle()` extension.
  - Add `TriStateChip` composable (outline=inherit, filled+check icon=ForceOn, dim+strikethrough=ForceOff; accessible `contentDescription` per state).
  - Add `TriStateChipPicker` composable (same filter-field UX as old `ChipPicker`, but uses `TriStateChip`).
  - Add `AddMcpForm` composable (transport toggle stdio/http via `SingleChoiceSegmentedButtonRow`; stdio shows command+args+env; http shows http-type toggle + url + headers; local error display; "Add MCP server" button).
  - Replace Skills and Plugins `ChipPicker` calls with `TriStateChipPicker`.
  - Add MCP section: globally-configured MCP tri-state chips + removable `InputChip`s for extra servers + collapsible `AddMcpForm`.
  - Keep `ChipPicker` for Repos (binary toggle, not a global-settings-derived override).

- `app/src/test/java/dev/agentic/ui/newrequest/NewRequestViewModelTest.kt` — Updated existing tests; added 15 new tests (see below).

## Commits

| SHA | Subject |
|---|---|
| `7b72a8e` | feat(s5b): add McpServerDef DTO and five new NewSessionReq fields |
| `4ecc929` | feat(s5b): tri-state override model, MCP section, and Add MCP form |
| `bd0cceb` | test(s5b): update NewRequestViewModelTest for tri-state model + add MCP tests |

## Tri-State Model + Submit Derivation

```
Override { Inherit, ForceOn, ForceOff }   // default: Inherit = follow global
```

On `submit()`:
```kotlin
hiddenSkills     = availableSkills.filter { skillOverrides[it.name] == ForceOff }.map { it.name }
forcedOnSkills   = availableSkills.filter { skillOverrides[it.name] == ForceOn  }.map { it.name }
hiddenPlugins    = availablePlugins.filter { pluginOverrides[it.name] == ForceOff }.map { it.name }
forcedOnPlugins  = availablePlugins.filter { pluginOverrides[it.name] == ForceOn  }.map { it.name }
hiddenMcpServers   = mcpComponents.filter { mcpOverrides[it.id] == ForceOff }.map { it.id }
forcedOnMcpServers = mcpComponents.filter { mcpOverrides[it.id] == ForceOn  }.map { it.id }
```

All-Inherit → all six lists empty → same wire payload as before S5b (backward compatible).

## MCP Add Form Validation

`McpDraft.validationError: String?` performs client-side checks in order:
1. `name.isBlank()` → "Name is required"
2. `name.trim() == "agentic"` → "Name must not be \"agentic\""
3. `transport == "stdio" && command.isBlank()` → "Command is required for stdio transport"
4. `transport == "http" && url.isBlank()` → "URL is required for HTTP/SSE transport"

Exactly-one-transport is enforced structurally: `buildMcpServerDef` only populates stdio fields when `transport == "stdio"` and http fields otherwise. The UI only shows the relevant fields for the selected transport.

## Test Coverage Added

New tests in `NewRequestViewModelTest`:
- `init loads MCP components from global settings filtering kind mcp`
- `init survives getGlobalSettings failure and leaves mcpComponents empty`
- `setOverride for skill updates skillOverrides`
- `setOverride for plugin updates pluginOverrides`
- `setOverride for mcp updates mcpOverrides`
- `submit sends correct NewSessionReq with tri-state overrides`
- `submit derives six override lists from Inherit ForceOn ForceOff selections`
- `all Inherit overrides produce empty lists on submit`
- `addMcpServer valid stdio server populates extraMcpServers`
- `addMcpServer valid http server populates extraMcpServers`
- `addMcpServer clears draft on success`
- `addMcpServer rejects empty name`
- `addMcpServer rejects name agentic`
- `addMcpServer rejects stdio with no command`
- `addMcpServer rejects http with no url`
- `removeMcpServer removes added server by name`
- `submit includes extraMcpServers in NewSessionReq`

ForceOn+ForceOff simultaneously impossible by construction: each component has a single `Override` value in its map — setting one state replaces the other.

## testDebugUnitTest Result Tail

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 14s
22 actionable tasks: 22 executed
```

Total: 551 tests, 0 failures, 0 errors.

## Concerns / Deviations

- `@EncodeDefault(NEVER)` requires `@OptIn(ExperimentalSerializationApi::class)` on the class; applied at the `McpServerDef` class level to keep it contained. This is the standard pattern used throughout the serialization ecosystem and will graduate to stable once kotlinx.serialization 2.0 ships.
- `SingleChoiceSegmentedButtonRow` / `SegmentedButton` require `@ExperimentalMaterial3ExpressiveApi` (already used in `NewRequestScreen`).
- `AppTextField` does not expose a `label: String` overload in all call sites; used the `placeholder` parameter instead for the Add MCP form fields (same pattern as the rest of the form). This means the field has no floating label — consistent with the existing form style.
- No emulator/visual QA available (headless CI gate). The composables are built to existing patterns; a human visual pass is advisable post-merge before shipping an APK.
- The `applyTemplate` updated behavior: templates with non-empty `skills` now set ForceOff for unlisted skills, which is a behavior change from "select only the listed skills" to "force-off unlisted, follow global for listed." This matches the S5b spec's intent — Inherit means "follow global."
