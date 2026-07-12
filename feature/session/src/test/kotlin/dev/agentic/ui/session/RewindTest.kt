package dev.agentic.ui.session

import androidx.lifecycle.SavedStateHandle
import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.net.Session
import dev.agentic.data.net.SessionDetail
import dev.agentic.data.repo.FilesRepository
import dev.agentic.data.repo.SessionsRepository
import dev.agentic.data.repo.WorkflowsRepository
import dev.agentic.domain.AnswerNode
import dev.agentic.domain.PromptNode
import dev.agentic.domain.ToolNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RewindTest {

    // ── promptTurnIndices (pure) ─────────────────────────────────────────────

    @Test fun `prompt turn indices number prompts and mark non-prompts -1`() {
        val nodes = listOf(
            PromptNode("first"),        // turn 0
            AnswerNode("a"),            // -1
            ToolNode("Edit", "s", "d"), // -1
            PromptNode("second"),       // turn 1
            PromptNode("third"),        // turn 2
            AnswerNode("b"),            // -1
        )
        assertArrayEquals(intArrayOf(0, -1, -1, 1, 2, -1), promptTurnIndices(nodes))
    }

    @Test fun `prompt turn indices empty`() {
        assertArrayEquals(intArrayOf(), promptTurnIndices(emptyList()))
    }

    // ── SessionViewModel.rewind ──────────────────────────────────────────────

    private lateinit var dispatcher: TestDispatcher
    private lateinit var api: FakeAgenticApi
    private lateinit var repoScope: CoroutineScope

    @Before fun setUp() {
        dispatcher = StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        api = FakeAgenticApi()
        repoScope = CoroutineScope(dispatcher)
        api.sessionDetailDefault = SessionDetail(Session(id = "s1", status = "done", repos = listOf("demo")))
    }

    @After fun tearDown() {
        repoScope.cancel()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun vm() = SessionViewModel(
        SessionsRepository(api, repoScope),
        WorkflowsRepository(api, repoScope),
        FilesRepository(api),
        api,
        SavedStateHandle(mapOf("id" to "s1")),
        dispatcher,
    )

    @Test fun `rewind success posts turn index and reports Done`() = runTest(dispatcher) {
        val vm = vm()
        vm.rewind(2)
        advanceUntilIdle()
        assertEquals(listOf("s1" to 2), api.rewindCalls)
        assertEquals(RewindResult.Done(2), vm.rewindResult.value)
    }

    @Test fun `rewind failure reports clean error`() = runTest(dispatcher) {
        api.rewindException = RuntimeException("boom")
        val vm = vm()
        vm.rewind(1)
        advanceUntilIdle()
        assertEquals(RewindResult.Failed("Something went wrong."), vm.rewindResult.value)
    }

    @Test fun `acknowledgeRewind clears the one-shot result`() = runTest(dispatcher) {
        val vm = vm()
        vm.rewind(0); advanceUntilIdle()
        vm.acknowledgeRewind()
        assertNull(vm.rewindResult.value)
    }
}
