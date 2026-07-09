package dev.agentic.ui.tree

import androidx.lifecycle.SavedStateHandle
import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.net.DiffHunk
import dev.agentic.data.net.DiffLine
import dev.agentic.data.net.FileDiff
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tests for [FileDiffViewModel] — bounded one-shot fetch in init, plain MutableStateFlow. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileDiffViewModelTest {

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

    private fun vm(id: String = "s1", repo: String = "demo", sha: String = "working", path: String = "src/x.kt") =
        FileDiffViewModel(
            FilesRepository(api),
            SavedStateHandle(mapOf("id" to id, "repo" to repo, "sha" to sha, "path" to path)),
        )

    @Test fun `initial state seeds path and sha and is loading`() {
        val vm = vm(path = "src/x.kt", sha = "working")
        val s = vm.uiState.value
        assertTrue(s.loading)
        assertEquals("src/x.kt", s.path)
        assertEquals("working", s.sha)
        assertNull(s.diff)
    }

    @Test fun `load success populates diff and passes args through`() = runTest(dispatcher) {
        api.commitDiffResult = FileDiff(
            path = "src/x.kt", status = "modified",
            hunks = listOf(DiffHunk(1, 1, 1, 2, "", listOf(
                DiffLine(kind = "context", oldLine = 1, newLine = 1, content = "a"),
                DiffLine(kind = "add", oldLine = null, newLine = 2, content = "b"),
            ))),
        )

        val vm = vm(id = "s1", repo = "demo", sha = "working", path = "src/x.kt")
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.loading)
        assertNull(s.error)
        assertEquals(2, s.diff!!.hunks.single().lines.size)
        assertEquals(listOf("s1", "demo", "working", "src/x.kt"), api.commitDiffCalls.single())
    }

    @Test fun `load failure sets clean error`() = runTest(dispatcher) {
        api.commitDiffException = RuntimeException("boom")
        val vm = vm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(s.loading)
        assertEquals("Something went wrong.", s.error)
        assertNull(s.diff)
    }
}
