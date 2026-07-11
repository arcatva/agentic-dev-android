package dev.agentic.data.repo

import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.SharedFile
import dev.agentic.data.net.WorkflowRun
import dev.agentic.domain.AttachmentNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Virtual time via StandardTestDispatcher + FakeAgenticApi tests poll-and-last-good without real network/wall-clock.
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowsRepositoryTest {

    private lateinit var api: FakeAgenticApi
    private lateinit var repoScope: CoroutineScope

    @Before fun setUp() {
        api = FakeAgenticApi()
    }

    @After fun tearDown() {
        if (::repoScope.isInitialized) repoScope.cancel()
    }


    @Test fun `runsStream emits api workflows result and keeps last-good when a tick throws`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)

        val first = listOf(WorkflowRun(runId = "r1", name = "build", status = "running"))
        val error = java.io.IOException("blip")
        val third = listOf(WorkflowRun(runId = "r2", name = "deploy", status = "done"))

        api.workflowsScript = mutableListOf(
            Result.success(first),
            Result.failure<List<WorkflowRun>>(error),
            Result.success(third),
        )

        val repo = WorkflowsRepository(api, repoScope)
        val emissions = repo.runsStream("sess1").take(3).toList()

        assertEquals("first tick emits the workflow list", first, emissions[0])
        assertEquals("error tick re-emits the last-good list", first, emissions[1])
        assertEquals("next success tick emits new list", third, emissions[2])
    }


    @Test fun `outboxStream maps SharedFile to AttachmentNode with mtime converted toLong`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)

        val sharedFiles = listOf(
            SharedFile(path = "out/a.txt", name = "a.txt", mtime = 1_700_000_000.9),
            SharedFile(path = "out/b.kt",  name = "b.kt",  mtime = 1_700_000_500.1),
        )
        api.outboxResult = sharedFiles

        val repo = WorkflowsRepository(api, repoScope)
        val emission = repo.outboxStream("sess1").take(1).toList().single()

        val expected = listOf(
            AttachmentNode(path = "out/a.txt", at = 1_700_000_000L),
            AttachmentNode(path = "out/b.kt",  at = 1_700_000_500L),
        )
        assertEquals(expected, emission)
    }


    @Test fun `FilesRepository upload returns Outcome Success on ok`() = runTest {
        api.uploadPathResult = "uploads/photo.png"
        val repo = FilesRepository(api)

        val result = repo.upload("sess1", byteArrayOf(1, 2, 3), "photo.png")

        assertTrue("expected Outcome.Success but got $result", result is Outcome.Success<*>)
        assertEquals("uploads/photo.png", (result as Outcome.Success<String>).value)
    }
}
