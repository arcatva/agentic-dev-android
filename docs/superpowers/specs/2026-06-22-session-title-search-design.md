# Session List Title Search — Design

Date: 2026-06-22
Repo: `agentic-dev-android`
Status: Approved (design), pending implementation plan

## Goal

Add a search box to the session list screen so the user can filter sessions by
**title** (`Session.prompt`) as they type. Material 3 Expressive (MD3E) styling,
using the native Material 3 `SearchBar` component.

## Scope

In scope:
- Client-side, real-time filtering of the already-loaded session list by title.
- Native M3 `SearchBar` UI in both the single-pane (`HomeScreen`) and wide
  (`AdaptiveHome` / `SessionListPane`) layouts.

Out of scope (explicitly decided):
- Searching conversation message body / transcript content. (Considered;
  rejected in favor of title-only to keep it client-side and avoid backend work.)
- Searching metadata other than the title (repos, skills, status).
- Any change to the `agentic-dev` backend. No new endpoint.
- New dependencies.

## Background (current state)

- 100% Jetpack Compose, `MaterialExpressiveTheme` (DarkExpressive + expressive
  shapes/motion). Material 3 `androidx.compose.material3:material3:1.4.0-alpha18`.
- Session list: `app/src/main/java/dev/agentic/ui/home/HomeScreen.kt`
  (single pane) and `app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt`
  (wide 3-pane, contains `SessionListPane`). Rows rendered by `SessionRow`.
- State: `HomeViewModel` exposes `uiState: StateFlow<HomeUiState>`;
  `HomeUiState.sessions: List<Session>` is the loaded list.
- `Session` (in `data/net/Models.kt`) has `prompt: String` — the title shown in
  each row (rendered as `prompt.ifBlank { "(no prompt)" }`).
- No existing search anywhere in the app.

## Design

### Interaction (native M3 SearchBar pattern)

- A collapsed M3 `SearchBar` pill sits at the top of the list area, below the
  existing `TopAppBar` ("agentic-dev"). Pill shows a search leading icon and the
  placeholder "Search sessions".
- Tap → expands to the full-width search surface (full screen on phones). The
  expanded surface renders the **title-filtered** session rows, updating live as
  the user types.
- Empty query while expanded → show all sessions (lets the user browse from the
  expanded surface too).
- Tapping a result row opens that session (reuse existing `onOpen`) and collapses
  the search bar.
- Trailing "X" clears the query; back arrow / tapping outside collapses the bar.
- While collapsed, the main list below continues to show **all** sessions
  unfiltered — this is the standard M3 SearchBar behavior (filtering lives in the
  expanded surface only). No change to the main `LazyColumn`'s data source.

### Filtering logic (pure, unit-tested)

New file `app/src/main/java/dev/agentic/domain/SessionSearch.kt`:

```kotlin
package dev.agentic.domain

import dev.agentic.data.net.Session

/** Case-insensitive substring match on the session title (prompt). */
fun List<Session>.filterByTitle(query: String): List<Session> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.prompt.contains(q, ignoreCase = true) }
}
```

- Matches the raw `prompt`. A session with a blank prompt (shown as
  "(no prompt)") will not match any non-empty query — accepted behavior.
- Pure function: no Android/Compose dependencies, trivially unit-testable.

### Components & state

- New file `app/src/main/java/dev/agentic/ui/home/SessionSearchBar.kt`:
  a composable wrapping the M3 `SearchBar` (built with
  `SearchBarDefaults.InputField` — the expressive API) plus the expanded-state
  results `LazyColumn` that reuses the existing `SessionRow`.
- Query string and expanded flag are held in the Compose layer via
  `rememberSaveable` (survive rotation). **No change to `HomeViewModel` or
  `HomeUiState`** — because the main list is never filtered; filtering happens
  only inside the expanded surface.
- Filtered list computed inside the expanded surface:
  `remember(query, sessions) { sessions.filterByTitle(query) }` — instant.

### Placement

- `HomeScreen.kt`: render `SessionSearchBar` as a pinned element at the top of
  the list region, under the existing `TopAppBar`. When expanded it overlays the
  list.
- `AdaptiveHome.kt` `SessionListPane`: render the same `SessionSearchBar` for
  layout parity. State is local per pane (`rememberSaveable`).
- Selection mode: when the list is in multi-select mode (top bar shows
  "N selected"), the search bar is hidden — consistent with the existing top-bar
  mode switch and avoids competing interactions.

### Empty / no-results state

- Inside the expanded surface, if the query is non-blank and the filtered result
  is empty, show a centered message: `No sessions match "<query>"`, using MD3
  typography and `onSurfaceVariant` color.

### Strings

- Inline string literals (project has no `strings.xml`; UI is English, matching
  existing literals like "New request", "agentic-dev"):
  - Placeholder: `Search sessions`
  - Empty state: `No sessions match "<query>"`

### Theme

- Inherits the existing `MaterialExpressiveTheme`. The M3 `SearchBar` is MD3E by
  default. No new theme code, no new dependencies.

## Testing

- TDD core: unit test `filterByTitle` in `app/src/test`:
  - empty / whitespace-only query returns the full list,
  - case-insensitive substring match,
  - substring (not just prefix) match,
  - no-match returns empty,
  - leading/trailing whitespace in the query is trimmed,
  - blank-prompt session is excluded by a non-empty query, included by empty query.
- Optional: a lightweight Compose UI test for expand → type → filtered rows →
  clear, only if the repo already has Compose UI test infra; not added otherwise.

## Files touched

- New: `app/src/main/java/dev/agentic/domain/SessionSearch.kt`
- New: `app/src/main/java/dev/agentic/ui/home/SessionSearchBar.kt`
- New: `app/src/test/.../SessionSearchTest.kt`
- Edit: `app/src/main/java/dev/agentic/ui/home/HomeScreen.kt` (mount search bar)
- Edit: `app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt` (mount search bar
  in `SessionListPane`)

No ViewModel changes, no backend changes, no new dependencies.

## Open questions

None blocking. One accepted decision: blank-prompt sessions are not matched by
non-empty queries.
