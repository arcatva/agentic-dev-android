package dev.agentic.ui.session

import androidx.lifecycle.SavedStateHandle
import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.net.SessionDetail
import dev.agentic.data.net.SessionEventsResponse
import dev.agentic.data.repo.FilesRepository
import dev.agentic.data.repo.SessionsRepository
import dev.agentic.data.repo.WorkflowsRepository
import dev.agentic.domain.PermNode
import dev.agentic.domain.PlanNode
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [SessionViewModel.respondPermission].
 *
 * Mirrors the [SessionViewModelTest.answerAsk] harness exactly:
 * - [FakeAgenticApi] + real [SessionsRepository]/[WorkflowsRepository] wired on a [StandardTestDispatcher]
 * - [kotlinx.coroutines.Dispatchers.setMain] so [viewModelScope] runs on the test scheduler
 * - uiState subscribed in [backgroundScope] so WhileSubscribed stateIn flows start
 * - [advanceTimeBy] + [runCurrent] to drive coroutines (never advanceUntilIdle while subscribed —
 *   the poll loops would spin forever)
 *
 * NOTE: This test is written at B5 but RUNS at B7, after B6 completes the Transcript.kt render
 * switch for PermNode/PlanNode. The main source set does not fully compile until B6, so test
 * execution is deferred to B7 as documented in the B5 brief.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelPermTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var api: FakeAgenticApi
    private lateinit var repoScope: CoroutineScope

    @Before fun setUp() {
        dispatcher = StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        api = FakeAgenticApi()
        // Keep the WS open so the reconnect loop doesn't spin forever in virtual time.
        api.streamHoldsOpen = true
        repoScope = CoroutineScope(dispatcher)
    }

    @After fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
        repoScope.cancel()
    }

    private fun sessionsRepo() = SessionsRepository(api, repoScope)
    private fun workflowsRepo() = WorkflowsRepository(api, repoScope)

    private fun vm(handle: SavedStateHandle = SavedStateHandle(mapOf("id" to "s1"))) =
        SessionViewModel(sessionsRepo(), workflowsRepo(), FilesRepository(api), handle, dispatcher)

    // A terminal session (claudeSessionId set) whose log contains an agentic_perm event.
    // The JSON format matches the reducer in Transcript.kt: type=agentic_perm, id, tool, input={}.
    private val permLog = listOf(
        """{"type":"agentic_prompt","text":"go","at":1}""",
        """{"type":"result","result":"ok"}""",
        """{"type":"agentic_perm","id":"perm-1","tool":"Bash","input":{"command":"rm -rf /tmp/x"}}""",
    )

    private fun terminalSession(claudeSessionId: String = "claude-1") =
        dev.agentic.data.net.Session(
            id = "s1", prompt = "go", status = "done", claudeSessionId = claudeSessionId
        )

    // ── eventsResponse helpers (migrated from the old api.sessionDetails/api.session surface) ────────
    // The repo's load/refetch/refresh paths consume structured events from api.sessionEvents; the OLD
    // wire JSONL `agentic_prompt`/`agentic_perm` types live in [permLog]/[planLog] above as historical
    // documentation but can no longer drive seedFromEvents. Each helper seeds the events list the
    // TranscriptReducer folds into the SAME nodes the old log produced (PermNode/PlanNode/AnswerNode).
    private fun ev(json: String): JsonElement = Json.parseToJsonElement(json)

    private val permEvents: List<JsonElement> = listOf(
        ev("""{"kind":"prompt","text":"go","at":1}"""),
        ev("""{"kind":"result","text":"ok"}"""),
        ev("""{"kind":"perm","id":"perm-1","tool":"Bash","input":{"command":"rm -rf /tmp/x"}}"""),
    )

    private fun permEventsResponse(session: dev.agentic.data.net.Session) = SessionEventsResponse(
        session = session,
        events = permEvents,
        latestEventId = permEvents.size.toLong(),
    )

    // ── 1. respondPermission optimistically marks the PermNode decided ──

    @Test fun `respondPermission optimistically marks the PermNode decided in uiState`() = runTest(dispatcher) {
        api.sessionEventsResult = permEventsResponse(terminalSession())
        val vm = vm()
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        // The PermNode is present and starts undecided.
        val perm = vm.uiState.value.nodes.filterIsInstance<PermNode>().single()
        assertEquals("perm-1", perm.id)
        assertFalse("PermNode starts undecided", perm.decided)

        // Respond with "allow" — the overlay must apply immediately (optimistic).
        vm.respondPermission("perm-1", "allow")
        runCurrent()

        val after = vm.uiState.value.nodes.filterIsInstance<PermNode>().single()
        assertTrue("PermNode is optimistically decided after respondPermission", after.decided)
        assertEquals("allow", after.decision)

        sub.cancel()
    }

    // ── 2. respondPermission records the call in the api (repo/api receives the POST) ──

    @Test fun `respondPermission posts to the session id, not the permission card id`() = runTest(dispatcher) {
        api.sessionEventsResult = permEventsResponse(terminalSession())
        val vm = vm()
        // No uiState subscriber needed for this event-only assertion. Bounded advanceTimeBy drives
        // the viewModelScope.launch → repo.respondPermission → api.respondPermission chain without
        // tripping the 2s sessionRefreshStream poll loop in virtual time.
        vm.respondPermission("perm-1", "deny", "security risk")
        advanceTimeBy(1_000L); runCurrent()

        assertTrue(
            "api.respondPermission must be called",
            api.permissionCalls.isNotEmpty(),
        )
        val call = api.permissionCalls.single()
        assertEquals("session id forwarded", "s1", call.first)
        assertEquals("decision forwarded", "deny", call.second)
        assertEquals("feedback forwarded", "security risk", call.third)
    }

    // ── 3. respondPermission with null feedback (plain allow) ──

    @Test fun `respondPermission with null feedback passes null to the api`() = runTest(dispatcher) {
        api.sessionEventsResult = permEventsResponse(terminalSession())
        val vm = vm()
        vm.respondPermission("perm-1", "allow")
        // Bounded advance for the same reason as test 2: drive the launched coroutine without
        // spinning forever in the 2s sessionRefreshStream poll.
        advanceTimeBy(1_000L); runCurrent()

        val call = api.permissionCalls.single()
        assertEquals("allow", call.second)
        assertEquals("feedback is null for a plain allow", null, call.third)
    }

    // A log entry that produces a PlanNode (agentic_perm with permKind=plan).
    private val planLog = listOf(
        """{"type":"agentic_prompt","text":"go","at":1}""",
        """{"type":"result","result":"ok"}""",
        """{"type":"agentic_perm","id":"plan-1","permKind":"plan","plan":"Step 1: do the thing"}""",
    )

    private val planEvents: List<JsonElement> = listOf(
        ev("""{"kind":"prompt","text":"go","at":1}"""),
        ev("""{"kind":"result","text":"ok"}"""),
        ev("""{"kind":"plan","id":"plan-1","plan":"Step 1: do the thing"}"""),
    )

    private fun planEventsResponse(session: dev.agentic.data.net.Session) = SessionEventsResponse(
        session = session,
        events = planEvents,
        latestEventId = planEvents.size.toLong(),
    )

    private val twoPermEvents: List<JsonElement> = permEvents + ev(
        """{"kind":"perm","id":"perm-2","tool":"Write","input":{}}"""
    )
    private fun twoPermEventsResponse(session: dev.agentic.data.net.Session) = SessionEventsResponse(
        session = session,
        events = twoPermEvents,
        latestEventId = twoPermEvents.size.toLong(),
    )

    // ── 4. overlay is keyed by id: a second unrelated PermNode is not affected ──

    @Test fun `respondPermission overlay only affects the targeted node id`() = runTest(dispatcher) {
        api.sessionEventsResult = twoPermEventsResponse(terminalSession())
        val vm = vm()
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        val permNodes = vm.uiState.value.nodes.filterIsInstance<PermNode>()
        assertEquals("two PermNodes present", 2, permNodes.size)

        vm.respondPermission("perm-1", "allow")
        runCurrent()

        val after = vm.uiState.value.nodes.filterIsInstance<PermNode>()
        val decided = after.single { it.decided }
        val undecided = after.single { !it.decided }
        assertEquals("only perm-1 is decided", "perm-1", decided.id)
        assertEquals("perm-2 is untouched", "perm-2", undecided.id)

        sub.cancel()
    }

    // ── 5. POST failure: overlay is rolled back and queueError is set ──

    @Test fun `respondPermission rolls back overlay and sets queueError when the POST fails`() = runTest(dispatcher) {
        api.sessionEventsResult = permEventsResponse(terminalSession())
        // Force the api to throw on respondPermission, mirroring how followUpException fails followUp.
        api.respondPermissionException = RuntimeException("network error")
        val vm = vm()
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        // Confirm the PermNode starts undecided.
        val perm = vm.uiState.value.nodes.filterIsInstance<PermNode>().single()
        assertFalse("PermNode starts undecided", perm.decided)

        // Call respondPermission — the optimistic overlay fires, then the POST fails.
        vm.respondPermission("perm-1", "allow")
        runCurrent()

        // The overlay must be rolled back: the PermNode is NOT decided.
        val after = vm.uiState.value.nodes.filterIsInstance<PermNode>().single()
        assertFalse("PermNode overlay is rolled back after POST failure", after.decided)
        // A queueError must be surfaced so the user knows to retry.
        assertEquals(
            "queueError is set after POST failure",
            "Couldn't send your response — try again.",
            vm.uiState.value.queueError,
        )

        sub.cancel()
    }

    // ── 6. PlanNode happy path: overlay marks the PlanNode decided ──

    @Test fun `respondPermission optimistically marks a PlanNode decided in uiState`() = runTest(dispatcher) {
        api.sessionEventsResult = planEventsResponse(terminalSession())
        val vm = vm()
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        // The PlanNode is present and starts undecided.
        val plan = vm.uiState.value.nodes.filterIsInstance<PlanNode>().single()
        assertEquals("plan-1", plan.id)
        assertFalse("PlanNode starts undecided", plan.decided)

        // Respond with "allow" — the overlay must apply immediately (optimistic).
        vm.respondPermission("plan-1", "allow")
        runCurrent()

        val after = vm.uiState.value.nodes.filterIsInstance<PlanNode>().single()
        assertTrue("PlanNode is optimistically decided after respondPermission", after.decided)
        assertEquals("allow", after.decision)

        sub.cancel()
    }
}
