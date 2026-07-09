# S5a Global Settings Screen — Implementation Report

## Files Added

- `app/src/main/java/dev/agentic/ui/globalsettings/GlobalSettingsViewModel.kt`
  ViewModel: fetches on init, optimistic toggle with revert+error on failure, `clearError()` for snackbar dismissal.

- `app/src/main/java/dev/agentic/ui/globalsettings/GlobalSettingsScreen.kt`
  Composable: loading/empty/content states; components grouped by kind (Skills→Plugins→MCP); ComponentRow = name+description+Switch; snackbar for transient errors via `LaunchedEffect(s.error)`.

- `app/src/test/java/dev/agentic/ui/globalsettings/GlobalSettingsViewModelTest.kt`
  10 unit tests covering: initial load success/failure, grouping by kind, toggle calls API with correct args (`kind`, `id`, `!enabled`), optimistic update, success reduces from returned list, error path reverts + surfaces error, clearError.

## Files Modified

| File | Change |
|------|--------|
| `data/net/Models.kt` | Added `ComponentInfo` + `ToggleComponentReq` DTOs |
| `data/net/AgenticApi.kt` | Added `getGlobalSettings()` + `toggleGlobalComponent()` with default no-op impls |
| `data/net/KtorAgenticApi.kt` | Implemented both methods: GET `/api/global-settings` + POST `/api/global-settings/toggle` |
| `ui/nav/AppNav.kt` | Added `@Serializable object GlobalSettings` route, import, two `HomeAdaptive` calls updated with `onOpenGlobalSettings`, `composable<GlobalSettings>` destination |
| `ui/home/HomeAdaptive.kt` | Added `onOpenGlobalSettings` param to `HomeAdaptive` + `NarrowScaffoldHome`, threaded through |
| `ui/home/HomeScreen.kt` | Added `onOpenGlobalSettings` to `HomeScreen` + `HomeTopBar`; `Icons.Rounded.Settings` icon in compact dropdown + expanded action bar |
| `ui/home/WideThreePaneHome.kt` | Added `onOpenGlobalSettings` to `WideThreePaneHome`, threaded to `HomeTopBar` |
| `data/FakeAgenticApi.kt` | Added `globalSettingsResult`, `getGlobalSettingsException`, `toggleGlobalComponentResult`, `toggleGlobalComponentException`, `toggleGlobalComponentCalls`, `getGlobalSettingsCallCount` |

## Nav + DI Wiring

- Route: `@Serializable object GlobalSettings` in `AppNav.kt`.
- Entry points: Settings icon button in `HomeTopBar` (both compact dropdown and wide expanded bar) calls `onOpenGlobalSettings`, threaded from `AppNav → HomeAdaptive → [NarrowScaffoldHome / WideThreePaneHome] → HomeScreen → HomeTopBar`.
- No new DI: `GlobalSettingsScreen` creates the VM inline via `viewModelFactory { initializer { GlobalSettingsViewModel(container.api) } }`, mirroring `SessionSettingsScreen` / `ProvidersScreen` patterns. `container.api` (`AgenticApi`) is the only dependency.

## Build / Test Result

```
BUILD SUCCESSFUL in 14s
22 actionable tasks: 10 executed, 12 from cache
```

`./gradlew testDebugUnitTest` compiled and all tests passed (including the 10 new `GlobalSettingsViewModelTest` tests). Warnings present are pre-existing deprecations in the codebase (ButtonGroup overload, AutoMirrored icon aliases) — none introduced by this change.

## Concerns / Deviations

- None. All spec requirements implemented: DTOs, API interface + Ktor impl, VM (fetch/toggle/revert), Screen (grouped list, Switch, snackbar), nav entry, fake, and VM tests.
- `local.properties` was not present in the worktree (gitignored); copied from main checkout to run Gradle. The file is not committed.
- S5b (tri-state chips + MCP inline-add) is NOT implemented here per instructions.

---

## Fix wave — S5a adversarial review fixes

### Fix 1: MCP rows read-only (`GlobalSettingsScreen.kt`)

`ComponentRow` gained a `readOnly: Boolean = false` parameter. When `readOnly = true`:
- The `Switch` is rendered with `enabled = false` so it is non-interactive.
- `onCheckedChange` is gated on `!readOnly` so no callback fires even via accessibility.
- A "managed per-session" caption appears under the component description (same style as description text, `onSurfaceVariant`).

The call site passes `readOnly = component.kind == "mcp"`. No other kinds are affected.

### Fix 2: Serialize toggles (`GlobalSettingsViewModel.kt`)

Added `private val toggleMutex = Mutex()` (from `kotlinx.coroutines.sync`). The API call inside `toggle()` is wrapped in `toggleMutex.withLock { ... }`. The optimistic state update still fires immediately (before acquiring the lock) so the UI reflects the flip without delay; only the API call and the subsequent state update are serialized.

Also added a hard guard at the top of `toggle()`: `if (component.kind == "mcp") return`. This prevents any call to `toggleGlobalComponent` for MCP rows regardless of UI state.

A failed toggle still reverts only the affected row (via the existing `component.globalEnabled` capture) and surfaces a transient error message, then releases the lock.

### Fix 3: Strengthened VM tests (`GlobalSettingsViewModelTest.kt`)

Three new tests added under "strengthened toggle tests":

1. **`toggle failure reverts to original enabled and surfaces error`** — starts with a skill at `enabled=false`, injects a failure, asserts the row is back at `false` (not the optimistic `true`), error is non-null and contains the exception message, and `toggling` is empty. Would fail if the revert branch were removed.

2. **`toggle state reflects returned list not naive optimistic flip`** — server returns a list where the toggled row is STILL enabled (backend vetoed the change) and an unrelated row has changed. Asserts both outcomes match the server response, not a naive local flip. Would fail if the VM ignored the returned list.

3. **`mcp toggle does not call toggleGlobalComponent`** — calls `vm.toggle(mcpComponent)` and asserts `toggleGlobalComponentCalls` is empty and the row's `globalEnabled` is unchanged. Would fail if the MCP guard were removed.

### Test result

```
BUILD SUCCESSFUL in 4s
22 actionable tasks: 5 executed, 17 up-to-date
```

All unit tests pass (including the 3 new strengthened tests).
