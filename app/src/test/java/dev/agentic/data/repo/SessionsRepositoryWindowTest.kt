package dev.agentic.data.repo

import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.net.Session
import dev.agentic.data.net.SessionDetail
import dev.agentic.domain.PromptNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the bounded transcript window ([SessionsRepository.loadEarlier] / [loadNewer]) on
 * ENDED sessions. The [FakeAgenticApi] paged-log mode serves an immutable event list as tail/before
 * windows (one rendered line per event), modeling the server's Discord-style cursor contract, so we
 * can drive real scroll-back / scroll-forward and assert the resident window stays bounded and tiled
 * (contiguous, no gaps, no duplicates).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsRepositoryWindowTest {

    private lateinit var api: FakeAgenticApi
    private lateinit var repoScope: CoroutineScope

    /** A `prompt` event → one PromptNode with unique text "e$i", so nodes map 1:1 to events in order. */
    private fun ev(i: Int): JsonElement = Json.parseToJsonElement("""{"kind":"prompt","text":"e$i"}""")

    private fun texts(repo: SessionsRepository, id: String = "s1"): List<String> =
        repo.transcript(id).value.nodes.filterIsInstance<PromptNode>().map { it.text }

    @Before fun setUp() {
        api = FakeAgenticApi()
        // 10 events e0..e9 as an immutable ended-session log, served two-per-page so a maxResidentEvents
        // of 4 (= 2 pages) forces eviction after a couple of scroll-back steps.
        api.pagedEvents["s1"] = (0..9).map { ev(it) }
        api.pagedPageLimit = 2
        api.sessionDetails["s1"] = SessionDetail(Session(id = "s1", prompt = "go", status = "done"))
    }

    @After fun tearDown() {
        if (::repoScope.isInitialized) repoScope.cancel()
    }

    private fun newRepo() = SessionsRepository(api, repoScope, maxResidentEvents = 4)

    @Test fun `scroll-back evicts off the newest end and keeps the window bounded`() = runTest {
        repoScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val repo = newRepo()
        val flow = repo.transcript("s1")
        advanceUntilIdle()

        // Initial tail window: last two events, more older, nothing newer evicted yet.
        assertEquals(listOf("e8", "e9"), texts(repo))
        assertTrue(flow.value.ended)
        assertTrue(flow.value.hasMore)
        assertFalse(flow.value.hasNewer)

        // Scroll all the way back.
        var i = 0
        while (flow.value.hasMore && i++ < 20) { repo.loadEarlier("s1"); advanceUntilIdle() }

        val t = texts(repo)
        assertFalse("reached the oldest page", flow.value.hasMore)
        assertTrue("newer pages were evicted", flow.value.hasNewer)
        assertEquals("window bounded at the oldest page(s)", listOf("e0", "e1", "e2", "e3"), t)
        assertTrue("resident stays within the cap", t.size <= 4)
    }

    @Test fun `scroll-forward pages evicted-newer content back in, ending at the tail`() = runTest {
        repoScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val repo = newRepo()
        val flow = repo.transcript("s1")
        advanceUntilIdle()
        var i = 0
        while (flow.value.hasMore && i++ < 20) { repo.loadEarlier("s1"); advanceUntilIdle() }
        assertTrue(flow.value.hasNewer)

        // Now scroll forward again.
        var j = 0
        while (flow.value.hasNewer && j++ < 20) { repo.loadNewer("s1"); advanceUntilIdle() }

        val t = texts(repo)
        assertFalse("back at the tail — nothing newer left", flow.value.hasNewer)
        assertEquals("window bounded at the newest page(s)", listOf("e6", "e7", "e8", "e9"), t)
        assertTrue("the true tail (e9) is resident", t.contains("e9"))
        // Tiling: contiguous, strictly ascending, no duplicates — pages never overlapped or gapped.
        assertEquals(t.sorted(), t)
        assertEquals(t.toSet().size, t.size)
    }

    @Test fun `round-trip up then down returns exactly the original tail`() = runTest {
        repoScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val repo = newRepo()
        val flow = repo.transcript("s1")
        advanceUntilIdle()
        var i = 0
        while (flow.value.hasMore && i++ < 20) { repo.loadEarlier("s1"); advanceUntilIdle() }
        var j = 0
        while (flow.value.hasNewer && j++ < 20) { repo.loadNewer("s1"); advanceUntilIdle() }
        // The newest two events match the initial tail load — reload via before=upper is byte-identical.
        assertEquals(listOf("e8", "e9"), texts(repo).takeLast(2))
        assertFalse(flow.value.hasNewer)
    }
}
