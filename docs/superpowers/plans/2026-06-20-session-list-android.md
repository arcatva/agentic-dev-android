# Session List Android Implementation Plan (agentic-dev-android)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render workflow-running sessions as running, show a persistent unread dot when a finished session hasn't been read since it completed (read state stored locally), and consume the backend's new sort order.

**Architecture:** Pure client changes in `agentic-dev-android` (Kotlin/Compose, MVVM, manual DI). New `Session` fields (`endedAt`, `workflowRunning`) come from the backend (Plan A). A small `domain` layer (`indicatorStatus`/`indicatorAwaitingInput`/`isSessionUnread`) computes the per-row display; a new on-device `ReadStateStore` (SharedPreferences, mirroring `SettingsStore`/`SessionUiStore`) holds read timestamps + a first-launch baseline; `HomeViewModel` folds them into `unreadIds`; `StatusIndicator` resolves its existing transient check into the already-present `Glyph.DOT`; opening a session (`SessionScreen`/`AdaptiveHome`) marks it read. Sorting is server-side (Plan A) — the client shows server order unchanged.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose 1.8.2 / Material3 1.4.0-alpha18, JUnit4 + kotlinx-coroutines-test 1.8.1, SharedPreferences, manual DI (`AppContainer`). No Hilt/Koin, no Robolectric, no Compose UI-test harness.

## Global Constraints

- **Depends on Plan A** (backend) for the `workflowRunning` field; `endedAt` is already serialized by the backend today. Android fields have defaults, so the app compiles/works even before Plan A ships (`workflowRunning` defaults to false).
- Single `:app` module; package root `dev.agentic`. Min SDK 26, Java 17.
- Run unit tests via the explicit Gradle binary (no `./gradlew` wrapper exists): `~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest --tests "<fqcn>"`. Compile-only check: `~/.local/share/gradle-8.10.2/bin/gradle :app:compileDebugKotlin`. If the worktree lacks Android SDK config and Gradle refuses, run the same commands in the main checkout `~/src/agentic-dev-android` after pushing (per that repo's CLAUDE.md build-and-deliver flow).
- **Never build the APK in the worktree** (no keystore). Final APK is produced in the main checkout (`gradle assembleRelease`) and delivered via `outbox/` per the repo CLAUDE.md.
- Unit tests use JUnit4 + `kotlinx-coroutines-test` with fakes (`FakeAgenticApi`, `FakeSettingsStore`); no mocking libraries. Compose composables have no unit-test harness — presentation tasks are verified by a clean compile + `@Preview`.
- Persistence keys live in the shared `"agentic"` SharedPreferences file.
- Read/unread state is local to the device (no backend sync).
- Working directory for all commands: `/home/arcatva/src/agentic-worktrees/02e74d6e-44e0-49d6-be42-1ae988440f6e/agentic-dev-android`.

---

### Task B1: Add `endedAt` and `workflowRunning` to the `Session` model

**Files:**
- Modify: `app/src/main/java/dev/agentic/data/net/Models.kt` (`Session` data class)
- Test: `app/src/test/java/dev/agentic/data/net/SessionSerializationTest.kt` (create)

**Interfaces:**
- Produces: `Session.endedAt: Long?` (epoch ms turn-end, null if never finished) and `Session.workflowRunning: Boolean` (default false). Both deserialized from the backend JSON; defaults keep older-server responses valid.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/agentic/data/net/SessionSerializationTest.kt`:

```kotlin
package dev.agentic.data.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `decodes endedAt and workflowRunning when present`() {
        val s = json.decodeFromString<Session>(
            """{"id":"s1","status":"done","endedAt":1700,"workflowRunning":true}"""
        )
        assertEquals(1700L, s.endedAt)
        assertTrue(s.workflowRunning)
    }

    @Test fun `defaults endedAt=null and workflowRunning=false when absent`() {
        val s = json.decodeFromString<Session>("""{"id":"s1","status":"done"}""")
        assertNull(s.endedAt)
        assertFalse(s.workflowRunning)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest --tests "dev.agentic.data.net.SessionSerializationTest"`
Expected: FAIL — compilation error / unresolved `endedAt` and `workflowRunning` on `Session`.

- [ ] **Step 3: Add the fields**

In `app/src/main/java/dev/agentic/data/net/Models.kt`, in the `Session` data class, add `endedAt` after `startedAt`, and `workflowRunning` after `awaitingInput`:

```kotlin
    val createdAt: Long = 0,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val worktreePath: String? = null,
    val branch: String? = null,
    val worktreeState: String? = null,
    val activity: Activity? = null,
    // true = turn finished, session idle and accepting a new message even while a background workflow
    // runs; false = busy mid-turn. null only before the session's first turn has started.
    val awaitingInput: Boolean? = null,
    // Runtime-only flag from the backend: true when the turn is done/idle but a background workflow is
    // still running, so the list shows the session as running. Defaults false for older servers.
    val workflowRunning: Boolean = false,
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest --tests "dev.agentic.data.net.SessionSerializationTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/agentic/data/net/Models.kt app/src/test/java/dev/agentic/data/net/SessionSerializationTest.kt
git commit -m "feat(model): add endedAt and workflowRunning to Session

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task B2: Domain helpers — indicator status and unread predicate

**Files:**
- Create: `app/src/main/java/dev/agentic/domain/SessionIndicator.kt`
- Test: `app/src/test/java/dev/agentic/domain/SessionIndicatorTest.kt` (create)

**Interfaces:**
- Consumes: `Session` (B1); `statusVisual()`, `StatusVisual` (`domain/Status.kt`); `hasError()`, `isBenignCap()` (`domain/StopReason.kt`).
- Produces:
  - `indicatorStatus(session: Session): String`
  - `indicatorAwaitingInput(session: Session): Boolean?`
  - `isSessionUnread(session: Session, lastReadAt: Long?, baseline: Long): Boolean`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/dev/agentic/domain/SessionIndicatorTest.kt`:

```kotlin
package dev.agentic.domain

import dev.agentic.data.net.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionIndicatorTest {
    private fun session(
        status: String = "done",
        errorKind: String? = null,
        awaitingInput: Boolean? = null,
        endedAt: Long? = null,
        workflowRunning: Boolean = false,
    ) = Session(
        id = "s", status = status, errorKind = errorKind,
        awaitingInput = awaitingInput, endedAt = endedAt, workflowRunning = workflowRunning,
    )

    @Test fun `workflow running shows running`() {
        assertEquals("running", indicatorStatus(session(status = "done", workflowRunning = true)))
        assertEquals(false, indicatorAwaitingInput(session(status = "done", workflowRunning = true)))
    }

    @Test fun `failed maps to failed`() {
        assertEquals("failed", indicatorStatus(session(status = "failed")))
    }

    @Test fun `benign cap renders like done`() {
        assertEquals("done", indicatorStatus(session(status = "running", errorKind = "wall_timeout")))
    }

    @Test fun `done after last read is unread`() {
        assertTrue(isSessionUnread(session(status = "done", endedAt = 200), lastReadAt = 100, baseline = 0))
    }

    @Test fun `done before last read is read`() {
        assertFalse(isSessionUnread(session(status = "done", endedAt = 50), lastReadAt = 100, baseline = 0))
    }

    @Test fun `never-read completion after baseline is unread`() {
        assertTrue(isSessionUnread(session(status = "done", endedAt = 200), lastReadAt = null, baseline = 100))
    }

    @Test fun `never-read completion before baseline is read`() {
        assertFalse(isSessionUnread(session(status = "done", endedAt = 50), lastReadAt = null, baseline = 100))
    }

    @Test fun `failed session is never an unread dot`() {
        assertFalse(isSessionUnread(session(status = "failed", endedAt = 200), lastReadAt = 0, baseline = 0))
    }

    @Test fun `running workflow is not unread`() {
        assertFalse(isSessionUnread(session(status = "done", endedAt = 200, workflowRunning = true), lastReadAt = 0, baseline = 0))
    }

    @Test fun `done with no endedAt is not unread`() {
        assertFalse(isSessionUnread(session(status = "done", endedAt = null), lastReadAt = null, baseline = 0))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest --tests "dev.agentic.domain.SessionIndicatorTest"`
Expected: FAIL — unresolved references `indicatorStatus`, `indicatorAwaitingInput`, `isSessionUnread`.

- [ ] **Step 3: Implement the helpers**

Create `app/src/main/java/dev/agentic/domain/SessionIndicator.kt`:

```kotlin
package dev.agentic.domain

import dev.agentic.data.net.Session

/** The status string to feed StatusIndicator for a session row, folding in the workflow-running and
 *  error rules. A live background workflow shows as "running" (spinner); a usage/rate/claude error or
 *  outright failure shows the red error icon; a benign watchdog cap renders like done. */
fun indicatorStatus(session: Session): String = when {
    session.workflowRunning -> "running"
    hasError(session.status, session.errorKind) -> "failed"
    isBenignCap(session.errorKind) -> "done"
    else -> session.status
}

/** awaitingInput to feed StatusIndicator. When a workflow is running we want the spinner (RUNNING
 *  visual), not the idle/blank one, so awaitingInput is reported as false. */
fun indicatorAwaitingInput(session: Session): Boolean? =
    if (session.workflowRunning) false else session.awaitingInput

/** A successfully-finished session (DONE/IDLE visual) is "unread" if it completed after the user last
 *  read it. Read state is local; [lastReadAt] is the user's last open of this session (null = never),
 *  [baseline] is the first-launch time so completions from before the app was ever opened don't all
 *  show as unread. failed/killed keep their own icons and are never unread dots; a still-running
 *  workflow is not a completion, so it's never unread. */
fun isSessionUnread(session: Session, lastReadAt: Long?, baseline: Long): Boolean {
    val visual = statusVisual(indicatorStatus(session), indicatorAwaitingInput(session))
    if (visual != StatusVisual.DONE && visual != StatusVisual.IDLE) return false
    val completedAt = session.endedAt ?: return false
    return completedAt > (lastReadAt ?: baseline)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest --tests "dev.agentic.domain.SessionIndicatorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/agentic/domain/SessionIndicator.kt app/src/test/java/dev/agentic/domain/SessionIndicatorTest.kt
git commit -m "feat(domain): indicator status + unread predicate

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task B3: `ReadStateStore` (local read tracking) + DI wiring

**Files:**
- Create: `app/src/main/java/dev/agentic/data/ReadStateStore.kt` (interface + `ReadState` + `SharedPrefsReadStateStore`)
- Create: `app/src/test/java/dev/agentic/data/FakeReadStateStore.kt` (test fake)
- Create: `app/src/test/java/dev/agentic/data/ReadStateStoreTest.kt`
- Modify: `app/src/main/java/dev/agentic/di/AppContainer.kt`

**Interfaces:**
- Produces:
  - `data class ReadState(val baseline: Long, val lastReadAt: Map<String, Long> = emptyMap())`
  - `interface ReadStateStore { val state: StateFlow<ReadState>; fun markRead(id: String, at: Long) }`
  - `class SharedPrefsReadStateStore(context, now = System.currentTimeMillis()) : ReadStateStore`
  - `class FakeReadStateStore(baseline: Long = 0L) : ReadStateStore`
  - `AppContainer.readStateStore: ReadStateStore`

- [ ] **Step 1: Write the failing test + the fake**

Create `app/src/test/java/dev/agentic/data/FakeReadStateStore.kt`:

```kotlin
package dev.agentic.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory ReadStateStore for unit tests. No Android dependencies. */
class FakeReadStateStore(baseline: Long = 0L) : ReadStateStore {
    private val _state = MutableStateFlow(ReadState(baseline))
    override val state: StateFlow<ReadState> = _state.asStateFlow()
    override fun markRead(id: String, at: Long) {
        val cur = _state.value
        _state.value = cur.copy(lastReadAt = cur.lastReadAt + (id to maxOf(at, cur.lastReadAt[id] ?: 0L)))
    }
}
```

Create `app/src/test/java/dev/agentic/data/ReadStateStoreTest.kt`:

```kotlin
package dev.agentic.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadStateStoreTest {
    @Test fun `markRead records the latest timestamp per session`() {
        val store = FakeReadStateStore(baseline = 0L)
        store.markRead("a", 100)
        assertEquals(100L, store.state.value.lastReadAt["a"])
        store.markRead("a", 50)   // an older mark must not move it backwards
        assertEquals(100L, store.state.value.lastReadAt["a"])
        store.markRead("a", 200)
        assertEquals(200L, store.state.value.lastReadAt["a"])
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest --tests "dev.agentic.data.ReadStateStoreTest"`
Expected: FAIL — unresolved `ReadStateStore` / `ReadState` / `FakeReadStateStore`.

- [ ] **Step 3: Implement the interface + SharedPrefs store**

Create `app/src/main/java/dev/agentic/data/ReadStateStore.kt`:

```kotlin
package dev.agentic.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Snapshot of local read state: a [baseline] (first-launch time — completions before it don't count
 *  as unread) and per-session last-open timestamps (epoch ms). */
data class ReadState(val baseline: Long, val lastReadAt: Map<String, Long> = emptyMap())

/** Local (per-device) record of which finished sessions the user has read. Exposed as a StateFlow so
 *  the session list recomputes unread dots reactively. No backend sync. */
interface ReadStateStore {
    val state: StateFlow<ReadState>
    /** Record that the user opened [id] at [at] (epoch ms). Never moves a session's timestamp backward. */
    fun markRead(id: String, at: Long)
}

/** No-op [ReadStateStore] (baseline 0, nothing read). A null-object DEFAULT so view models and tests
 *  that don't supply one still compile; production wiring passes a real [SharedPrefsReadStateStore]. */
object EmptyReadStateStore : ReadStateStore {
    override val state: StateFlow<ReadState> = MutableStateFlow(ReadState(0L)).asStateFlow()
    override fun markRead(id: String, at: Long) {}
}

@Serializable
private data class ReadStatePersist(val baseline: Long, val lastReadAt: Map<String, Long> = emptyMap())

/** SharedPreferences-backed [ReadStateStore]. Stores the whole state as one JSON blob under "readstate"
 *  in the shared "agentic" prefs file (mirrors [SessionUiStore]). On first construction (no blob) the
 *  baseline is set to [now] and persisted, so the user isn't greeted by a wall of unread dots for
 *  sessions that finished before they ever opened the app. */
class SharedPrefsReadStateStore(
    context: Context,
    now: Long = System.currentTimeMillis(),
) : ReadStateStore {
    private val prefs = context.getSharedPreferences("agentic", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(loadOrSeed(now))
    override val state: StateFlow<ReadState> = _state.asStateFlow()

    /** Load the persisted state, or seed a fresh one (baseline = now) and persist it on first run.
     *  Called from the [_state] initializer; uses only [prefs]/[json], which are declared above it. */
    private fun loadOrSeed(now: Long): ReadState {
        val loaded = prefs.getString("readstate", null)?.let {
            try { json.decodeFromString<ReadStatePersist>(it) } catch (e: Exception) { null }
        }
        return if (loaded != null) ReadState(loaded.baseline, loaded.lastReadAt)
        else ReadState(now).also { persist(it) }
    }

    override fun markRead(id: String, at: Long) {
        val cur = _state.value
        val next = cur.copy(lastReadAt = cur.lastReadAt + (id to maxOf(at, cur.lastReadAt[id] ?: 0L)))
        _state.value = next
        persist(next)
    }

    private fun persist(s: ReadState) {
        prefs.edit().putString("readstate", json.encodeToString(ReadStatePersist(s.baseline, s.lastReadAt))).apply()
    }
}
```

- [ ] **Step 4: Wire it into the DI container**

In `app/src/main/java/dev/agentic/di/AppContainer.kt`, add the import and the field (next to `sessionUiStore`):

```kotlin
import dev.agentic.data.ReadStateStore
import dev.agentic.data.SharedPrefsReadStateStore
```

```kotlin
    val sessionUiStore: SessionUiStore = SessionUiStore(app)
    val readStateStore: ReadStateStore = SharedPrefsReadStateStore(app)
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest --tests "dev.agentic.data.ReadStateStoreTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/agentic/data/ReadStateStore.kt app/src/test/java/dev/agentic/data/FakeReadStateStore.kt app/src/test/java/dev/agentic/data/ReadStateStoreTest.kt app/src/main/java/dev/agentic/di/AppContainer.kt
git commit -m "feat(data): local ReadStateStore with first-launch baseline

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task B4: `StatusIndicator` — resolve the check into a persistent unread dot

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/components/StatusIndicator.kt`

**Interfaces:**
- Consumes: existing `Glyph.DOT` (already rendered for `PENDING`).
- Produces: `StatusIndicator(..., unread: Boolean = false)` — at rest, a DONE/IDLE session with `unread = true` shows the dot; the transient completion check now shrinks into that dot instead of into nothing.

This is presentation code with no unit-test harness; verify by clean compile + the added `@Preview`.

- [ ] **Step 1: Add the `unread` parameter**

In `app/src/main/java/dev/agentic/ui/components/StatusIndicator.kt`, add the parameter to the composable signature:

```kotlin
fun StatusIndicator(
    status: String,
    awaitingInput: Boolean? = null,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    accent: Color? = null,
    unread: Boolean = false,
) {
```

- [ ] **Step 2: Make `steadyGlyph` aware of unread**

Change the private `steadyGlyph()` extension to take `unread` and return `DOT` for the done/idle + unread case:

```kotlin
private fun StatusVisual.steadyGlyph(unread: Boolean = false): Glyph = when (this) {
    StatusVisual.RUNNING -> Glyph.SPINNER
    StatusVisual.PENDING -> Glyph.DOT
    StatusVisual.FAILED -> Glyph.FAILED
    StatusVisual.KILLED -> Glyph.KILLED
    StatusVisual.DONE, StatusVisual.IDLE -> if (unread) Glyph.DOT else Glyph.NONE
}
```

- [ ] **Step 3: Pass `unread` at the glyph call site**

Change the `glyph` assignment (currently `val glyph = if (checking) Glyph.CHECK else visual.steadyGlyph()`):

```kotlin
    val glyph = if (checking) Glyph.CHECK else visual.steadyGlyph(unread)
```

The existing `AnimatedContent` transition (pop-in check, shrink-out) now animates `CHECK → DOT` when `unread` is true (the check shrinks and the dot pops), and `CHECK → NONE` when read — no other change needed.

- [ ] **Step 4: Add a preview for visual verification**

Add at the bottom of the file (import `androidx.compose.ui.tooling.preview.Preview` and `androidx.compose.foundation.layout.Row`/`Arrangement`/`spacedBy` and `androidx.compose.ui.unit.dp` as needed):

```kotlin
@Preview
@Composable
private fun StatusIndicatorUnreadPreview() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusIndicator(status = "done", unread = true)    // unread dot
        StatusIndicator(status = "done", unread = false)   // blank (read)
        StatusIndicator(status = "running")                // spinner
        StatusIndicator(status = "failed")                 // error icon
    }
}
```

- [ ] **Step 5: Compile**

Run: `~/.local/share/gradle-8.10.2/bin/gradle :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (no unresolved references).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/agentic/ui/components/StatusIndicator.kt
git commit -m "feat(ui): StatusIndicator resolves check into unread dot

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task B5: `HomeViewModel` — compute `unreadIds`

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/home/HomeViewModel.kt` (constructor, `HomeUiState`, `combine`)
- Modify: `app/src/main/java/dev/agentic/ui/home/HomeScreen.kt` (the `HomeViewModel(...)` factory call)
- Modify: `app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt` (the `HomeViewModel(...)` factory call)
- Test: `app/src/test/java/dev/agentic/ui/home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `ReadStateStore` (B3), `isSessionUnread` (B2), `Session.endedAt`/`workflowRunning` (B1).
- Produces: `HomeUiState.unreadIds: Set<String>`; `HomeViewModel(sessionsRepo, readStateStore)`.

- [ ] **Step 1: Write the failing test**

In `app/src/test/java/dev/agentic/ui/home/HomeViewModelTest.kt`, add these imports if absent:

```kotlin
import dev.agentic.data.FakeReadStateStore
import dev.agentic.data.net.Session
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
```

Add the test inside the class:

```kotlin
    @Test fun `unreadIds holds finished sessions not yet read; markRead clears them`() = runTest(dispatcher) {
        val read = FakeReadStateStore(baseline = 0L)
        api.sessionsResult = listOf(
            Session(id = "s1", status = "done", endedAt = 1000),
            Session(id = "s2", status = "done", endedAt = 1000),
        )
        val vm = HomeViewModel(sessionsRepo(), read)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceTimeBy(2_500)
        runCurrent()
        assertTrue("s1" in vm.uiState.value.unreadIds)
        assertTrue("s2" in vm.uiState.value.unreadIds)

        read.markRead("s1", 2000)   // reading s1 (after its endedAt) clears its dot
        advanceTimeBy(1)
        runCurrent()
        assertFalse("s1" in vm.uiState.value.unreadIds)
        assertTrue("s2" in vm.uiState.value.unreadIds)
    }
```

No edits to the other ~16 existing `HomeViewModel(sessionsRepo())` constructions in this file are needed: the new `readStateStore` parameter defaults to `EmptyReadStateStore` (Step 3b), so they keep compiling. Only this new test passes an explicit `FakeReadStateStore` to drive unread state. (After Step 3, run the file's full suite to confirm the pre-existing tests still pass.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest --tests "dev.agentic.ui.home.HomeViewModelTest"`
Expected: FAIL — `HomeViewModel` constructor takes one arg / `unreadIds` unresolved.

- [ ] **Step 3: Add the constructor param and `unreadIds`, compute in `combine`**

In `app/src/main/java/dev/agentic/ui/home/HomeViewModel.kt`:

(a) Add imports:

```kotlin
import dev.agentic.data.EmptyReadStateStore
import dev.agentic.data.ReadStateStore
import dev.agentic.domain.isSessionUnread
```

(b) Constructor — `readStateStore` defaults to the null-object `EmptyReadStateStore` so the existing tests' `HomeViewModel(sessionsRepo())` calls keep compiling; production passes the real store (Step 4):

```kotlin
class HomeViewModel(
    private val sessionsRepo: SessionsRepository,
    private val readStateStore: ReadStateStore = EmptyReadStateStore,
) : ViewModel() {
```

(c) Add the field to `HomeUiState` (after `selectedIds`):

```kotlin
    val selectedIds: Set<String> = emptySet(),
    /** Ids of finished sessions the user hasn't read since they completed — render a list-row dot. */
    val unreadIds: Set<String> = emptySet(),
```

(d) Replace the `combine(...)` that builds `uiState`. Keep the existing 4-flow `combine` to assemble the base state, then fold in `readStateStore.state` with a 2-arg `.combine(...)` to compute `unreadIds`. (Nesting a 4-arg and a 2-arg combine avoids relying on a 5-arg overload and cleanly separates base-state assembly from the unread pass; both arities are standard.)

```kotlin
    val uiState: StateFlow<HomeUiState> =
        combine(sessionsState, usageFlow, _refreshing, _selectedIds) { ss, usage, refreshing, selected ->
            val present = ss.sessions.mapTo(HashSet()) { it.id }
            HomeUiState(
                sessions = ss.sessions,
                usage = usage,
                loading = false,
                serverUnreachable = ss.serverUnreachable,
                refreshing = refreshing,
                selectedIds = if (selected.isEmpty()) selected else selected.intersect(present),
            )
        }.combine(readStateStore.state) { base, read ->
            val unread = base.sessions.asSequence()
                .filter { isSessionUnread(it, read.lastReadAt[it.id], read.baseline) }
                .map { it.id }
                .toHashSet()
            base.copy(unreadIds = unread)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), HomeUiState())
```

- [ ] **Step 4: Update the two production call sites**

In `app/src/main/java/dev/agentic/ui/home/HomeScreen.kt` (the `resolvedVm` factory):

```kotlin
    val resolvedVm: HomeViewModel = vm ?: viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(container.sessionsRepo, container.readStateStore) }
        },
    )
```

In `app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt` (the `homeVm` factory):

```kotlin
    val homeVm: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(container.sessionsRepo, container.readStateStore) }
        },
    )
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest --tests "dev.agentic.ui.home.HomeViewModelTest"`
Expected: PASS (the new test and all pre-existing ones).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/agentic/ui/home/HomeViewModel.kt app/src/main/java/dev/agentic/ui/home/HomeScreen.kt app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt app/src/test/java/dev/agentic/ui/home/HomeViewModelTest.kt
git commit -m "feat(home): compute unreadIds from local read state

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task B6: `SessionRow` — show the unread dot and workflow-aware status

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/home/HomeScreen.kt` (`SessionRow` signature + its `StatusIndicator` call; both `SessionRow(...)` call sites in `SessionListPane`)

**Interfaces:**
- Consumes: `indicatorStatus`/`indicatorAwaitingInput` (B2), `HomeUiState.unreadIds` (B5), `StatusIndicator(unread=...)` (B4).

Presentation code; verify by clean compile.

- [ ] **Step 1: Add `unread` to `SessionRow` and use the domain helpers**

In `app/src/main/java/dev/agentic/ui/home/HomeScreen.kt`:

(a) Add the import:

```kotlin
import dev.agentic.domain.indicatorStatus
import dev.agentic.domain.indicatorAwaitingInput
```

(b) Add `unread` to the `SessionRow` signature (after `checked`):

```kotlin
private fun SessionRow(
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

(c) Replace the inline `StatusIndicator(status = when { ... }, awaitingInput = session.awaitingInput, size = 16.dp)` block with the domain helpers and pass `unread` (suppressed for the currently-open row in the wide layout):

```kotlin
                StatusIndicator(
                    status = indicatorStatus(session),
                    awaitingInput = indicatorAwaitingInput(session),
                    size = 16.dp,
                    unread = unread && !openHighlight,
                )
```

(If `hasError`/`isBenignCap` imports in this file are now unused, remove them to keep the build warning-free.)

- [ ] **Step 2: Pass `unread` at both `SessionRow` call sites**

In `SessionListPane`, add `unread = session.id in state.unreadIds,` to BOTH `SessionRow(...)` calls (the selection-mode call and the swipe-mode call). For example the swipe-mode call becomes:

```kotlin
                            SessionRow(
                                session = session,
                                now = now,
                                openHighlight = session.id == selectedId,
                                inSelectionMode = false,
                                checked = false,
                                unread = session.id in state.unreadIds,
                                onClick = { onOpen(session.id) },
                                onLongClick = { onLongPress(session.id) },
                                modifier = Modifier.fillMaxWidth(),
                            )
```

and likewise add `unread = session.id in state.unreadIds,` to the selection-mode `SessionRow(...)` call.

- [ ] **Step 3: Compile**

Run: `~/.local/share/gradle-8.10.2/bin/gradle :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/agentic/ui/home/HomeScreen.kt
git commit -m "feat(home): render unread dot + workflow-aware status in SessionRow

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task B7: Mark sessions read on open

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/session/SessionViewModel.kt` (both constructors + `markRead`)
- Modify: `app/src/main/java/dev/agentic/ui/session/SessionScreen.kt` (factory + `DisposableEffect`)
- Modify: `app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt` (right-pane factory + `LaunchedEffect`)
- Test: `app/src/test/java/dev/agentic/ui/session/SessionViewModelTest.kt`

**Interfaces:**
- Consumes: `ReadStateStore` (B3); `AppContainer.readStateStore` (B3).
- Produces: `SessionViewModel.markRead(at: Long = System.currentTimeMillis())`; both constructors now take a `ReadStateStore`.

- [ ] **Step 1: Write the failing test**

In `app/src/test/java/dev/agentic/ui/session/SessionViewModelTest.kt`, add imports if absent:

```kotlin
import dev.agentic.data.FakeReadStateStore
import dev.agentic.data.ReadStateStore
import org.junit.Assert.assertEquals
```

Update the `vm(...)` helper to thread a read store, and add a test:

```kotlin
    private fun vm(
        handle: SavedStateHandle = SavedStateHandle(mapOf("id" to "s1")),
        read: ReadStateStore = FakeReadStateStore(),
    ) = SessionViewModel(sessionsRepo(), workflowsRepo(), FilesRepository(api), read, handle)

    @Test fun `markRead records this session id in the read store`() = runTest(dispatcher) {
        val read = FakeReadStateStore(baseline = 0L)
        val svm = vm(read = read)
        svm.markRead(1234)
        assertEquals(1234L, read.state.value.lastReadAt["s1"])
    }
```

(Route any other `SessionViewModel(...)` construction in this file through the updated `vm(...)` helper.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest --tests "dev.agentic.ui.session.SessionViewModelTest"`
Expected: FAIL — `SessionViewModel` secondary constructor has no `ReadStateStore` param / no `markRead`.

- [ ] **Step 3: Add the dependency + `markRead` to `SessionViewModel`**

In `app/src/main/java/dev/agentic/ui/session/SessionViewModel.kt`:

(a) Add the import:

```kotlin
import dev.agentic.data.ReadStateStore
```

(b) Primary constructor — add `readStateStore` after `filesRepo`:

```kotlin
class SessionViewModel(
    private val sessionsRepo: SessionsRepository,
    private val workflowsRepo: WorkflowsRepository,
    private val filesRepo: FilesRepository,
    private val readStateStore: ReadStateStore,
    private val id: String,
    private val initialPrompt: String? = null,
) : ViewModel() {
```

(c) Secondary constructor — add `readStateStore` and forward it:

```kotlin
    constructor(
        sessionsRepo: SessionsRepository,
        workflowsRepo: WorkflowsRepository,
        filesRepo: FilesRepository,
        readStateStore: ReadStateStore,
        handle: SavedStateHandle,
    ) : this(
        sessionsRepo, workflowsRepo, filesRepo, readStateStore,
        requireNotNull(handle.get<String>("id")) { "SessionViewModel requires an 'id' arg" },
        handle.get<String>("initialPrompt"),
    )
```

(d) Add the method (anywhere among the public methods):

```kotlin
    /** Record that the user is viewing this session now, clearing its unread dot in the list. Called on
     *  open and on leave so a completion that lands while the screen is open stays "read". */
    fun markRead(at: Long = System.currentTimeMillis()) = readStateStore.markRead(id, at)
```

- [ ] **Step 4: Update `SessionScreen` (phone) — factory + DisposableEffect**

In `app/src/main/java/dev/agentic/ui/session/SessionScreen.kt`:

(a) Add the import:

```kotlin
import androidx.compose.runtime.DisposableEffect
```

(b) Pass `container.readStateStore` into the factory:

```kotlin
    val realVm: SessionViewModel = vm ?: viewModel(
        factory = viewModelFactory {
            initializer {
                SessionViewModel(container.sessionsRepo, container.workflowsRepo, container.filesRepo, container.readStateStore, createSavedStateHandle())
            }
        },
    )
```

(c) Right after `val s by realVm.uiState.collectAsStateWithLifecycle()`, mark read on enter and on leave:

```kotlin
    // Reading this session clears its unread dot; the onDispose mark covers a completion that lands
    // while the screen is open (so it stays read after you leave).
    DisposableEffect(realVm) {
        realVm.markRead()
        onDispose { realVm.markRead() }
    }
```

- [ ] **Step 5: Update `AdaptiveHome` (wide layout) — factory + LaunchedEffect**

In `app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt`:

(a) Pass `container.readStateStore` into the per-session `SessionViewModel` factory (the explicit-id constructor):

```kotlin
                    val sVm: SessionViewModel = viewModel(
                        key = "s:$sid",
                        factory = viewModelFactory {
                            initializer {
                                SessionViewModel(
                                    container.sessionsRepo,
                                    container.workflowsRepo,
                                    container.filesRepo,
                                    container.readStateStore,
                                    sid,
                                )
                            }
                        },
                    )
```

(b) Right after `val s by sVm.uiState.collectAsStateWithLifecycle()`, mark read when this session is shown and again whenever it finishes while shown:

```kotlin
                    // Wide layout shows the selected session — mark it read (and re-mark on each new
                    // completion) so its dot stays cleared while it's the open pane.
                    LaunchedEffect(sid, s.session?.endedAt) { sVm.markRead() }
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest --tests "dev.agentic.ui.session.SessionViewModelTest"`
Expected: PASS.

- [ ] **Step 7: Full unit-test run + compile**

Run: `~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest` then `~/.local/share/gradle-8.10.2/bin/gradle :app:compileDebugKotlin`
Expected: all unit tests PASS; compile SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/agentic/ui/session/SessionViewModel.kt app/src/main/java/dev/agentic/ui/session/SessionScreen.kt app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt app/src/test/java/dev/agentic/ui/session/SessionViewModelTest.kt
git commit -m "feat(session): mark sessions read on open (clears unread dot)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Done criteria (Android)

- A session with a live background workflow shows the running spinner in the list (`workflowRunning` ⇒ running).
- A successfully-finished session the user hasn't read since completion shows a persistent dot; the completion check animates into that dot; reading the session (opening it, or having it open when it finishes) clears the dot; a later completion re-shows it.
- The list reflects the backend's last-message order (no client re-sort).
- `:app:testDebugUnitTest` is green; `:app:compileDebugKotlin` succeeds.
- APK is built in the main checkout (`gradle assembleRelease`) and delivered via `outbox/` per the repo CLAUDE.md — never built in the worktree.

## Known limitation (deliberate simplification vs. spec)

The spec sketched a completion timestamp of `max(endedAt, observedCompletedAt)`, where `observedCompletedAt` is a client-recorded time of the live running→done transition. This plan uses **`endedAt` only** (the turn-end time from the backend), because reliable transition detection in the ViewModel needs prev-state tracking (a `scan` or a repo-level transitions flow) that the current `combine`-based pipeline doesn't have, and it adds real complexity for a narrow edge case.

Consequence: the primary flows all work (a session that finishes — including when a background workflow ends and the effective status flips to done — shows the unread dot; reading clears it; a *new turn* you send re-bumps `endedAt` so it re-unreads). The only uncovered edge is: you read a session after its turn ended, then a background workflow finishes later producing new output without a new user turn — that late workflow output won't re-mark the session unread (because `endedAt` didn't move). This is acceptable for the first version; if it matters, add observed-completion tracking as a follow-up.

## Manual verification (after building the APK)

1. Start a session that launches a workflow; after the turn finishes, confirm the list row keeps spinning until the workflow ends, then shows a check that settles into a dot.
2. Open the session → dot clears. Send another message → on completion the dot returns.
3. Confirm the list re-orders so the session you most recently messaged is on top.
