package dev.agentic.ui.tree

import androidx.lifecycle.SavedStateHandle
import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.net.CommitFile
import dev.agentic.data.net.CommitNode
import dev.agentic.data.net.RepoCommits
import dev.agentic.data.net.Uncommitted
import dev.agentic.data.repo.FilesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Tests for [CommitGraphViewModel].
 *
 * The VM uses a plain MutableStateFlow (not stateIn/WhileSubscribed) and calls load() in init —
 * so there is NO infinite upstream. All tests can safely use advanceUntilIdle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommitGraphViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var api: FakeAgenticApi

    @Before fun setUp() {
        dispatcher = StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        api = FakeAgenticApi()
    }

    @After fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun filesRepo() = FilesRepository(api)

    private fun vm(id: String = "s1") =
        CommitGraphViewModel(filesRepo(), SavedStateHandle(mapOf("id" to id)))

    private fun commit(sha: String, isSession: Boolean = false) =
        CommitNode(sha = sha, shortSha = sha.take(7), subject = "msg $sha", isSession = isSession)

    // ── 1. initial state has loading=true, empty repos, no error ─────────────────

    @Test fun `initial state has loading=true before load completes`() {
        // Do NOT advance — check state before init's load() coroutine runs.
        val vm = vm()
        val s = vm.uiState.value
        assertTrue("loading should be true initially", s.loading)
        assertTrue("repos empty initially", s.repos.isEmpty())
        assertNull("no error initially", s.error)
    }

    // ── 2. load success: repos set, loading=false, error=null ────────────────────

    @Test fun `load success populates repos and clears loading`() = runTest(dispatcher) {
        val repos = listOf(
            RepoCommits(
                repo = "demo",
                commits = listOf(commit("aaaaaaa", isSession = true), commit("bbbbbbb")),
                uncommitted = Uncommitted(added = 1, modified = 0, deleted = 0),
            ),
        )
        api.commitsResult = repos

        val vm = vm()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals(repos, s.repos)
        assertFalse("loading false after success", s.loading)
        assertNull("no error on success", s.error)
    }

    // ── 3. load empty: repos empty, loading=false, no error ──────────────────────

    @Test fun `load empty yields empty repos with no error`() = runTest(dispatcher) {
        api.commitsResult = emptyList()

        val vm = vm()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertTrue("repos empty", s.repos.isEmpty())
        assertFalse("loading false", s.loading)
        assertNull("no error", s.error)
    }

    // ── 4. load failure: error set (clean userMessage), loading=false ────────────

    @Test fun `load failure sets clean error and clears loading`() = runTest(dispatcher) {
        api.commitsException = RuntimeException("network gone")

        val vm = vm()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertNotNull("error should be set", s.error)
        // userMessage() for an Unknown error — never the raw toString().
        assertEquals("Something went wrong.", s.error)
        assertFalse("loading false after failure", s.loading)
        assertTrue("repos empty on failure", s.repos.isEmpty())
    }

    // ── 5. loadFilesFor populates the detail sheet ───────────────────────────────

    @Test fun `loadFilesFor populates detail files`() = runTest(dispatcher) {
        api.commitsResult = emptyList()
        val files = listOf(
            CommitFile("a.kt", "added", 5, 0),
            CommitFile("b.kt", "deleted", 0, 3),
        )
        api.commitFilesResult = files

        val vm = vm()
        advanceUntilIdle()

        vm.loadFilesFor(repo = "demo", sha = "abc1234", title = "abc1234")
        // Synchronously the detail is opened in loading state.
        assertNotNull("detail opened immediately", vm.uiState.value.detail)
        assertTrue("detail loading before fetch returns", vm.uiState.value.detail!!.loading)

        advanceUntilIdle()

        val detail = vm.uiState.value.detail!!
        assertEquals("demo", detail.repo)
        assertEquals("abc1234", detail.sha)
        assertEquals(files, detail.files)
        assertFalse("detail loaded", detail.loading)
        assertNull("no detail error", detail.error)
        // Passed repo + sha through to the API correctly.
        assertEquals(Triple("s1", "demo", "abc1234"), api.commitFilesCalls.single())
    }

    @Test fun `loadFilesFor failure sets detail error`() = runTest(dispatcher) {
        api.commitsResult = emptyList()
        api.commitFilesException = RuntimeException("boom")

        val vm = vm()
        advanceUntilIdle()

        vm.loadFilesFor(repo = "demo", sha = "working", title = "Uncommitted")
        advanceUntilIdle()

        val detail = vm.uiState.value.detail!!
        assertFalse("detail not loading after failure", detail.loading)
        assertEquals("Something went wrong.", detail.error)
    }

    // ── 6. discard calls api.discard then reloads ────────────────────────────────

    @Test fun `discard calls api discard then reloads`() = runTest(dispatcher) {
        api.commitsResult = listOf(RepoCommits(repo = "demo", commits = listOf(commit("aaaaaaa"))))

        val vm = vm()
        advanceUntilIdle()   // initial load
        val callsAfterInit = api.commitsCallCount
        assertEquals(1, callsAfterInit)

        vm.discard()
        advanceUntilIdle()

        assertTrue("api.discard called for s1", api.discardCalls.contains("s1"))
        // load() ran again after discard.
        assertEquals(2, api.commitsCallCount)
        assertFalse("loading false after reload", vm.uiState.value.loading)
        assertEquals(api.commitsResult, vm.uiState.value.repos)
    }

    // ── 7. explicit load re-fetches ──────────────────────────────────────────────

    @Test fun `explicit load call re-fetches commits`() = runTest(dispatcher) {
        api.commitsResult = listOf(RepoCommits(repo = "demo", commits = listOf(commit("aaaaaaa"))))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.repos.size)

        api.commitsResult = listOf(
            RepoCommits(repo = "demo", commits = listOf(commit("aaaaaaa"))),
            RepoCommits(repo = "other", commits = listOf(commit("ccccccc"))),
        )
        vm.load()
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.repos.size)
    }
}
