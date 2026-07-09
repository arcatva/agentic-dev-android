package dev.agentic.ui.home


import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.net.CreateGroupReq
import dev.agentic.data.net.Group
import dev.agentic.data.net.PatchSessionReq
import dev.agentic.data.net.SearchHit
import dev.agentic.data.net.SearchResponse
import dev.agentic.data.net.Session
import dev.agentic.data.net.UpdateGroupReq
import dev.agentic.data.net.Usage
import dev.agentic.data.net.UsageWindow
import dev.agentic.data.repo.SessionsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [HomeViewModel]. Dispatchers.setMain(testDispatcher) makes viewModelScope run on the
 * test scheduler. uiState is a WhileSubscribed StateFlow: to drive the upstream polls we subscribe
 * in [backgroundScope] (auto-cancelled, never awaited) and advance time by a bounded amount —
 * never advanceUntilIdle while subscribed, which would spin forever on the infinite poll loops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var api: FakeAgenticApi
    private lateinit var repoScope: CoroutineScope

    @Before fun setUp() {
        dispatcher = StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        api = FakeAgenticApi()
        repoScope = CoroutineScope(dispatcher)
    }

    @After fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
        repoScope.cancel()
    }

    private fun sessionsRepo() = SessionsRepository(api, repoScope)

    @Test fun `initial state has empty sessions list and loading=true`() = runTest(dispatcher) {
        val vm = HomeViewModel(sessionsRepo())
        val s = vm.uiState.value           // no subscriber yet → the WhileSubscribed initial value
        assertTrue(s.sessions.isEmpty())
        assertTrue("loading should be true initially", s.loading)
    }

    @Test fun `sessionsStream emission populates sessions and clears loading`() = runTest(dispatcher) {
        val sessions = listOf(
            Session(id = "s1", prompt = "first"),
            Session(id = "s2", prompt = "second"),
        )
        api.sessionsResult = sessions
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }  // start the WhileSubscribed upstream
        advanceTimeBy(100L); runCurrent()
        val s = vm.uiState.value
        assertEquals(sessions, s.sessions)
        assertFalse("loading should be false after first emission", s.loading)
        sub.cancel()
    }

    @Test fun `delete calls api delete with the given id`() = runTest(dispatcher) {
        val vm = HomeViewModel(sessionsRepo())
        vm.delete("s42")
        advanceUntilIdle()                 // safe: no uiState subscriber, so no infinite upstream running
        assertTrue("api.delete should have been called with s42", api.deleteCalls.contains("s42"))
    }

    @Test fun `setPendingPrompt writes to sessionsRepo pendingPrompts`() = runTest(dispatcher) {
        val repo = sessionsRepo()
        val vm = HomeViewModel(repo)
        vm.setPendingPrompt("sid1", "hello world")
        assertEquals("hello world", repo.pendingPrompts["sid1"])
    }

    // ── pull-to-refresh ───────────────────────────────────────────────────────────

    @Test fun `refresh fetches sessions and usage on demand before the next poll tick`() = runTest(dispatcher) {
        api.sessionsResult = emptyList()
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()
        assertTrue("starts with an empty list", vm.uiState.value.sessions.isEmpty())

        // New data appears server-side; a pull-to-refresh must surface it WITHOUT waiting for the 2s poll.
        val fresh = listOf(Session(id = "s9", prompt = "fresh"))
        api.sessionsResult = fresh
        api.usageResult = Usage(five_hour = UsageWindow(utilization = 42.0))
        vm.refresh()
        runCurrent()                       // run the refresh coroutine; do NOT advance the 2s poll

        val s = vm.uiState.value
        assertEquals("refresh shows the fresh list immediately", fresh, s.sessions)
        assertEquals("refresh updates the usage meters", 42, s.usage?.five_hour?.utilization?.toInt())
        assertFalse("refreshing flag is cleared once the fetch completes", s.refreshing)
        sub.cancel()
    }

    @Test fun `refresh sets refreshing true while in flight and clears it when done`() = runTest(dispatcher) {
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()
        assertFalse("not refreshing at rest", vm.uiState.value.refreshing)

        // Gate the usage fetch open so the refresh coroutine stays suspended mid-flight.
        val gate = CompletableDeferred<Unit>()
        api.usageGate = gate
        vm.refresh()
        runCurrent()
        assertTrue("refreshing is true while the fetch is in flight", vm.uiState.value.refreshing)

        gate.complete(Unit)
        runCurrent()
        assertFalse("refreshing is cleared after the fetch completes", vm.uiState.value.refreshing)
        sub.cancel()
    }

    // ── background→foreground: a stale pull-to-refresh snapshot must NOT flash on resume ──────────
    //
    // Repro of the reported bug: after a pull-to-refresh captured an early usage %, backgrounding the
    // app past the WhileSubscribed(5s) timeout tears the uiState upstream down; returning to the
    // foreground restarts it, and the sticky manual* value re-emits its OLD snapshot before the live
    // poll has answered — so the usage meter briefly flashes the stale number, then snaps back. We gate
    // the live usage poll open on resume so the ONLY thing that could move the meter is a stale replay.

    @Test fun `resume after a refresh does not flash the stale usage percentage`() = runTest(dispatcher) {
        api.sessionsResult = listOf(Session(id = "s1"))
        api.usageResult = Usage(five_hour = UsageWindow(utilization = 5.0))   // early in the window
        val vm = HomeViewModel(sessionsRepo())

        // foreground #1: subscribe, the poll shows 5%, the user pull-to-refreshes (captures 5%).
        val sub1 = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()
        vm.refresh(); runCurrent()
        assertEquals("refresh captured the early 5%", 5, vm.uiState.value.usage?.five_hour?.utilization?.toInt())

        // usage climbs server-side; the live 60s poll catches up to 52% before the app is backgrounded.
        api.usageResult = Usage(five_hour = UsageWindow(utilization = 52.0))
        advanceTimeBy(61_000L); runCurrent()
        assertEquals("live poll reached 52% before backgrounding", 52, vm.uiState.value.usage?.five_hour?.utilization?.toInt())

        // background: gate the usage poll shut, drop the subscriber, let WhileSubscribed(5s) tear down.
        api.usageGate = CompletableDeferred()
        sub1.cancel(); runCurrent()
        advanceTimeBy(6_000L); runCurrent()

        // foreground #2: re-subscribe. The fresh usage poll is gated (hung), so nothing legitimate can
        // change the meter — it must stay at the last-good 52%, not flash the stale 5% from the old refresh.
        val sub2 = backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        assertEquals(
            "resume must keep last-good 52%, not flash the stale 5% snapshot",
            52, vm.uiState.value.usage?.five_hour?.utilization?.toInt(),
        )
        sub2.cancel()
        api.usageGate?.complete(Unit)
    }

    @Test fun `resume after a refresh does not flash the stale session order`() = runTest(dispatcher) {
        val orderA = listOf(Session(id = "a"), Session(id = "b"))
        val orderB = listOf(Session(id = "b"), Session(id = "a"))   // server reordered by activity
        api.sessionsResult = orderA
        val vm = HomeViewModel(sessionsRepo())

        // foreground #1: subscribe, poll shows order A, pull-to-refresh captures order A.
        val sub1 = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()
        vm.refresh(); runCurrent()
        assertEquals("refresh captured order A", orderA, vm.uiState.value.sessions)

        // server reorders; the live 2s poll catches up to order B before backgrounding.
        api.sessionsResult = orderB
        advanceTimeBy(2_500L); runCurrent()
        assertEquals("live poll reached order B before backgrounding", orderB, vm.uiState.value.sessions)

        // background: gate the session poll shut, drop the subscriber, let WhileSubscribed(5s) tear down.
        api.sessionsGate = CompletableDeferred()
        sub1.cancel(); runCurrent()
        advanceTimeBy(6_000L); runCurrent()

        // foreground #2: re-subscribe. The fresh session poll is gated, so the list must stay order B,
        // not flash the stale order A snapshot from the old refresh.
        val sub2 = backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        assertEquals(
            "resume must keep order B, not flash the stale order A snapshot",
            orderB, vm.uiState.value.sessions,
        )
        sub2.cancel()
        api.sessionsGate?.complete(Unit)
    }

    // ── PR-9: server-unreachable banner ──────────────────────────────────────────

    @Test fun `PR-9 first load failure sets serverUnreachable flag`() = runTest(dispatcher) {
        // Script sessions() to fail on the first tick.
        api.sessionsScript = mutableListOf(
            Result.failure(java.io.IOException("connection refused")),
        )
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        val s = vm.uiState.value
        assertTrue("serverUnreachable should be true after first-load failure", s.serverUnreachable)
        assertFalse("loading should be cleared even on first-load failure", s.loading)
        assertTrue("sessions should be empty on first-load failure", s.sessions.isEmpty())
        sub.cancel()
    }

    @Test fun `PR-9 server unreachable clears when a later tick succeeds`() = runTest(dispatcher) {
        val sessions = listOf(Session(id = "s1", prompt = "hi"))
        // First tick fails, second tick succeeds.
        api.sessionsScript = mutableListOf(
            Result.failure(java.io.IOException("down")),
            Result.success(sessions),
        )
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        // First tick.
        advanceTimeBy(100L); runCurrent()
        assertTrue("serverUnreachable after first failure", vm.uiState.value.serverUnreachable)

        // Advance past the 2s poll interval so the second tick fires.
        advanceTimeBy(2_500L); runCurrent()
        val s = vm.uiState.value
        assertFalse("serverUnreachable cleared after successful tick", s.serverUnreachable)
        assertEquals(sessions, s.sessions)
        sub.cancel()
    }

    // ── multi-select delete ──────────────────────────────────────────────────────

    @Test fun `toggleSelection adds an id then toggling again deselects it`() = runTest(dispatcher) {
        api.sessionsResult = listOf(Session(id = "s1"), Session(id = "s2"))
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()
        assertTrue("no selection at rest", vm.uiState.value.selectedIds.isEmpty())

        vm.toggleSelection("s1"); runCurrent()
        assertEquals(setOf("s1"), vm.uiState.value.selectedIds)

        vm.toggleSelection("s1"); runCurrent()
        assertTrue("toggling the same id again deselects it", vm.uiState.value.selectedIds.isEmpty())
        sub.cancel()
    }

    @Test fun `selectAll selects every currently-listed session id`() = runTest(dispatcher) {
        api.sessionsResult = listOf(Session(id = "a"), Session(id = "b"), Session(id = "c"))
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        vm.selectAll(); runCurrent()
        assertEquals(setOf("a", "b", "c"), vm.uiState.value.selectedIds)
        sub.cancel()
    }

    @Test fun `clearSelection empties the selection`() = runTest(dispatcher) {
        api.sessionsResult = listOf(Session(id = "s1"))
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        vm.toggleSelection("s1"); runCurrent()
        assertEquals(setOf("s1"), vm.uiState.value.selectedIds)
        vm.clearSelection(); runCurrent()
        assertTrue("clearSelection empties the set", vm.uiState.value.selectedIds.isEmpty())
        sub.cancel()
    }

    @Test fun `deleteSelected deletes every selected id and clears the selection`() = runTest(dispatcher) {
        api.sessionsResult = listOf(Session(id = "a"), Session(id = "b"), Session(id = "c"))
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        vm.toggleSelection("a"); vm.toggleSelection("c"); runCurrent()
        assertEquals(setOf("a", "c"), vm.uiState.value.selectedIds)

        vm.deleteSelected(); runCurrent()
        assertEquals("both selected ids are deleted", setOf("a", "c"), api.deleteCalls.toSet())
        assertTrue("selection is cleared after a batch delete", vm.uiState.value.selectedIds.isEmpty())
        sub.cancel()
    }

    @Test fun `deleteSelected only deletes ids still present after a vanished id is pruned`() = runTest(dispatcher) {
        // Guards the source-of-truth split: the displayed (pruned) selection and the set actually
        // deleted must agree. Select a+b, let a vanish on the next poll, then delete — only b deletes.
        api.sessionsScript = mutableListOf(
            Result.success(listOf(Session(id = "a"), Session(id = "b"), Session(id = "c"))),
            Result.success(listOf(Session(id = "b"), Session(id = "c"))),   // "a" gone
        )
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()
        vm.toggleSelection("a"); vm.toggleSelection("b"); runCurrent()
        assertEquals(setOf("a", "b"), vm.uiState.value.selectedIds)

        advanceTimeBy(2_500L); runCurrent()   // poll drops "a"
        assertEquals("vanished id pruned from the shown selection", setOf("b"), vm.uiState.value.selectedIds)

        vm.deleteSelected(); runCurrent()
        assertEquals("deletes only the id still present, not the pruned ghost", setOf("b"), api.deleteCalls.toSet())
        assertTrue("selection cleared after delete", vm.uiState.value.selectedIds.isEmpty())
        sub.cancel()
    }

    @Test fun `deleteSelected with no selection is a no-op`() = runTest(dispatcher) {
        api.sessionsResult = listOf(Session(id = "s1"))
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        vm.deleteSelected(); runCurrent()
        assertTrue("nothing is deleted when nothing is selected", api.deleteCalls.isEmpty())
        assertTrue("selection stays empty", vm.uiState.value.selectedIds.isEmpty())
        sub.cancel()
    }

    // ── multi-select fork ────────────────────────────────────────────────────────

    @Test fun `forkSelected forks every selected id and clears the selection`() = runTest(dispatcher) {
        api.sessionsResult = listOf(Session(id = "a"), Session(id = "b"), Session(id = "c"))
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        vm.toggleSelection("a"); vm.toggleSelection("c"); runCurrent()
        assertEquals(setOf("a", "c"), vm.uiState.value.selectedIds)

        vm.forkSelected(); runCurrent()
        assertEquals("both selected ids are forked", setOf("a", "c"), api.forkCalls.toSet())
        assertTrue("selection is cleared after a batch fork", vm.uiState.value.selectedIds.isEmpty())
        sub.cancel()
    }

    @Test fun `forkSelected with no selection is a no-op`() = runTest(dispatcher) {
        api.sessionsResult = listOf(Session(id = "s1"))
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        vm.forkSelected(); runCurrent()
        assertTrue("nothing is forked when nothing is selected", api.forkCalls.isEmpty())
        assertTrue("selection stays empty", vm.uiState.value.selectedIds.isEmpty())
        sub.cancel()
    }

    @Test fun `selection prunes ids that vanish from the session list`() = runTest(dispatcher) {
        // Select an id, then the server stops listing it — the stale id must drop out of selectedIds
        // so the contextual bar count never counts sessions that are no longer shown.
        api.sessionsScript = mutableListOf(
            Result.success(listOf(Session(id = "s1"), Session(id = "s2"))),
            Result.success(listOf(Session(id = "s2"))),
        )
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()
        vm.toggleSelection("s1"); runCurrent()
        assertEquals(setOf("s1"), vm.uiState.value.selectedIds)

        advanceTimeBy(2_500L); runCurrent()   // next poll tick drops s1 from the list
        assertTrue("a selected id that vanished is pruned", vm.uiState.value.selectedIds.isEmpty())
        sub.cancel()
    }

    @Test fun `PR-9 later blip after success does NOT set serverUnreachable`() = runTest(dispatcher) {
        val sessions = listOf(Session(id = "s1", prompt = "hi"))
        // First tick succeeds, second tick fails (transient blip).
        api.sessionsScript = mutableListOf(
            Result.success(sessions),
            Result.failure(java.io.IOException("blip")),
        )
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        // First tick.
        advanceTimeBy(100L); runCurrent()
        assertFalse("serverUnreachable should be false after first success", vm.uiState.value.serverUnreachable)

        // Advance past the 2s poll interval so the second (failing) tick fires.
        advanceTimeBy(2_500L); runCurrent()
        val s = vm.uiState.value
        assertFalse("later blip must NOT set serverUnreachable", s.serverUnreachable)
        // sessions should remain from last good tick (last-good behaviour preserved).
        assertEquals(sessions, s.sessions)
        sub.cancel()
    }

    @Test fun `unreadIds holds sessions with unreadEventId greater than acked, ackEvent clears`() = runTest(dispatcher) {
        api.sessionsResult = listOf(
            Session(id = "s1", status = "done", unreadEventId = 5),
            Session(id = "s2", status = "done", unreadEventId = 5),
        )
        val vm = HomeViewModel(sessionsRepo())
        backgroundScope.launch { vm.uiState.collect {} }
        advanceTimeBy(2_500)
        runCurrent()
        assertTrue("s1" in vm.uiState.value.unreadIds)
        assertTrue("s2" in vm.uiState.value.unreadIds)

        // Acknowledge s1: update its ackedEventId server-side → next poll clears the dot
        api.sessionsResult = listOf(
            Session(id = "s1", status = "done", unreadEventId = 5, ackedEventId = 5),
            Session(id = "s2", status = "done", unreadEventId = 5),
        )
        advanceTimeBy(2_500)  // wait for the next poll tick to pick up the updated list
        runCurrent()
        assertFalse("s1" in vm.uiState.value.unreadIds)
        assertTrue("s2" in vm.uiState.value.unreadIds)
    }

    // ── Task 6: content search state on HomeViewModel ───────────────────────────

    @Test fun `setSearchQuery debounces and updates results`() = runTest(dispatcher) {
        val hit = SearchHit(Session(id = "s1", prompt = "p"), 1f, emptyList())
        api.searchHandler = { q -> if (q == "build") SearchResponse("build", listOf(hit)) else null }
        val vm = HomeViewModel(sessionsRepo())
        // Subscribe to uiState so the WhileSubscribed upstream (and any new search wiring) actually
        // runs. Per the file header comment, never advanceUntilIdle while subscribed.
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()

        vm.setSearchQuery("build")
        runCurrent()
        // Query is mirrored into uiState immediately (no debounce on the visible text).
        assertEquals("build", vm.uiState.value.searchQuery)

        // Past the 250ms debounce — the api call has had time to fire and the result to land.
        advanceTimeBy(300L); runCurrent()
        val s = vm.uiState.value
        assertEquals("visible query still mirrors the latest input", "build", s.searchQuery)
        assertEquals("exactly one hit for the 'build' query", 1, s.searchResults.size)
        assertEquals("hit is the one the fake returned", "s1", s.searchResults[0].session.id)
        assertFalse("searching is cleared once the response lands", s.searching)
        sub.cancel()
    }

    @Test fun `setSearchQuery blank clears results`() = runTest(dispatcher) {
        val hit = SearchHit(Session(id = "s1", prompt = "p"), 1f, emptyList())
        api.searchHandler = { q -> if (q.isNotEmpty()) SearchResponse(q, listOf(hit)) else null }
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()

        vm.setSearchQuery("hi")
        advanceTimeBy(300L); runCurrent()
        assertEquals("'hi' populated results", 1, vm.uiState.value.searchResults.size)

        vm.setSearchQuery("")
        runCurrent()
        // Blanking the query mirrors the new value into uiState immediately.
        assertEquals("", vm.uiState.value.searchQuery)
        assertTrue("blanking clears any prior results", vm.uiState.value.searchResults.isEmpty())
        assertFalse("searching is not in flight after blanking", vm.uiState.value.searching)
        sub.cancel()
    }

    // ── Session groups ──────────────────────────────────────────────────────────

    @Test fun `groups appear in uiState when populated`() = runTest(dispatcher) {
        val groups = listOf(
            Group(id = "g1", name = "Work"),
            Group(id = "g2", name = "Personal"),
        )
        api.groupsResult = groups
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()
        assertEquals(groups, vm.uiState.value.groups)
        sub.cancel()
    }

    @Test fun `createGroup calls repo and refreshes groups`() = runTest(dispatcher) {
        val created = Group(id = "g-new", name = "New")
        api.groupsScript.addAll(
            listOf(
                Result.success(emptyList()),
                Result.success(listOf(created)),
            ),
        )
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()
        assertTrue("groups empty before creation", vm.uiState.value.groups.isEmpty())

        vm.createGroup("New")
        advanceTimeBy(1_000L); runCurrent()
        assertEquals("createGroup called with the right name", "New", api.createGroupCalls.last().name)
        assertEquals("groups refreshed after creation", listOf(created), vm.uiState.value.groups)
        sub.cancel()
    }

    @Test fun `updateGroup calls repo`() = runTest(dispatcher) {
        api.groupsResult = listOf(Group(id = "g1", name = "Old"))
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        vm.updateGroup("g1", name = "Renamed")
        advanceTimeBy(1_000L); runCurrent()
        val call = api.updateGroupCalls.last()
        assertEquals("g1", call.first)
        assertEquals("Renamed", call.second.name)
        sub.cancel()
    }

    @Test fun `deleteGroup calls repo`() = runTest(dispatcher) {
        api.groupsResult = listOf(Group(id = "g1", name = "Work"))
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        vm.deleteGroup("g1")
        advanceTimeBy(1_000L); runCurrent()
        assertEquals("deleteGroup called with g1", "g1", api.deleteGroupCalls.last())
        sub.cancel()
    }

    @Test fun `moveSessionToGroup calls setSessionGroup with groupId`() = runTest(dispatcher) {
        // Seed a session so patchSession can resolve get(id).
        api.sessionDetails["s1"] = dev.agentic.data.net.SessionDetail(
            session = Session(id = "s1", prompt = "test"),
            log = emptyList(),
        )
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        vm.moveSessionToGroup("s1", "g1")
        advanceTimeBy(1_000L); runCurrent()
        assertEquals("one patchSession call", 1, api.patchSessionCalls.size)
        assertEquals("s1", api.patchSessionCalls.last().first)
        assertEquals("g1", api.patchSessionCalls.last().second.groupId)
        sub.cancel()
    }

    @Test fun `moveSessionToGroup null sends empty groupId`() = runTest(dispatcher) {
        api.sessionDetails["s1"] = dev.agentic.data.net.SessionDetail(
            session = Session(id = "s1", prompt = "test"),
            log = emptyList(),
        )
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        vm.moveSessionToGroup("s1", null)
        advanceTimeBy(1_000L); runCurrent()
        assertEquals("one patchSession call", 1, api.patchSessionCalls.size)
        assertEquals("s1", api.patchSessionCalls.last().first)
        // null → repo sends empty string via ?: ""
        assertEquals("", api.patchSessionCalls.last().second.groupId)
        sub.cancel()
    }

    @Test fun `selectGroupFilter sets selectedGroupFilter in uiState`() = runTest(dispatcher) {
        api.groupsResult = listOf(Group(id = "g1", name = "Work"))
        val vm = HomeViewModel(sessionsRepo())
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        vm.selectGroupFilter("g1"); runCurrent()
        assertEquals("g1", vm.uiState.value.selectedGroupFilter)

        vm.selectGroupFilter(null); runCurrent()
        assertEquals(null, vm.uiState.value.selectedGroupFilter)
        sub.cancel()
    }
}
