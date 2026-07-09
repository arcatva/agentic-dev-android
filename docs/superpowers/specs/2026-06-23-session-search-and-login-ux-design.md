# 2026-06-23 session search and login UX design

Status: design, ready for review.
Owner: arc.
Replaces: `2026-06-22-session-title-search-design.md` for the title-row search and content search scope.

## Context and motivation

This design bundles three Android UI changes plus one backend addition that the Android changes need:

1. Session list search bar moves onto the same row as the title. Today the search bar lives in `SessionListPane` below usage meters, while the top app bar carries the title. Combining them into one row makes the title, search, and trailing actions read as one header.
2. Session list search can find content, not just the title. The current `SessionSearchBar` filters `state.sessions` via `filterByTitle`, which only looks at `Session.prompt`. Users expect a search to also find text they remember seeing in a session transcript (assistant prose, tools they ran, files they touched, spawned-agent results).
3. The New Request form's chip picker (`NewRequestScreen` → `ChipPicker`) ships an in-line `OutlinedTextField` "Search..." that is not stylistically aligned with Material 3 Expressive search surfaces. Replace it with a shared search component that visually matches the session list search.
4. Login manual and scan screens hide their lower content under the soft keyboard. The activity uses `adjustResize`, but `LoginManualScreen` / `LoginScanScreen` neither scroll nor apply `imePadding`, so the password field and the connect/login button can sit behind the IME. Add proper insets handling and a keyboard-submit affordance.
5. As part of (2) and a separate observation: the Android `TextNode(narration = true)` UI treatment of streamed assistant prose as a collapsed "Notes · <preview>" card is project-local, not an external spec. With content search in scope, the gap between "the user remembers this assistant text" and "the app can find it" widens. Adjust the narration default to match how a CLI transcript reads: stream inline by default, fold only while a turn is in progress.

## Goals

- Search bar visible and discoverable on the same row as the title.
- Content search across session metadata and rendered transcript text (except thinking).
- A consistent Material 3 Expressive search component reused in session list and New Request.
- Login forms lift above the soft keyboard on small screens.
- Narration default aligns with CLI-style rendering without losing the "so the answer isn't blank" guard for killed/crashed turns.

## Non-goals

- Backend full-text index. Linear scan with strict caps is fine for the first version.
- Including thinking text in content search.
- Inline highlighted rich-text snippets on the server. Android can render bold or wrap a span locally if needed.
- Any New Request form refactor beyond replacing the chip search surface.

## UX details

### Session list top bar (single-pane and wide)

`HomeTopBar` layout, left to right, single row:

- Title block: `AutoAwesome` icon + `agentic-dev` (same as today).
- Search input: occupies the remaining width, placeholder `Search sessions`, leading search icon, trailing close icon when non-empty.
- Trailing actions: today's actions collapse into a small `IconButton` cluster (logout, etc.). Selection mode replaces the search input with the existing `N selected` cluster (close + select-all + delete) — search hides in selection mode, same as today.

`SessionListPane` no longer renders the search bar. The list still shows usage meters above the items when `searchQuery` is blank, and hides them when a search is active (so search results are not visually pushed down by the meters). The pull-to-refresh and selection mechanics are unchanged.

`AdaptiveHome` reuses the same `HomeTopBar`; the left pane shows the search surface inside the bar, the right pane keeps its current `Session` title block unchanged.

`NewRequestScreen` top bar is unchanged; only the `ChipPicker` search input is replaced (see below).

### New Request chip search

The in-line `OutlinedTextField` inside `ChipPicker` (placeholder `Search…`) is replaced with the same MD3 Expressive search component used by the session list. The picker keeps its case-insensitive substring filter and preserves the original chip order. Search state remains local to the picker.

### Login

`LoginManualScreen` and `LoginScanScreen`:

- Root container becomes scrollable (`verticalScroll(rememberScrollState())`) and applies `imePadding()`.
- Host field gets `KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)` and a `KeyboardActions(onNext = { passwordFocus.requestFocus() })` jump to the password field.
- Password field gets `KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)` and a `KeyboardActions(onDone = { if (canSubmit) submit() })`. This submits only when the existing enabled rule is met (`host` non-blank, `password` non-empty, not busy; for scan: a server selected, password non-empty, not busy).
- Activity `windowSoftInputMode="adjustResize"` and the existing `enableEdgeToEdge(...)` setup are kept.

### Narration default

Today `appendText` creates `TextNode(text, narration = true)` and `promoteTrailingTextIfNoAnswer` flips the trailing `TextNode` to `narration = false` only when the turn ended without an `AnswerNode`. That produces a transcript that mostly looks like a stack of collapsed "Notes" chips with sparse visible answers.

Change to a CLI-aligned default:

- `TextNode`'s default constructor argument stays `narration = false`.
- `appendText` keeps the same logic but the default is now "render inline" (i.e. `narration = false` is what users see by default).
- `promoteTrailingTextIfNoAnswer` becomes "on turn end, ensure the trailing `TextNode` renders inline". The check "is there an `AnswerNode` after the last `PromptNode`?" is removed: regardless of `AnswerNode` presence, on turn end the trailing `TextNode.narration` is set to `false` so any leftover streamed prose shows up.
- During an active turn (between `PromptNode` and the closing `AnswerNode` / `result` / engine exit), the reducer keeps the most recent `TextNode` folded (`narration = true`) only if its accumulated text length is `< 256` characters. Once the turn ends, the trailing `TextNode.narration` is forced to `false` so any leftover streamed prose shows up. The user-visible rule is "ended turn ⇒ inline".
- `StepCard` rendering of nested text inside spawn cards stays as today; the change is only about top-level `TextNode` rendering.

## API: `GET /api/sessions/search`

Request:

- `q` string, required. Minimum length 2. Shorter queries return `{ query, results: [] }`.
- `limit` int, optional, default 50, max 50.

Response:

```json
{
  "query": "build failed",
  "results": [
    {
      "session": {
        "id": "...",
        "prompt": "...",
        "status": "...",
        "repos": ["..."],
        "createdAt": 1700000000000,
        "endedAt": null
      },
      "score": 12.3,
      "matches": [
        { "field": "notes",      "snippet": "...build failed in step 2...", "lineIndex": 412 },
        { "field": "toolSummary","snippet": "Bash · npm run build",        "lineIndex": 410 }
      ]
    }
  ]
}
```

Allowed `field` values:

- `title`, `repo`, `branch`, `sessionId`, `status`, `error`
- `prompt`
- `notes` (any `TextNode.text`, regardless of `narration`)
- `answer` (`AnswerNode.text`)
- `toolName`, `toolSummary`, `toolDetail`
- `spawnDesc`, `spawnResult`
- `skill`, `workflow`, `ask`, `plan`, `perm`
- `attachment`

Caps:

- At most 3 matches per session.
- Snippet length cap 200 chars; trim on whitespace when possible and prepend `...` if truncated.
- Total `results` cap 50.

Ranking:

1. Title/prompt hit (any of `title`, `prompt`).
2. Metadata hit (`repo`, `branch`, `sessionId`, `status`, `error`).
3. Transcript hit (any `notes`, `answer`, `tool*`, `spawn*`, `skill`, `workflow`, `ask`, `plan`, `perm`, `attachment`).
4. Tie-breaker: `COALESCE(lastUserMessageAt, createdAt)` desc, then `seq` desc — same as the existing session list ordering.

Search input and match generation:

- Case-insensitive substring.
- Whitespace is trimmed.
- Search runs over the rendered projection (same projection the WebSocket and `GET /api/sessions/:id` use) — drop `system` init/retry, raw `user` tool_result, control frames, `engineExit`, `init`, `backfill`, and any non-rendered noise.
- Thinking text is excluded.
- `notes` and `answer` are extracted from the rendered projection the same way `TranscriptReducer` does on the client, so the snippet offset lines up with what the user sees.

Cost guards:

- Per session, stop scanning after 3 matches or after 50,000 rendered lines, whichever first.
- Per query, stop after 50 results.
- The handler reuses the engine's existing `Arc` so it does not block writes or WebSocket streaming.

Auth:

- Same authentication as other session routes (host token / cookie). No anonymous search.

Failure:

- Network/parse failures surface as 4xx/5xx and `Outcome.Failure` on the client; the session list does not wipe and shows no snackbar (search-only error).

## Android data flow

`HomeViewModel`:

- `searchQuery: StateFlow<String>` (default empty).
- `searchResults: StateFlow<List<SearchHit>>` (empty when `query` is blank or shorter than 2).
- `searching: StateFlow<Boolean>`.
- `setSearchQuery(q: String)` debounces 250 ms via the existing IO dispatcher.
- Cancellation: only the latest in-flight search is kept; an older pending search is dropped on a newer query.

`HomeScreen` and `AdaptiveHome`:

- Read `searchQuery` from the VM; when non-blank and `>= 2` chars, render `searchResults`; otherwise render `state.sessions`.
- Pass `query`, `onQueryChange`, `searching`, `results`, `onOpenSession`, `onExpandedChange` into the shared `SearchBar` component.
- FAB hides when `searchExpanded == true` or selection mode is on (existing rule).

`SearchBar`:

- Lives in `ui/components/`. Same shape and motion as the existing `SessionSearchBar`, but stateless (state is owned by the VM and passed in).
- Renders the placeholder, leading search icon, trailing close when `query` is non-empty, and a thin progress bar when `searching` is true.
- Empty state: `No sessions match "<query>"` (matches today's wording).

`NewRequestScreen` chip picker:

- Reuse the same `SearchBar` component (different placeholder: `Search chips…`).
- Filter logic stays in the picker (case-insensitive substring, preserve order).

`LoginManualScreen` and `LoginScanScreen`:

- Add `Modifier.verticalScroll(rememberScrollState())` and `Modifier.imePadding()` to the root `Column` (manual) and to the `Column` that wraps the results list and the bottom password/connect block (scan).
- Host: `KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)` plus `KeyboardActions`.
- Password: `KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)` plus `KeyboardActions` that trigger the existing submit when the form is valid.

## Test plan

### Backend (`agentic-dev/server-rs`)

- `search::service::search_sessions` unit tests:
  - Title match ranks above metadata above transcript.
  - Case-insensitive match, trimmed query, minimum length 2.
  - At most 3 matches per session; snippet length cap; results cap 50.
  - Thinking excluded.
  - `system` init/retry, raw `user` tool_result, control frames, `engineExit`, `init`, `backfill` excluded.
  - Deleted/missing session produces no result and does not crash.
  - Empty/short query returns empty results, not an error.
- Route test for `GET /api/sessions/search`:
  - Returns 200 with the response shape.
  - Returns 400 on missing/empty `q`.
  - Authenticated when other session routes are.

### Android (`agentic-dev-android`)

- `domain/SessionSearchTest`:
  - Existing title tests stay.
  - New: `TextNode.text` matches regardless of `narration`; `ThinkingNode` does not match; trim, case-insensitivity, empty result on blank/short query.
- `data/repo/SessionsRepositoryTest`:
  - `contentSearch(q)` success and failure.
  - Cancellation: only the latest search is observed.
- `ui/home/HomeViewModelTest`:
  - Setting `searchQuery` triggers debounced search; latest wins.
  - Empty query clears `searchResults`.
  - Errors do not wipe `sessions`; `searching` flag toggles.
- `data/net/SessionSerializationTest`:
  - `SearchResponse`, `SearchHit`, `SearchMatch` decode with defaults; tolerate unknown keys.
- `data/FakeAgenticApi`:
  - Scripted `searchSessions` responses for repository and VM tests.
- `ui/login/LoginManualViewModelTest` (or its current home, if any):
  - If new behavior is added to the VM (e.g. submit on password Done), cover the new branch.
- Compose-level search and login tests: not added in this change. There is no existing Compose UI test harness, and adding one for this PR is out of scope.

## Files touched

Backend:

- `agentic-dev/server-rs/src/api/mod.rs` — register `GET /api/sessions/search`.
- `agentic-dev/server-rs/src/api/sessions.rs` — new handler.
- `agentic-dev/server-rs/src/engine/search.rs` (new) — search service, ranking, snippet extraction.
- `agentic-dev/server-rs/src/engine/transcript.rs` — shared helpers for rendered-projection scanning if useful.
- `agentic-dev/server-rs/tests/search.rs` (new) — route + service tests.

Android:

- `agentic-dev-android/app/src/main/java/dev/agentic/data/net/Models.kt` — `SearchResponse`, `SearchHit`, `SearchMatch`.
- `agentic-dev-android/app/src/main/java/dev/agentic/data/net/AgenticApi.kt` — `searchSessions`.
- `agentic-dev-android/app/src/main/java/dev/agentic/data/net/KtorAgenticApi.kt` — implement `searchSessions`.
- `agentic-dev-android/app/src/test/java/dev/agentic/data/FakeAgenticApi.kt` — scriptable response.
- `agentic-dev-android/app/src/main/java/dev/agentic/data/repo/SessionsRepository.kt` — `contentSearch(q)` with debounce and cancellation.
- `agentic-dev-android/app/src/main/java/dev/agentic/ui/components/SearchBar.kt` (new) — stateless MD3 Expressive search component reused by list and new request.
- `agentic-dev-android/app/src/main/java/dev/agentic/ui/home/HomeTopBar.kt` — host the search bar.
- `agentic-dev-android/app/src/main/java/dev/agentic/ui/home/HomeScreen.kt` — drop search bar from `SessionListPane`; use `searchResults` when query is non-blank.
- `agentic-dev-android/app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt` — same.
- `agentic-dev-android/app/src/main/java/dev/agentic/ui/home/HomeViewModel.kt` — `searchQuery` / `searchResults` / `searching` state.
- `agentic-dev-android/app/src/main/java/dev/agentic/ui/home/SessionSearchBar.kt` — keep as a thin wrapper that delegates to the shared `SearchBar`, or delete in favor of the new component. Decision: keep the file as a deprecated thin shim for one release to avoid breaking imports, then delete.
- `agentic-dev-android/app/src/main/java/dev/agentic/ui/newrequest/NewRequestScreen.kt` — replace in-picker search with the shared `SearchBar`.
- `agentic-dev-android/app/src/main/java/dev/agentic/domain/Transcript.kt` — narration default flip; simplify `promoteTrailingTextIfNoAnswer`.
- `agentic-dev-android/app/src/main/java/dev/agentic/ui/session/Transcript.kt` — only if the rendering of `narration = true` is removed; if it is still used transiently, leave the existing branch.
- `agentic-dev-android/app/src/main/java/dev/agentic/ui/login/LoginManualScreen.kt` — scroll + imePadding + keyboard options/actions.
- `agentic-dev-android/app/src/main/java/dev/agentic/ui/login/LoginScanScreen.kt` — scroll + imePadding + keyboard options/actions on the password field.
- `agentic-dev-android/app/src/test/java/dev/agentic/domain/SessionSearchTest.kt` — extend.
- `agentic-dev-android/app/src/test/java/dev/agentic/data/repo/SessionsRepositoryTest.kt` — extend.
- `agentic-dev-android/app/src/test/java/dev/agentic/ui/home/HomeViewModelTest.kt` — extend.
- `agentic-dev-android/app/src/test/java/dev/agentic/data/net/SessionSerializationTest.kt` — extend.
- `agentic-dev-android/app/src/main/AndroidManifest.xml` — no change expected.

## Out of scope / follow-up

- Persistent full-text index for sessions if the linear scan turns out too slow.
- Searching thinking content.
- Search filters by status, repo, date.
- Highlighting matches in the snippet (Android can do this locally with `AnnotatedString` if needed).
- Re-designing the New Request top bar.
