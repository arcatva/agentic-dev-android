package dev.agentic.ui.adopt

import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.net.Adoptable
import dev.agentic.data.net.AdoptSessionReq
import dev.agentic.data.repo.SessionsRepository
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers [AdoptPickerViewModel] — the adopt picker's load + adopt state machine — against the
 * FakeAgenticApi + real SessionsRepository, same StandardTestDispatcher harness as RewindTest.
 * Notably includes the regression that an adopt() failure must NOT wipe the candidate list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdoptPickerViewModelTest {

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
        repoScope.cancel()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun vm() = AdoptPickerViewModel(SessionsRepository(api, repoScope))

    private fun cand(id: String, mtime: Double) = Adoptable(
        sessionId = id, cwd = "/p/$id", slug = "slug", firstPrompt = "prompt $id",
        mtimeMs = mtime, resumable = true, lineCount = 1,
    )

    @Test fun `load sorts candidates newest-first and clears loading`() = runTest(dispatcher) {
        api.adoptableResult = listOf(cand("a", 100.0), cand("b", 300.0), cand("c", 200.0))
        val vm = vm()
        vm.load(); advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(s.loading)
        assertNull(s.error)
        assertEquals(listOf("b", "c", "a"), s.items.map { it.sessionId })
    }

    @Test fun `load empty is distinct from an error`() = runTest(dispatcher) {
        api.adoptableResult = emptyList()
        val vm = vm()
        vm.load(); advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(s.loading)
        assertNull(s.error)
        assertTrue(s.items.isEmpty())
    }

    @Test fun `load failure surfaces an error and leaves items empty`() = runTest(dispatcher) {
        api.adoptableException = RuntimeException("boom")
        val vm = vm()
        vm.load(); advanceUntilIdle()
        val s = vm.uiState.value
        assertNotNull(s.error)
        assertTrue(s.items.isEmpty())
    }

    @Test fun `adopt success records the POST and exposes the new session id`() = runTest(dispatcher) {
        api.adoptableResult = listOf(cand("a", 1.0))
        api.adoptSessionResult = "new-session-id"
        val vm = vm()
        vm.load(); advanceUntilIdle()
        vm.adopt(vm.uiState.value.items[0]); advanceUntilIdle()
        assertEquals(listOf(AdoptSessionReq("a", "/p/a")), api.adoptSessionCalls)
        val s = vm.uiState.value
        assertEquals("new-session-id", s.adoptedId)
        assertNull(s.adoptingCsid)
    }

    @Test fun `adopt failure keeps the list and surfaces an inline error`() = runTest(dispatcher) {
        api.adoptableResult = listOf(cand("a", 1.0))
        api.adoptSessionException = RuntimeException("nope")
        val vm = vm()
        vm.load(); advanceUntilIdle()
        vm.adopt(vm.uiState.value.items[0]); advanceUntilIdle()
        val s = vm.uiState.value
        assertNotNull(s.error)
        assertEquals("adopt error must NOT wipe the candidate list", 1, s.items.size)
        assertNull(s.adoptedId)
        assertNull(s.adoptingCsid)
    }

    @Test fun `acknowledgeAdopt clears the one-shot adoptedId`() = runTest(dispatcher) {
        api.adoptableResult = listOf(cand("a", 1.0))
        api.adoptSessionResult = "x"
        val vm = vm()
        vm.load(); advanceUntilIdle()
        vm.adopt(vm.uiState.value.items[0]); advanceUntilIdle()
        vm.acknowledgeAdopt()
        assertNull(vm.uiState.value.adoptedId)
    }
}
