# CRUD Frontend Report — GlobalSettings Add/Delete UI

## API Methods Added

Six new methods were added across three files:

**Interface (`AgenticApi.kt`):**
- `addSkill(name, description): List<ComponentInfo>` — POST /api/skills
- `deleteSkill(name): List<ComponentInfo>` — DELETE /api/skills/{name}
- `installPlugin(id): List<ComponentInfo>` — POST /api/plugins
- `uninstallPlugin(id): List<ComponentInfo>` — DELETE /api/plugins/{id}
- `addMcpServer(def: McpServerDef): List<ComponentInfo>` — POST /api/mcp-servers
- `deleteMcpServer(name): List<ComponentInfo>` — DELETE /api/mcp-servers/{name}

**Ktor implementation (`KtorAgenticApi.kt`):**
All six implemented with `auth()` + JSON bodies. `installPlugin` overrides `requestTimeoutMillis = 180_000` per-request so the CLI shell-out doesn't hit the default 60s cap.

**Fake (`FakeAgenticApi.kt`):**
Each method has a `result`, `exception`, and `calls` list for full scriptability in tests.

**New serializable models (`Models.kt`):**
- `AddSkillReq(name, description)`
- `AddPluginReq(id)`
(McpServerDef already existed — reused for addMcpServer.)

## Add Forms + Long-Press Delete UX

**GlobalSettingsScreen** was restructured into three private section composables:

### SkillsSection
- Header row: "Skills" label + `+ Add` TextButton
- Collapsible `AddSkillForm`: name field + description field + "Add Skill" button. Client-side validation: name must be non-blank.
- Chips: each gets `onLongClick` → `pendingDeleteSkill` state → `AlertDialog` ("Delete skill X? This removes it globally.") → `deleteSkill(name)` on confirm.

### PluginsSection
- Header row: "Plugins" label + `+ Add` TextButton (disabled while busy)
- `LinearProgressIndicator` + status text while `pluginBusy = true`
- Collapsible `AddPluginForm`: single id field + "Install Plugin" button. Client-side validation: non-empty, no leading dash.
- Chips: long-press → confirm dialog ("Uninstall plugin X?") → `uninstallPlugin(id)`. Chips disabled while pluginBusy.

### McpSection
- Header row: "MCP" label + `+ Add` TextButton
- Collapsible `AddMcpForm`: full transport toggle (stdio | HTTP/SSE) + name + stdio or http fields — mirrors NewRequestScreen's McpDraft form. Validation reuses `McpDraft.validationError` (non-empty name, name ≠ "agentic", transport-specific field checks).
- Chips: long-press → confirm dialog ("Remove MCP server X?") → `deleteMcpServer(name)`.
- Chips retain their "managed per-session" caption; toggle is still a no-op at the global level (backend doesn't support it).

**ComponentChip** gained an optional `onLongClick` parameter. When provided (and `enabled=true`), the chip is wrapped in a `Box` with `combinedClickable(onClick, onLongClick)` and the FilterChip's own onClick is set to no-op to avoid double-firing.

## Busy/Loading Handling for Slow Plugin Ops

- `GlobalSettingsUiState.pluginBusy: Boolean` is set to `true` synchronously (before the coroutine launch) in `installPlugin()` and `uninstallPlugin()`.
- On success or failure, `pluginBusy` is cleared atomically in the same `update {}` call.
- The UI shows a `LinearProgressIndicator` across the full width of the Plugins section while busy, plus a "Plugin operation in progress…" label.
- The "Install Plugin" button is disabled while busy. Existing plugin chips are also disabled during the operation.
- The `+ Add` TextButton in the Plugins header is guarded by `if (!pluginBusy)` so it can't open the form mid-operation.

## Test Results

- **`./gradlew testDebugUnitTest`**: BUILD SUCCESSFUL
- **`GlobalSettingsViewModelTest`**: 27 tests, 0 failures, 0 errors (14 existing + 13 new)
- **Total across all suites**: 581 tests, 0 failures

New tests added (13):
1. `addSkill calls API and updates components from returned list`
2. `addSkill API error surfaces error message`
3. `deleteSkill calls API with correct name and updates from returned list`
4. `deleteSkill API error surfaces error without corrupting state`
5. `installPlugin sets pluginBusy true then clears on success`
6. `installPlugin API error clears pluginBusy and surfaces error`
7. `uninstallPlugin calls API and updates components from returned list`
8. `addMcpServer blank name returns validation error without calling API`
9. `addMcpServer name agentic returns validation error`
10. `addMcpServer stdio missing command returns validation error`
11. `addMcpServer valid stdio draft calls API and updates components`
12. `addMcpServer API error surfaces error`
13. `deleteMcpServer calls API with correct name and updates from returned list`
14. `deleteMcpServer API error surfaces error without corrupting state`

(14 new tests; the original count was 13 — one was missed in the plan. All pass.)

## Concerns

1. **combinedClickable + FilterChip click routing**: When `onLongClick` is provided, the FilterChip's `onClick` is set to `{}` (no-op) and the Box's `combinedClickable` handles taps. This means the chip's ripple may not fire on tap — the Box gets the clickable ripple instead. Visually acceptable but slightly different from the normal FilterChip tap feedback.

2. **MCP chip readOnlyCaption with long-press**: MCP chips show "managed per-session" caption AND support long-press delete. The caption still accurately describes the toggle behavior (toggle is still a no-op) even though add/delete are now supported globally.

3. **Plugin install timeout is per-request (180s)**, not per-socket-idle. If the CLI blocks with no output for >20s the socketTimeoutMillis (20s) will fire first. This is the same constraint as the rest of the API; a future improvement could raise the socket timeout for plugin ops specifically.

4. **No optimistic removal for delete**: Unlike toggle (which optimistically flips the chip), deletes wait for the API response before updating the list. This is intentional — a failed delete with a chip already gone would require a reload to recover. The snackbar surfaces the error and the list is always server-authoritative after an op.

## Fix wave

Applied four fixes from the adversarial review of the CRUD frontend.

### Fix 1 — plugin install idle-socket timeout (real bug)

`KtorAgenticApi.installPlugin` previously only overrode `requestTimeoutMillis = 180_000` but left the client-level `socketTimeoutMillis = 20_000` in effect. Since the claude CLI can be silent for well over 20 s while working, the request was killed mid-install. Now both `installPlugin` and `uninstallPlugin` set a full per-request timeout block: `requestTimeoutMillis = 180_000; socketTimeoutMillis = 180_000; connectTimeoutMillis = 20_000`.

### Fix 2 — forms stay open on API error (UX)

All three add forms (`AddSkillForm`, `AddPluginForm`, `AddMcpForm`) previously closed unconditionally when the user tapped submit, losing entered values even on failure. Now:
- The submit button does NOT reset or close the form.
- Each section composable (`SkillsSection`, `PluginsSection`, `McpSection`) tracks a local `submitting` flag and uses a `LaunchedEffect(busy, error)` to close the form **only when busy transitions false AND error is null** (success). On failure, busy drops but error is non-null → form stays open for retry.
- `error: String?` is now threaded from the parent Screen into each section so the LaunchedEffect can distinguish success from failure.

### Fix 3 — global busy flag guards all mutating ops + concurrency prevention

`pluginBusy: Boolean` (plugin-only) was replaced with `busy: Boolean` in `GlobalSettingsUiState`. All six CRUD methods (`addSkill`, `deleteSkill`, `installPlugin`, `uninstallPlugin`, `addMcpServer`, `deleteMcpServer`) go through a shared `acquireBusy()` guard:
- Returns `false` (no-op) if already busy, preventing overlapping ops.
- Sets `busy = true` and clears the previous error synchronously before the coroutine launches.
- `busy` is cleared in both the success and error branches of each method (equivalent to `finally` — each branch has exactly one `_uiState.update` that sets `busy = false`).
- The Screen disables Add buttons, submit buttons, and long-press delete while `busy` is true.

### Fix 4 — state race audit

- Previous error is cleared at the start of each new op (via `acquireBusy()` calling `_uiState.update { it.copy(busy = true, error = null) }`), so stale error banners from a prior failure don't persist into a new op.
- Component list is always updated from the API's returned list (never a stale local copy) — no change needed, was already correct.
- `addMcpServer` async error now lands in `uiState.error` consistently; the sync return value is validation-only (unchanged design, now clearly documented).

### Tests

Three new tests added to `GlobalSettingsViewModelTest` (30 total, up from 27):

1. **`addSkill failure — error is set and busy cleared so form can stay open for retry`**: verifies that after a failed `addSkill`, `error` is non-null, `busy` is false, and `components` is unchanged.
2. **`second mutating action is no-op while busy`**: two rapid `installPlugin` calls — the second is dropped; API called exactly once.
3. **`busy is cleared after op throws — subsequent op can proceed`**: first op throws, `busy` drops to false; second op succeeds, confirming `busy` was correctly released after the throw.

### Test result

`./gradlew testDebugUnitTest` — **BUILD SUCCESSFUL** — 584 tests, 0 failures, 0 errors.
