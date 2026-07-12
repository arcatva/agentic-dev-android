package dev.agentic.ui.session

import androidx.lifecycle.SavedStateHandle
import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.net.Session
import dev.agentic.data.net.SessionDetail
import dev.agentic.data.net.SessionEventsResponse
import dev.agentic.data.repo.FilesRepository
import dev.agentic.data.repo.SessionsRepository
import dev.agentic.data.repo.WorkflowsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.*
import org.junit.Assert.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private lateinit var api: FakeAgenticApi
    private lateinit var repoScope: CoroutineScope

    @Before fun setUp() {
        dispatcher = StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        api = FakeAgenticApi()
        repoScope = CoroutineScope(dispatcher)
    }
    @After fun tearDown() { kotlinx.coroutines.Dispatchers.resetMain(); repoScope.cancel() }

    private fun sessionsRepo() = SessionsRepository(api, repoScope)
    private fun workflowsRepo() = WorkflowsRepository(api, repoScope)
    private fun vm(handle: SavedStateHandle = SavedStateHandle(mapOf("id" to "s1"))) =
        SessionViewModel(sessionsRepo(), workflowsRepo(), FilesRepository(api), handle, dispatcher)

    private val seedLog = listOf("""{"type":"agentic_prompt","text":"go","at":1}""", """{"type":"result","result":"ok"}""")

    // Structured-event twin of [seedLog] for the migrated repo load() (FakeAgenticApi.session()
    // is unused — load() now goes through sessionEventsResult, and a response scripted only on
    // sessionDetails throws TODO at every load). Mirrors SessionsRepositoryTest.eventsResponse(...).
    private fun ev(json: String): JsonElement = Json.parseToJsonElement(json)
    private val seedEvents: List<JsonElement> = listOf(
        ev("""{"kind":"prompt","text":"go","at":1}"""),
        ev("""{"kind":"result","text":"ok"}"""),
    )
    private fun eventsResponse(session: Session) = SessionEventsResponse(
        session = session,
        events = seedEvents,
        latestEventId = seedEvents.size.toLong(),
    )

    @Test fun `ackEvent calls ackSession on the API`() = runTest(dispatcher) {
        val session = Session(id = "s1", prompt = "go", status = "done", claudeSessionId = "claude-1", unreadEventId = 7)
        // Script the migrated events surface (load uses api.sessionEvents). Keeps the post-stream
        // session() idle and terminal so we don't trip the reconnect loop in virtual time.
        api.sessionEventsResult = eventsResponse(session)
        val svm = vm()
        val sub = backgroundScope.launch { svm.uiState.collect { } }
        advanceTimeBy(100L); runCurrent()
        svm.ackEvent(7)
        // Bounded advance: ackSession is best-effort, runs under NonCancellable with a 5s guard —
        // 6s covers it; advanceTimeBy bounds virtual time so the 2s sessionRefreshStream poll
        // doesn't schedule forever. Test ends deterministically instead of hanging.
        advanceTimeBy(6_000L); runCurrent()
        // Direct assertion: ackSession was actually delivered with (id="s1", eventId=7).
        assertEquals("ackSession forwarded to the API", listOf("s1" to 7L), api.ackSessionCalls.toList())
        sub.cancel()
    }
}
