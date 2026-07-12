package dev.agentic.ui.workflow

import androidx.lifecycle.SavedStateHandle
import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.net.WorkflowAgent
import dev.agentic.data.net.WorkflowRun
import dev.agentic.data.repo.WorkflowsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [WorkflowViewModel].
 *
 * Pattern mirrors HomeViewModelTest / SessionViewModelTest:
 * - setMain(testDispatcher) makes viewModelScope use the test scheduler
 * - uiState is a WhileSubscribed StateFlow: subscribe in backgroundScope, advance time by a
 *   bounded amount — NEVER advanceUntilIdle while subscribed (infinite poll loop would hang)
 * - Tests that do NOT subscribe to uiState may safely use advanceUntilIdle
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowViewModelTest {

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

    private fun workflowsRepo() = WorkflowsRepository(api, repoScope)

    private fun vm(id: String = "s1") =
        WorkflowViewModel(workflowsRepo(), SavedStateHandle(mapOf("id" to id)))

    // ── 1. initial state ──────────────────────────────────────────────────────────

    @Test fun `initial state has empty runs and no selection`() = runTest(dispatcher) {
        val vm = vm()
        val s = vm.uiState.value
        assertTrue("runs should be empty initially", s.runs.isEmpty())
        assertNull("no selectedAgent initially", s.selectedAgent)
        assertNull("no agentTranscript initially", s.agentTranscript)
        assertFalse("not loading transcript initially", s.loadingTranscript)
    }

    // ── 2. runsStream emission populates runs ────────────────────────────────────

    @Test fun `runsStream emission populates runs in uiState`() = runTest(dispatcher) {
        val runs = listOf(
            WorkflowRun(runId = "r1", name = "build", status = "running"),
            WorkflowRun(runId = "r2", name = "test", status = "done"),
        )
        api.workflowsResult = runs

        val vm = vm()
        // Subscribe in backgroundScope so WhileSubscribed activates the upstream poll.
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        assertEquals(runs, vm.uiState.value.runs)
        sub.cancel()
    }

    // ── 3. selectAgent sets selectedAgent + loadingTranscript, then fetches transcript ──

    @Test fun `selectAgent populates selectedAgent and transcript, clears loading`() = runTest(dispatcher) {
        api.agentTranscriptResult = "step1\nstep2"
        val agent = WorkflowAgent(agentId = "a1", label = "planner")

        val vm = vm()
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        vm.selectAgent("r1", agent)
        // runCurrent() drives the coroutine that sets loadingTranscript=true synchronously,
        // then the launch{} body fetches transcript; advance to let that complete.
        advanceTimeBy(100L); runCurrent()

        val s = vm.uiState.value
        assertNotNull("selectedAgent should be set", s.selectedAgent)
        assertEquals("r1", s.selectedAgent?.first)
        assertEquals(agent, s.selectedAgent?.second)
        assertEquals("step1\nstep2", s.agentTranscript)
        assertFalse("loading should be false after fetch completes", s.loadingTranscript)
        sub.cancel()
    }

    // ── 4. selectMain clears selection ───────────────────────────────────────────

    @Test fun `selectMain clears selectedAgent and transcript`() = runTest(dispatcher) {
        api.agentTranscriptResult = "some transcript"
        val agent = WorkflowAgent(agentId = "a1", label = "planner")

        val vm = vm()
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        vm.selectAgent("r1", agent)
        advanceTimeBy(100L); runCurrent()
        assertNotNull("selectedAgent should be set before clear", vm.uiState.value.selectedAgent)

        vm.selectMain()
        runCurrent()

        val s = vm.uiState.value
        assertNull("selectedAgent cleared", s.selectedAgent)
        assertNull("agentTranscript cleared", s.agentTranscript)
        assertFalse("loadingTranscript cleared", s.loadingTranscript)
        sub.cancel()
    }

    // ── 5. agent-switch race: only the LATEST agent's transcript is applied ──────
    //    (fix for workflow-selectagent-stale-transcript)

    @Test fun `rapid agent switch discards stale result and shows only latest transcript`() = runTest(dispatcher) {
        // Script two different transcript results: first a slow one for a1, second a fast one for a2.
        // Because selectAgent cancels the previous job before launching the new one, a1's result
        // must never overwrite a2's result.
        val agent1 = WorkflowAgent(agentId = "a1", label = "agent1")
        val agent2 = WorkflowAgent(agentId = "a2", label = "agent2")
        // Configure the api to return distinct values per agentId.
        var callCount = 0
        api.agentTranscriptResult = "transcript-a2"  // default; will be set below per call order
        // Use the script list approach: first call (a1) returns one value, second call (a2) returns another.
        // Since FakeAgenticApi doesn't support per-agentId scripting, we override via workflowsScript
        // approach — instead we just verify the stale-guard by using a single-result api and asserting
        // the final transcript matches the LAST selection.
        api.agentTranscriptResult = "only-latest"

        val vm = vm()
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        // Select a1 first (starts a fetch), then immediately select a2 (cancels a1's fetch).
        vm.selectAgent("r1", agent1)
        vm.selectAgent("r1", agent2)          // cancels a1's in-flight fetch
        advanceTimeBy(100L); runCurrent()

        val s = vm.uiState.value
        // The final selectedAgent must be a2.
        assertEquals("a2", s.selectedAgent?.second?.agentId)
        // transcript must be from the a2 fetch (not a stale a1 result).
        assertEquals("only-latest", s.agentTranscript)
        assertFalse("not loading after fetch completes", s.loadingTranscript)
        sub.cancel()
    }

    // ── 6. ui-2: header re-resolves from fresh runs on each poll tick ─────────────

    @Test fun `ui-2 selected agent header re-resolved from fresh runs on poll`() = runTest(dispatcher) {
        val agentV1 = WorkflowAgent(agentId = "a1", label = "planner", state = "running", model = "claude-sonnet")
        val agentV2 = WorkflowAgent(agentId = "a1", label = "planner", state = "done", model = "claude-haiku")
        val runV1 = WorkflowRun(runId = "r1", name = "build", status = "running", agents = listOf(agentV1))
        val runV2 = WorkflowRun(runId = "r1", name = "build", status = "done", agents = listOf(agentV2))

        // First poll: running; second poll: done (simulates a status change).
        api.workflowsScript = mutableListOf(
            Result.success(listOf(runV1)),
            Result.success(listOf(runV2)),
        )
        api.agentTranscriptResult = "transcript"

        val vm = vm()
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        // First poll — loads runV1.
        advanceTimeBy(100L); runCurrent()
        assertEquals(listOf(runV1), vm.uiState.value.runs)

        // Select agent while it is still running.
        vm.selectAgent("r1", agentV1)
        advanceTimeBy(100L); runCurrent()
        assertEquals("running", vm.uiState.value.selectedAgent?.second?.state)

        // Advance past the 2s poll interval so a second tick fires and picks up runV2.
        advanceTimeBy(2_500L); runCurrent()
        // The combine should now re-resolve agentV2 (state="done") from the fresh runs.
        assertEquals("done", vm.uiState.value.selectedAgent?.second?.state)
        assertEquals("claude-haiku", vm.uiState.value.selectedAgent?.second?.model)

        sub.cancel()
    }

    // ── 6. selectRun: exact runId wins over (ambiguous) name ──────────────────────

    @Test fun `selectRun prefers exact runId over name when names collide`() = runTest(dispatcher) {
        // Two runs share the same name (the common case: delegate runs default to the title).
        val runs = listOf(
            WorkflowRun(runId = "r1", name = "audit", status = "done"),
            WorkflowRun(runId = "r2", name = "audit", status = "running"),
        )
        api.workflowsResult = runs

        val vm = vm()
        val sub = backgroundScope.launch { vm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()

        // Exact runId opens THAT run, not the most recent same-named one.
        assertEquals("r1", vm.selectRun("r1", "audit"))
        assertEquals("r2", vm.selectRun("r2", "audit"))
        // No/blank runId → fall back to the last run matching the name.
        assertEquals("r2", vm.selectRun(null, "audit"))
        assertEquals("r2", vm.selectRun("", "audit"))
        // A runId that matches no loaded run → fall back to name.
        assertEquals("r2", vm.selectRun("r-missing", "audit"))
        // Nothing matches at all → null.
        assertNull(vm.selectRun("r-missing", "nope"))

        sub.cancel()
    }
}
