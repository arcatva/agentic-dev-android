package dev.agentic.ui.session

import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.net.DetachResp
import dev.agentic.data.net.Session
import dev.agentic.data.net.SessionDetail
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
 * Covers [SessionSettingsViewModel.detach] — the "Open in Claude Code" hand-off. On success it
 * POSTs /api/sessions/:id/detach, exposes the returned `resumeCmd` for copy, and flips the local
 * session's `detached` flag; on failure it clears the in-flight flag and surfaces the error
 * without a resume command. Same StandardTestDispatcher + FakeAgenticApi harness as RewindTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSettingsDetachTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var api: FakeAgenticApi
    private lateinit var repoScope: CoroutineScope

    @Before fun setUp() {
        dispatcher = StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        api = FakeAgenticApi()
        repoScope = CoroutineScope(dispatcher)
        // The settings VM loads the session via get() on init; seed it.
        api.sessionDetailDefault = SessionDetail(Session(id = "s1", status = "done", repos = listOf("demo")))
    }

    @After fun tearDown() {
        repoScope.cancel()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun vm() = SessionSettingsViewModel(SessionsRepository(api, repoScope), "s1")

    @Test fun `detach posts, exposes resume command, and marks the session detached`() = runTest(dispatcher) {
        api.detachResult = DetachResp(
            cwd = "/home/me/proj",
            claudeSessionId = "csid-1",
            resumeCmd = "cd '/home/me/proj' && claude --resume csid-1",
        )
        val vm = vm()
        advanceUntilIdle() // init loads the session

        vm.detach()
        advanceUntilIdle()

        assertEquals(listOf("s1"), api.detachCalls)
        val s = vm.uiState.value
        assertFalse("in-flight flag cleared after completion", s.detaching)
        assertEquals("cd '/home/me/proj' && claude --resume csid-1", s.resumeCmd)
        assertTrue("local session reflects the hand-off", s.session?.detached == true)
        assertNull(s.error)
    }

    @Test fun `detach failure clears the in-flight flag and surfaces an error`() = runTest(dispatcher) {
        api.detachException = RuntimeException("boom")
        val vm = vm()
        advanceUntilIdle()

        vm.detach()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.detaching)
        assertNull("no resume command on failure", s.resumeCmd)
        assertNotNull("error surfaced", s.error)
    }
}
