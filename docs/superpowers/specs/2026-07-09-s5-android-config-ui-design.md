# S5 — Android UI for global settings + generalized Filters

- **Date:** 2026-07-09
- **Status:** Spec (autonomous build — architecture pre-approved in the S4 program spec)
- **Repo:** agentic-dev-android (Kotlin + Jetpack Compose, M3 Expressive). Ktor API client.
- **Consumes backend:** S4 (`GET /api/global-settings`, `POST /api/global-settings/toggle`),
  S2 (`extraMcpServers` / `hiddenMcpServers` on create), S3 (`forcedOn*` on create).

## Goal
Surface the config platform in the app: (1) a **Global Settings** screen to toggle
skills/plugins/MCP globally (writes `~/.claude` via the API, affecting the CLI too), and (2) extend
the **New Request Filters** to (a) tri-state per-session overrides and (b) MCP (toggle + inline-add).

Delivered as **two PRs** to keep each coherent and reviewable:
- **S5a — Global Settings screen** (new screen + API + models + nav entry).
- **S5b — New Request: MCP section + tri-state chips.**

## Existing shape to follow
- API client: `app/src/main/java/dev/agentic/data/net/KtorAgenticApi.kt` (`GET /api/skills`,
  `/api/plugins`); interface in `AgenticApi.kt`; DTOs in `data/net/Models.kt`
  (`NewSessionReq{hiddenSkills, hiddenPlugins, ...}`, `SkillInfo`, `PluginInfo`).
- New Request: `ui/newrequest/NewRequestViewModel.kt` (available/selected sets; on submit
  `hiddenSkills = available − selected`), `ui/newrequest/NewRequestScreen.kt` (filter chips).
- Tests: `app/src/test/java/dev/agentic/ui/newrequest/NewRequestViewModelTest.kt` (JVM unit tests via
  `./gradlew testDebugUnitTest`).

## S5a — Global Settings screen
**API (Ktor + interface + DTOs):**
- `data class ComponentInfo(kind: String, id: String, name: String, description: String, source: String, globalEnabled: Boolean)`.
- `suspend fun getGlobalSettings(): List<ComponentInfo>` → `GET /api/global-settings`.
- `suspend fun toggleGlobalComponent(kind: String, id: String, enabled: Boolean): List<ComponentInfo>`
  → `POST /api/global-settings/toggle` with `{kind,id,enabled}` (returns the refreshed list).
**UI:** a `GlobalSettingsScreen` + `GlobalSettingsViewModel`. List grouped by `kind`
(Skills / Plugins / MCP), each row = name + description + a Switch bound to `globalEnabled`.
Flipping a switch calls `toggleGlobalComponent` and updates from the returned list; show a transient
error (snackbar) on failure and revert the switch. Reachable from the app's settings/menu surface
(add a nav entry next to existing top-level destinations in `ui/nav/AppNav.kt`).
**Tests:** VM test with a fake API — toggling maps to the right call and reduces the returned list;
error path reverts.

## S5b — New Request: MCP + tri-state
**Tri-state model.** Replace the binary selected/deselected chip for skills & plugins with a
per-component override of `Inherit | ForceOn | ForceOff` (default `Inherit`). On submit, derive:
- `hiddenSkills` / `hiddenPlugins` / `hiddenMcpServers` = ids set to `ForceOff`.
- `forcedOnSkills` / `forcedOnPlugins` / `forcedOnMcpServers` = ids set to `ForceOn`.
- `Inherit` ids appear in neither list.
The chip cycles Inherit → ForceOn → ForceOff → Inherit on tap (with a distinct visual per state:
outline = inherit, filled/check = force-on, strikethrough/dim = force-off) — keep it legible and M3.
Update `NewSessionReq` DTO with the four new `forcedOn*` + `hiddenMcpServers` + `extraMcpServers`
fields (all default empty). Backward compatible: a chip left at Inherit reproduces today's behavior
only if the component is globally on; Inherit now means "follow global", which is the S4 semantics.

**MCP section.** Fetch MCP components (reuse `GET /api/global-settings`, filter `kind=="mcp"`, or a
dedicated call) and list them as tri-state chips like skills/plugins. Add an **"Add MCP server"**
form producing an `extraMcpServers` entry:
`McpServerDef(name, command?, args?, env?, type?, url?, headers?)` — a transport toggle (stdio/http)
that shows command+args(+env) OR url(+headers). Client-side validation mirrors the backend
(name non-empty, exactly one transport, name ≠ `agentic`); added servers render as removable chips.

**Tests:** VM test — Inherit/ForceOn/ForceOff selections derive the correct six lists on submit;
adding a valid stdio and a valid http server populates `extraMcpServers`; invalid add is rejected;
`agentic` name rejected.

## Constraints
- Follow existing Compose/VM patterns; no new heavyweight deps.
- `./gradlew testDebugUnitTest` green (compile + unit tests) before each PR.
- Per repo workflow: adversarial `delegate` verify → PR → Codex → auto-merge.

## Validation boundary
Unit tests + compile are the automated gate (no emulator/visual QA available headless). The screens
are built to the existing patterns; a human visual pass is advisable post-merge before shipping an APK.

## Acceptance
- S5a: Global Settings screen lists components and toggles them via the API (verified in VM test).
- S5b: New Request derives the six override lists from tri-state selections and carries
  `extraMcpServers`; MCP inline-add validates. `testDebugUnitTest` green for both.
