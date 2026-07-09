# Session List Title Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an MD3 Expressive `SearchBar` to the session list that filters sessions by title (`Session.prompt`) as the user types, client-side and instant.

**Architecture:** A pure `List<Session>.filterByTitle(query)` function (unit-tested) does the matching. A new `SessionSearchBar` composable wraps the Material 3 `SearchBar`: collapsed it is a pill at the top of the list; expanded it shows the live-filtered sessions in its full-width surface, reusing the existing `SessionRow`. It is mounted once inside the shared `SessionListPane`, so both the single-pane `HomeScreen` and the wide `AdaptiveHome` get it. The collapsed list below is never filtered (standard M3 SearchBar behaviour). No ViewModel or backend changes.

**Tech Stack:** Kotlin, Jetpack Compose, `androidx.compose.material3:material3:1.4.0-alpha18` (Material 3 Expressive), JUnit4 + kotlinx-coroutines-test for unit tests.

## Global Constraints

- Compose Material3 is pinned to `1.4.0-alpha18`; do NOT bump it (compileSdk/AGP are pinned to match). The classic `SearchBar(inputField=…, expanded=…, onExpandedChange=…){content}` + `SearchBarDefaults.InputField(query, onQueryChange, onSearch, expanded, onExpandedChange, …)` API is present in this version and requires `@OptIn(ExperimentalMaterial3Api::class)`.
- Do NOT build the APK in this worktree (no Gradle wrapper, no signing keystore). Unit tests DO run here via the system Gradle once `local.properties` points at the SDK (see Task 1, Step 0). APK build happens in the main checkout `~/src/agentic-dev-android` (Task 4).
- UI strings are inline literals (no `strings.xml`); app UI is English. Use: placeholder `Search sessions`, empty state `No sessions match "<query>"`, clear-button content description `Clear search`.
- Match on the raw `Session.prompt`. A blank-prompt session (shown as `(no prompt)`) is intentionally NOT matched by any non-empty query.
- Commit after each task. Push to master only in Task 4 (the deliver step).

---

### Task 1: Pure title-filter function

**Files:**
- Create: `app/src/main/java/dev/agentic/domain/SessionSearch.kt`
- Test: `app/src/test/java/dev/agentic/domain/SessionSearchTest.kt`

**Interfaces:**
- Consumes: `dev.agentic.data.net.Session` (existing data class; `prompt: String` field).
- Produces: `fun List<Session>.filterByTitle(query: String): List<Session>` in package `dev.agentic.domain` — used by Task 2.

- [ ] **Step 0: One-time — let Gradle find the SDK in this worktree**

The worktree has no `local.properties`. Create it (it is gitignored — verified at `.gitignore:4`, so it will never be committed) so `testDebugUnitTest` can configure the Android project:

```bash
cd /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/agentic-dev-android
[ -f local.properties ] || echo 'sdk.dir=/home/arcatva/Android/Sdk' > local.properties
git check-ignore local.properties   # must print: local.properties
```

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/agentic/domain/SessionSearchTest.kt`:

```kotlin
package dev.agentic.domain

import dev.agentic.data.net.Session
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSearchTest {
    private fun s(id: String, prompt: String) = Session(id = id, prompt = prompt)

    private val sessions = listOf(
        s("1", "Fix login bug"),
        s("2", "Add search box to list"),
        s("3", "Refactor LIST pane"),
        s("4", ""),
    )

    @Test
    fun `blank or whitespace query returns the full list unchanged`() {
        assertEquals(sessions, sessions.filterByTitle(""))
        assertEquals(sessions, sessions.filterByTitle("   "))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(listOf("2", "3"), sessions.filterByTitle("list").map { it.id })
    }

    @Test
    fun `matches a substring anywhere in the title`() {
        assertEquals(listOf("1"), sessions.filterByTitle("ogin").map { it.id })
    }

    @Test
    fun `leading and trailing whitespace in the query is trimmed`() {
        assertEquals(listOf("1"), sessions.filterByTitle("  login  ").map { it.id })
    }

    @Test
    fun `no match returns an empty list`() {
        assertEquals(emptyList<String>(), sessions.filterByTitle("zzz").map { it.id })
    }

    @Test
    fun `a blank-prompt session is excluded by a non-empty query`() {
        assertEquals(emptyList<String>(), listOf(s("4", "")).filterByTitle("x").map { it.id })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/agentic-dev-android
~/.local/share/gradle-8.10.2/bin/gradle -p . :app:testDebugUnitTest \
  --tests "dev.agentic.domain.SessionSearchTest" --console=plain
```
Expected: FAIL — compilation error `unresolved reference: filterByTitle` (the function does not exist yet).

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/dev/agentic/domain/SessionSearch.kt`:

```kotlin
package dev.agentic.domain

import dev.agentic.data.net.Session

/**
 * Case-insensitive substring filter on the session title ([Session.prompt]).
 * A blank/whitespace query returns the list unchanged. Matches the raw prompt, so a
 * blank-prompt session is never matched by a non-empty query.
 */
fun List<Session>.filterByTitle(query: String): List<Session> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.prompt.contains(q, ignoreCase = true) }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/agentic-dev-android
~/.local/share/gradle-8.10.2/bin/gradle -p . :app:testDebugUnitTest \
  --tests "dev.agentic.domain.SessionSearchTest" --console=plain
```
Expected: `BUILD SUCCESSFUL`, all 6 tests pass.

- [ ] **Step 5: Commit**

```bash
cd /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/agentic-dev-android
git add app/src/main/java/dev/agentic/domain/SessionSearch.kt \
        app/src/test/java/dev/agentic/domain/SessionSearchTest.kt
git commit -m "feat: add filterByTitle session search predicate"
```

---

### Task 2: `SessionSearchBar` composable

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/home/HomeScreen.kt` (change `SessionRow` visibility `private` → `internal`, line 436)
- Create: `app/src/main/java/dev/agentic/ui/home/SessionSearchBar.kt`

**Interfaces:**
- Consumes: `dev.agentic.domain.filterByTitle` (Task 1); existing `SessionRow(session, now, openHighlight, inSelectionMode, checked, unread, onClick, onLongClick, modifier)` in `HomeScreen.kt`.
- Produces: `@Composable internal fun SessionSearchBar(sessions: List<Session>, onOpen: (String) -> Unit, modifier: Modifier = Modifier, onExpandedChange: (Boolean) -> Unit = {})` — mounted by Task 3.

There is no Compose UI-test harness in this project (only JVM unit tests under `app/src/test`; no `androidTest`, no compose-ui-test dependency). So this composable is verified by **compilation** here and by **manual run** in Task 4 — not by an automated UI test. Do not invent a UI test framework.

- [ ] **Step 1: Make `SessionRow` reusable from the new file**

In `app/src/main/java/dev/agentic/ui/home/HomeScreen.kt`, change the `SessionRow` declaration visibility (currently at line 436) from `private` to `internal`:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionRow(
    session: Session,
    now: Long,
    openHighlight: Boolean,
    inSelectionMode: Boolean,
    checked: Boolean,
    unread: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
```

(Only the `private` → `internal` keyword changes; the body is unchanged.)

- [ ] **Step 2: Create the `SessionSearchBar` composable**

Create `app/src/main/java/dev/agentic/ui/home/SessionSearchBar.kt`:

```kotlin
package dev.agentic.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.agentic.data.net.Session
import dev.agentic.domain.filterByTitle

/**
 * MD3 SearchBar over the session list. Collapsed: a pill at the top of the list. Expanded: a
 * full-width search surface whose results are the sessions whose title ([Session.prompt]) matches
 * the query, case-insensitive. Filtering is client-side and instant; the collapsed list below is
 * never filtered (standard M3 SearchBar behaviour). Tapping a result opens it and collapses the bar.
 *
 * [onExpandedChange] lets the host hide its FAB while the search surface is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionSearchBar(
    sessions: List<Session>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Relative ages don't need to tick inside the transient search surface; sample the clock once.
    val now = remember { System.currentTimeMillis() }
    val results = remember(query, sessions) { sessions.filterByTitle(query) }

    // Collapsing clears the query so the collapsed pill never shows a stale filter that the
    // (unfiltered) list below doesn't reflect. Also notifies the host (FAB hide).
    val setExpanded: (Boolean) -> Unit = { exp ->
        expanded = exp
        if (!exp) query = ""
        onExpandedChange(exp)
    }

    SearchBar(
        modifier = modifier.fillMaxWidth(),
        // The Scaffold/top app bar above already consumed the status-bar inset; zero it here so the
        // collapsed pill isn't pushed down by a second top inset.
        windowInsets = WindowInsets(0, 0, 0, 0),
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = { setExpanded(false) },
                expanded = expanded,
                onExpandedChange = setExpanded,
                placeholder = { Text("Search sessions") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
            )
        },
        expanded = expanded,
        onExpandedChange = setExpanded,
    ) {
        // Expanded-surface content (ColumnScope): live-filtered results, or an empty-state message.
        if (query.isNotBlank() && results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No sessions match \"${query.trim()}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(results, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        now = now,
                        openHighlight = false,
                        inSelectionMode = false,
                        checked = false,
                        unread = false,
                        onClick = {
                            onOpen(session.id)
                            setExpanded(false)
                        },
                        onLongClick = {},
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: Verify it compiles**

Compilation is exercised by the unit-test build (it compiles the debug main source set):

```bash
cd /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/agentic-dev-android
~/.local/share/gradle-8.10.2/bin/gradle -p . :app:compileDebugKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`. (If it reports an unresolved `Search`/`Close` icon, confirm `material-icons-extended` is still a dependency in `app/build.gradle.kts` — it is, at line 60.)

- [ ] **Step 4: Commit**

```bash
cd /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/agentic-dev-android
git add app/src/main/java/dev/agentic/ui/home/SessionSearchBar.kt \
        app/src/main/java/dev/agentic/ui/home/HomeScreen.kt
git commit -m "feat: add SessionSearchBar (M3 SearchBar) composable"
```

---

### Task 3: Mount the search bar and hide the FAB while searching

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/home/HomeScreen.kt` (`SessionListPane` signature + body; `HomeScreen` FAB gate)
- Modify: `app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt` (`SessionListPane` call + FAB gate)

**Interfaces:**
- Consumes: `SessionSearchBar(...)` (Task 2).
- Produces: `SessionListPane` gains a new trailing optional param `onSearchExpandedChange: (Boolean) -> Unit = {}`. Both call sites pass it.

- [ ] **Step 1: Add the search bar + new param to `SessionListPane`**

In `HomeScreen.kt`, add `onSearchExpandedChange` to the `SessionListPane` signature (insert after `onLongPress`, before `modifier`):

```kotlin
internal fun SessionListPane(
    state: HomeUiState,
    selectedId: String?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onSearchExpandedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
) {
```

Then, inside the `else ->` branch, mount the search bar as the FIRST child of `Column(Modifier.fillMaxSize())` — before the server-unreachable banner. The existing code reads:

```kotlin
            else -> {
                Column(Modifier.fillMaxSize()) {
                    // PR-9: server-unreachable banner (first-load failure only).
                    if (state.serverUnreachable) {
```

Change it to:

```kotlin
            else -> {
                Column(Modifier.fillMaxSize()) {
                    // Title search. Hidden in multi-select mode (the top bar is the "N selected"
                    // contextual bar then, and search shouldn't compete with selection).
                    if (!state.selectionMode) {
                        SessionSearchBar(
                            sessions = state.sessions,
                            onOpen = onOpen,
                            onExpandedChange = onSearchExpandedChange,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    // PR-9: server-unreachable banner (first-load failure only).
                    if (state.serverUnreachable) {
```

(`dp` and `padding` are already imported in `HomeScreen.kt`.)

- [ ] **Step 2: Hide the `HomeScreen` FAB while searching**

In `HomeScreen.kt`, inside `fun HomeScreen(...)`, add a `searching` state next to the existing `listState`/`fabExpanded` block (around line 109):

```kotlin
    val listState = rememberLazyListState()
    var searching by rememberSaveable { mutableStateOf(false) }
```

(`rememberSaveable` import: add `import androidx.compose.runtime.saveable.rememberSaveable` — `mutableStateOf`, `getValue`, `setValue` are already imported.)

Change the FAB gate from `if (!s.selectionMode)` to also check `!searching`:

```kotlin
        floatingActionButton = {
            // Hide the New-request FAB while picking sessions or while the search surface is open.
            if (!s.selectionMode && !searching) {
                ExtendedFloatingActionButton(
```

And pass the callback into the `SessionListPane` call in `HomeScreen`:

```kotlin
        SessionListPane(
            state = s,
            selectedId = null,
            onOpen = onOpenSession,
            onDelete = resolvedVm::delete,
            onRefresh = resolvedVm::refresh,
            onToggleSelect = resolvedVm::toggleSelection,
            onLongPress = resolvedVm::toggleSelection,
            onSearchExpandedChange = { searching = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            listState = listState,
        )
```

- [ ] **Step 3: Hide the `AdaptiveHome` FAB while searching and pass the callback**

In `AdaptiveHome.kt`, add a `searching` state next to the existing `listState`/`fabExpanded` block (around line 103). `rememberSaveable`, `mutableStateOf`, `getValue`, `setValue` are already imported in this file:

```kotlin
    val listState = rememberLazyListState()
    var searching by rememberSaveable { mutableStateOf(false) }
```

Pass the callback into the `SessionListPane` call (around line 145):

```kotlin
                    SessionListPane(
                        state = homeState,
                        selectedId = selectedId,
                        onOpen = { selectedId = it },
                        onDelete = homeVm::delete,
                        onRefresh = homeVm::refresh,
                        onToggleSelect = homeVm::toggleSelection,
                        onLongPress = homeVm::toggleSelection,
                        onSearchExpandedChange = { searching = it },
                        modifier = Modifier.fillMaxSize(),
                        listState = listState,
                    )
```

Change the left-pane FAB gate from `if (!homeState.selectionMode)` to also check `!searching`:

```kotlin
                    // Hide the FAB while multi-selecting or while the search surface is open.
                    if (!homeState.selectionMode && !searching) {
                        ExtendedFloatingActionButton(
```

- [ ] **Step 4: Verify the whole module compiles**

```bash
cd /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/agentic-dev-android
~/.local/share/gradle-8.10.2/bin/gradle -p . :app:testDebugUnitTest --console=plain
```
Expected: `BUILD SUCCESSFUL`; all existing unit tests plus `SessionSearchTest` pass (compiles `HomeScreen.kt` + `AdaptiveHome.kt` + `SessionSearchBar.kt`).

- [ ] **Step 5: Commit**

```bash
cd /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/agentic-dev-android
git add app/src/main/java/dev/agentic/ui/home/HomeScreen.kt \
        app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt
git commit -m "feat: mount session search bar and hide FAB while searching"
```

---

### Task 4: Build the signed APK and deliver

This task cannot run in the worktree (no keystore). It pushes to master, builds in the main checkout, and copies the APK to the outbox for download. Then a human verifies on a device.

**Files:** none changed (build + deliver only).

- [ ] **Step 1: Push the worktree branch to master**

```bash
cd /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/agentic-dev-android
git push origin HEAD:master \
  || (git pull --rebase origin master && git push origin HEAD:master)
```
Expected: push succeeds (fast-forward or after rebase).

- [ ] **Step 2: Build the release APK in the main checkout**

```bash
cd ~/src/agentic-dev-android && git pull --ff-only origin master
~/.local/share/gradle-8.10.2/bin/gradle assembleRelease --console=plain
```
Expected: `BUILD SUCCESSFUL`; APK at `~/src/agentic-dev-android/app/build/outputs/apk/release/app-release.apk`.

- [ ] **Step 3: Deliver the APK to the outbox (renamed by build time)**

```bash
mkdir -p /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/outbox
cp ~/src/agentic-dev-android/app/build/outputs/apk/release/app-release.apk \
  "/home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/outbox/$(date +%Y%m%d-%H%M).apk"
ls -la /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/outbox/
```
Expected: a `<YYYYMMDD-HHMM>.apk` appears in the outbox.

- [ ] **Step 4: Manual verification checklist (human, on device)**

Install the delivered APK and confirm on the session list screen:
- A search pill labelled "Search sessions" sits at the top of the list, below the "agentic-dev" bar.
- Tapping it expands the full-width search surface.
- Typing filters the results live by title; matching is case-insensitive and matches substrings mid-title.
- The clear (✕) button empties the query; the back gesture/arrow collapses the bar.
- Tapping a result opens that session; the bar collapses and the query resets.
- While the search surface is open, the "New request" FAB is hidden; it returns when collapsed.
- Entering multi-select mode (long-press a row) hides the search pill; leaving it restores the pill.
- A query with no matches shows `No sessions match "<query>"`.

---

## Self-Review

**Spec coverage** (against `docs/superpowers/specs/2026-06-22-session-title-search-design.md`):
- Title-only client-side filter → Task 1 (`filterByTitle`).
- Native M3 `SearchBar`, live-filtered results in expanded surface → Task 2.
- Reuse `SessionRow`, query/expanded as `rememberSaveable`, no ViewModel change → Task 2.
- Mounted in shared `SessionListPane` (covers single-pane + wide) → Task 3.
- Hidden in selection mode → Task 3, Step 1.
- Empty/no-results state → Task 2, Step 2.
- Inline English strings, MD3E theme inherited, no new deps → Tasks 2–3, Global Constraints.
- Unit test for the filter → Task 1.
- Enhancement beyond spec: hide the FAB while the search surface is open (Task 3) — keeps the expanded surface clean; consistent with the spec's polish intent.

**Placeholder scan:** none — every step has concrete code/commands and expected output.

**Type consistency:** `filterByTitle(query: String): List<Session>` is defined in Task 1 and called identically in Task 2. `SessionSearchBar(sessions, onOpen, modifier, onExpandedChange)` is defined in Task 2 and called with those exact named args in Task 3. `SessionListPane`'s new `onSearchExpandedChange: (Boolean) -> Unit` param is defined and passed in both call sites. `SessionRow` is called in Task 2 with the exact parameter names from its declaration.

## Known limitations (acceptable for v1)

- In the wide `AdaptiveHome` layout the search bar lives in the (narrow, drag-resizable) list pane, so its expanded surface fills that pane rather than the whole window. Acceptable; the primary target is the phone single-pane.
- Search matches only the title (`prompt`), by explicit decision. Conversation-content search was considered and deferred (would require a backend search endpoint).
