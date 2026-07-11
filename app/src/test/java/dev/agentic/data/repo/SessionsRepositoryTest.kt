package dev.agentic.data.repo

import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.FakeSettingsStore
import dev.agentic.domain.AnswerNode
import dev.agentic.domain.ToolNode
import dev.agentic.domain.TranscriptReducer
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.SearchHit
import dev.agentic.data.net.SearchResponse
import dev.agentic.data.net.Session
import dev.agentic.data.net.SessionDetail
import dev.agentic.data.net.SessionEventsResponse
import dev.agentic.data.repo.SessionsLoadState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsRepositoryTest {

    private lateinit var api: FakeAgenticApi
    private lateinit var repoScope: CoroutineScope

    private fun runningSession(id: String = "s1") =
        Session(id = id, prompt = "go", status = "running")

    private fun terminalSession(id: String = "s1") =
        Session(id = id, prompt = "go", status = "done")

    private val seedLog = listOf(
        """{"type":"agentic_prompt","text":"go","at":1}""",
        """{"type":"result","result":"ok"}""",
    )

    // Log entries (buildFromLog shape) that produce a ToolNode and an AnswerNode — used as the
    // post-stream persisted log so a reseed-on-reconnect reproduces what the live stream delivered.
    private val toolLogEntry =
        """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"/a/B.kt"}}]}}"""
    private val answerLogEntry = """{"type":"result","result":"done"}"""

    // The repo migrated its load/refetch/refresh paths from api.session(id) (SessionDetail + raw JSONL
    // `log`) to api.sessionEvents(id, …) (SessionEventsResponse + structured `events`). These are the
    // events-surface equivalents of [seedLog]/[toolLogEntry]/[answerLogEntry]: kind-tagged wire objects
    // that seedFromEvents folds into the SAME nodes buildFromLog produced for the old log lines, so the
    // reducer-equivalence assertions (e.g. `nodes == seedFromLog(seedLog).display()`) still hold.
    private fun ev(json: String): JsonElement = Json.parseToJsonElement(json)

    // Structured-event twin of [seedLog]: prompt "go" @1 + result "ok" → PromptNode + AnswerNode.
    private val seedEvents: List<JsonElement> = listOf(
        ev("""{"kind":"prompt","text":"go","at":1}"""),
        ev("""{"kind":"result","text":"ok"}"""),
    )
    // Structured-event twin of [toolLogEntry]: a Read ToolNode.
    private val toolEvent: JsonElement = ev("""{"kind":"tool","name":"Read","input":{"file_path":"/a/B.kt"}}""")
    // Structured-event twin of [answerLogEntry]: an AnswerNode("done").
    private val answerEvent: JsonElement = ev("""{"kind":"result","text":"done"}""")

    // Build a [SessionEventsResponse] the migrated load/refetch/refresh paths consume.
    private fun eventsResponse(
        session: Session,
        events: List<JsonElement> = seedEvents,
        latestEventId: Long = events.size.toLong(),
        firstEventLine: Long = 0L,
        hasMore: Boolean = false,
    ) = SessionEventsResponse(
        session = session,
        events = events,
        latestEventId = latestEventId,
        firstEventLine = firstEventLine,
        hasMore = hasMore,
    )

    // How many times the migrated load/refetch/refresh paths hit api.sessionEvents for [id] — the
    private fun FakeAgenticApi.sessionEventsCallCount(id: String): Int = sessionEventsCalls.count { it == id }

    @Before fun setUp() {
        api = FakeAgenticApi()
    }

    @After fun tearDown() {
        if (::repoScope.isInitialized) repoScope.cancel()
    }


    @Test fun `draftFor falls back to the persisted disk draft after the in-memory cache is gone`() {
        repoScope = CoroutineScope(StandardTestDispatcher())
        val settings = FakeSettingsStore()
        // shares the same persisted store, so the draft is recovered from disk.
        SessionsRepository(api, repoScope, settings).setDraft("s1", "half-typed message")
        assertEquals("half-typed message", SessionsRepository(api, repoScope, settings).draftFor("s1"))
        SessionsRepository(api, repoScope, settings).clearDraft("s1")
        assertEquals("", SessionsRepository(api, repoScope, settings).draftFor("s1"))
    }


    @Test fun `transcript first emits connecting then a loaded state matching reducer display`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(terminalSession())
        val repo = SessionsRepository(api, repoScope)

        val flow = repo.transcript("s1")
        assertTrue("expected connecting=true initially", flow.value.connecting)

        advanceUntilIdle()

        val loaded = flow.value
        assertFalse(loaded.connecting)
        assertNotNull(loaded.session)
        assertEquals("s1", loaded.session?.id)
        val expected = TranscriptReducer().apply { seedFromLog(seedLog) }.display()
        assertEquals(expected, loaded.nodes)
    }

    @Test fun `first-load failure sets loadError (no permanent blank) and reload recovers`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsException = java.io.IOException("server down")
        val repo = SessionsRepository(api, repoScope)
        val flow = repo.transcript("s1")
        advanceUntilIdle()
        assertFalse("must not be stuck connecting", flow.value.connecting)
        assertTrue("first-load failure must set loadError", flow.value.loadError)
        assertTrue("no session yet", flow.value.session == null)
        api.sessionEventsException = null
        api.sessionEventsResult = eventsResponse(terminalSession())
        repo.reload("s1")
        advanceUntilIdle()
        assertFalse("loadError clears after a successful reload", flow.value.loadError)
        assertNotNull("session loaded after reload", flow.value.session)
        assertEquals("s1", flow.value.session?.id)
    }


    @Test fun `non-terminal session opens stream and frames append nodes and flip busy`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        // re-fetches a TERMINAL session whose log persisted the streamed tool+result, so the loop
        // reseeds, sees terminal and stops (bounded).
        api.sessionEventsScript["s1"] = mutableListOf(
            Result.success(eventsResponse(runningSession())),
            Result.success(eventsResponse(terminalSession(), events = seedEvents + toolEvent + answerEvent)),
        )
        api.streamFrames = listOf(
            """{"kind":"tool","name":"Read","input":{"file_path":"/a/B.kt"}}""",
            """{"kind":"result","text":"done"}""",
        )
        val repo = SessionsRepository(api, repoScope)

        val flow = repo.transcript("s1")
        advanceUntilIdle()

        val st = flow.value
        assertEquals(1, api.streamCallCount)
        assertEquals(seedEvents.size, api.streamSinceArgs.first())
        assertTrue("tool frame should add a ToolNode", st.nodes.any { it is ToolNode })
        assertTrue("result frame should add an AnswerNode", st.nodes.any { it is AnswerNode })
        assertFalse("result frame sets busy=false", st.busy)
    }

    @Test fun `windowed load opens the stream from total, not the tail size`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        // (windowed ?limit path): start=17, total=seedLog.size+17. The reducer seeds from the tail, but
        // the live cursor is a rendered-offset into the FULL log — so the stream must resume at `total`,
        // NOT `log.size`, or it would re-stream 17 lines of already-shown history.
        val total = seedEvents.size + 17
        // latestEventId is the FULL-log rendered offset (`total`) and firstEventLine the window start (17):
        // the live cursor must resume at `total`, not the tail size, or it would re-stream shown history.
        api.sessionEventsScript["s1"] = mutableListOf(
            Result.success(eventsResponse(runningSession(), events = seedEvents, latestEventId = total.toLong(), firstEventLine = 17L)),
            Result.success(eventsResponse(terminalSession(), events = seedEvents, latestEventId = total.toLong(), firstEventLine = 17L)),
        )
        api.streamFrames = listOf("""{"kind":"result","text":"done"}""")
        val repo = SessionsRepository(api, repoScope)

        repo.transcript("s1")
        advanceUntilIdle()

        assertEquals(total, api.streamSinceArgs.first())
        assertEquals(SessionsRepository.INITIAL_LOG_LIMIT, api.sessionEventsLimitArgs.first())
    }

    @Test fun `busy is true while a tool turn is generating before result`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(runningSession())
        // loop does NOT advance to the refetch — busy stays true. We step the stream open via
        // runCurrent (load + open) without ever closing it.
        api.streamFrames = listOf("""{"kind":"tool","name":"Read","input":{}}""")
        api.streamHoldsOpen = true
        val repo = SessionsRepository(api, repoScope)

        val flow = repo.transcript("s1")
        runCurrent()

        assertTrue("a generating turn keeps busy=true", flow.value.busy)
        repoScope.cancel()
    }


    @Test fun `terminal session does not open a stream`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(terminalSession())
        val repo = SessionsRepository(api, repoScope)

        repo.transcript("s1")
        advanceUntilIdle()

        assertEquals(0, api.streamCallCount)
    }


    @Test fun `transcript called twice returns the same StateFlow instance`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(terminalSession())
        val repo = SessionsRepository(api, repoScope)

        val first = repo.transcript("s1")
        val second = repo.transcript("s1")
        advanceUntilIdle()

        assertSame(first, second)
        assertEquals(1, api.sessionEventsCallCount("s1"))
    }


    @Test fun `followUp returns the since`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionDetails["s1"] = SessionDetail(terminalSession(), emptyList())
        api.followUpSince = 42
        val repo = SessionsRepository(api, repoScope)

        val r = repo.followUp("s1", "next", setTitle = true)
        advanceUntilIdle()

        assertTrue("expected Success but got $r", r is Outcome.Success)
        assertEquals(42, (r as Outcome.Success<Int>).value)
        assertEquals(Triple("s1", "next", true), api.followUpCalls.single())
    }

    @Test fun `followUp opens a stream when no stream job is active`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(terminalSession())
        val repo = SessionsRepository(api, repoScope)

        val flow = repo.transcript("s1")
        advanceUntilIdle()
        assertEquals(0, api.streamCallCount)

        // script a running response for that poll, then a terminal response for the loop's post-stream
        // refetch (whose events persist the streamed answer) so the loop converges.
        api.followUpSince = 7
        api.streamFrames = listOf("""{"kind":"result","text":"followup-answer"}""")
        api.sessionEventsScript["s1"] = mutableListOf(
            Result.success(eventsResponse(runningSession())),
            Result.success(eventsResponse(terminalSession(), events = seedEvents + answerEvent)),
        )
        repo.followUp("s1", "more", setTitle = false)
        advanceUntilIdle()

        assertEquals(1, api.streamCallCount)
        assertEquals(7, api.streamSinceArgs.last())
        assertTrue(flow.value.nodes.any { it is AnswerNode })
    }


    @Test fun `sessionsStream keeps last good list when api sessions throws on a tick`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        val good = listOf(runningSession("a"))
        val newer = listOf(runningSession("a"), runningSession("b"))
        api.sessionsScript = mutableListOf(
            Result.success(good),
            Result.failure(java.io.IOException("blip")),
            Result.success(newer),
        )
        val repo = SessionsRepository(api, repoScope)

        val emissions = repo.sessionsStream().take(3).toList()

        assertEquals(good, emissions[0])
        assertEquals("a thrown tick re-emits the last good list", good, emissions[1])
        assertEquals(newer, emissions[2])
    }


    @Test fun `sessionsStreamWithState emits FirstLoadError when first tick fails`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionsScript = mutableListOf(
            Result.failure(java.io.IOException("server down")),
        )
        val repo = SessionsRepository(api, repoScope)

        val emissions = repo.sessionsStreamWithState().take(1).toList()

        assertEquals(1, emissions.size)
        assertTrue(
            "expected FirstLoadError but got ${emissions[0]}",
            emissions[0] is SessionsLoadState.FirstLoadError,
        )
    }

    @Test fun `sessionsStreamWithState emits Loaded when tick succeeds`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        val sessions = listOf(runningSession("a"))
        api.sessionsResult = sessions
        val repo = SessionsRepository(api, repoScope)

        val emissions = repo.sessionsStreamWithState().take(1).toList()

        assertEquals(1, emissions.size)
        val loaded = emissions[0] as? SessionsLoadState.Loaded
        assertNotNull("expected Loaded", loaded)
        assertEquals(sessions, loaded?.sessions)
    }

    @Test fun `sessionsStreamWithState later blip does NOT emit FirstLoadError again`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        val good = listOf(runningSession("a"))
        api.sessionsScript = mutableListOf(
            Result.success(good),
            Result.failure(java.io.IOException("blip")),
            Result.success(good),
        )
        val repo = SessionsRepository(api, repoScope)

        // as a null → no FirstLoadError emitted since lastGood is already non-null.
        val loadedEmissions = repo.sessionsStreamWithState()
            .filterIsInstance<SessionsLoadState.Loaded>()
            .take(2)
            .toList()

        assertEquals(2, loadedEmissions.size)
        assertEquals(good, loadedEmissions[0].sessions)
        assertEquals(good, loadedEmissions[1].sessions)
    }


    @Test fun `live stream end refreshes session to terminal clears busy and stops`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        // After the stream returns the loop re-fetches a TERMINAL session whose log persisted the
        // streamed answer → session refreshed, busy cleared, ended set, loop breaks.
        api.sessionEventsScript["s1"] = mutableListOf(
            Result.success(eventsResponse(runningSession().copy(awaitingInput = false))),
            Result.success(eventsResponse(terminalSession(), events = seedEvents + answerEvent)),
        )
        api.streamFrames = listOf("""{"kind":"result","text":"done"}""")
        val repo = SessionsRepository(api, repoScope)

        val flow = repo.transcript("s1")
        advanceUntilIdle()

        val st = flow.value
        assertEquals("stream opened exactly once (terminal after first end)", 1, api.streamCallCount)
        assertEquals("session refreshed to terminal status", "done", st.session?.status)
        assertTrue("engineExit/terminal sets ended", st.ended)
        assertFalse("terminal clears busy even without a result frame", st.busy)
        assertTrue("reseeded nodes include the persisted answer", st.nodes.any { it is AnswerNode })
    }

    @Test fun `busy is cleared on terminal even when stream closed with no result frame`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        // The loop's terminal refetch must still clear busy.
        api.sessionEventsScript["s1"] = mutableListOf(
            Result.success(eventsResponse(runningSession().copy(awaitingInput = false))),
            Result.success(eventsResponse(terminalSession(id = "s1").copy(status = "killed"))),
        )
        api.streamFrames = listOf("""{"kind":"tool","name":"Read","input":{}}""")
        val repo = SessionsRepository(api, repoScope)

        val flow = repo.transcript("s1")
        advanceUntilIdle()

        assertFalse("killed/terminal clears stuck busy", flow.value.busy)
        assertTrue(flow.value.ended)
        assertEquals("killed", flow.value.session?.status)
    }


    @Test fun `dropped stream on a live session reconnects then converges`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        // the loop backs off and reconnects. The second connection returns; session() is then
        // terminal, so the loop stops. Asserts stream was called >1x then converged.
        api.streamScript.addAll(
            listOf(
                FakeAgenticApi.StreamStep(throws = java.io.IOException("socket drop")),
                FakeAgenticApi.StreamStep(frames = listOf("""{"kind":"result","text":"done"}""")),
            )
        )
        api.sessionEventsScript["s1"] = mutableListOf(
            Result.success(eventsResponse(runningSession())),
            Result.success(eventsResponse(runningSession())),
            Result.success(eventsResponse(terminalSession(), events = seedEvents + answerEvent)),
        )
        val repo = SessionsRepository(api, repoScope)

        val flow = repo.transcript("s1")
        runCurrent()
        assertEquals("first stream attempt happened", 1, api.streamCallCount)
        assertFalse("not terminal yet", flow.value.ended)

        advanceTimeBy(2_000L)
        runCurrent()

        assertTrue("stream reopened after the drop", api.streamCallCount >= 2)
        assertTrue("session converged to terminal", flow.value.ended)
        assertEquals("done", flow.value.session?.status)
    }

    @Test fun `a network-available signal wakes the reconnect loop without waiting out the backoff`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        val network = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        // reconnect immediately, WITHOUT advancing the backoff timer; the 2nd stream then converges.
        api.streamScript.addAll(
            listOf(
                FakeAgenticApi.StreamStep(throws = java.io.IOException("socket drop")),
                FakeAgenticApi.StreamStep(frames = listOf("""{"kind":"result","text":"done"}""")),
            )
        )
        api.sessionEventsScript["s1"] = mutableListOf(
            Result.success(eventsResponse(runningSession())),
            Result.success(eventsResponse(runningSession())),
            Result.success(eventsResponse(terminalSession(), events = seedEvents + answerEvent)),
        )
        val repo = SessionsRepository(api, repoScope, networkAvailable = network)

        val flow = repo.transcript("s1")
        runCurrent()
        assertEquals("first stream attempt happened", 1, api.streamCallCount)
        assertFalse("not terminal yet", flow.value.ended)

        network.emit(Unit)
        runCurrent()

        assertTrue("network signal reconnected the stream", api.streamCallCount >= 2)
        assertTrue("session converged to terminal", flow.value.ended)
    }


    @Test fun `kill cancels the stream job and evicts the entry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(runningSession())
        api.streamFrames = emptyList()
        api.streamHoldsOpen = true
        val repo = SessionsRepository(api, repoScope)

        val first = repo.transcript("s1")
        runCurrent()
        assertEquals("load ran once", 1, api.sessionEventsCallCount("s1"))
        assertEquals("stream opened and is held open", 1, api.streamCallCount)

        repo.kill("s1")
        advanceUntilIdle()

        assertEquals(listOf("s1"), api.killCalls)
        assertEquals("no reconnect after kill", 1, api.streamCallCount)
        assertEquals("no extra session refetch after kill", 1, api.sessionEventsCallCount("s1"))

        // load — proving the old view/reducer/job were dropped (not the cached instance). Make the
        // fresh load terminal so it opens no stream (keeps the assertion bounded).
        api.streamHoldsOpen = false
        api.sessionEventsResult = eventsResponse(terminalSession())
        val second = repo.transcript("s1")
        assertNotSame("a fresh flow instance after eviction", first, second)
        assertTrue("fresh flow starts connecting again", second.value.connecting)
        advanceUntilIdle()
        assertEquals("kill evicted the entry, so a second load ran", 2, api.sessionEventsCallCount("s1"))
    }

    // The reported bug: a running session goes silent after the app is backgrounded and returns, and
    // ONLY a force-kill + reopen of the whole app restores it. Root cause: a stream coroutine blocked
    // reading a half-open socket stays Job.isActive==true, and the OLD refresh() merely re-ran load(),
    // whose openStream() early-returns while isActive — so the dead stream was never replaced. refresh()
    // now cancels the stuck job FIRST. A held-open fake stream models the stuck socket: it never returns
    // on its own, so the ONLY way streamCallCount reaches 2 is the forced reconnect tearing job #1 down
    // (otherwise the isActive guard blocks the reopen and it stays 1 — the bug).

    @Test fun `refresh force-reconnects a stuck stream cancelling the old job and opening a fresh one`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(runningSession())
        api.streamHoldsOpen = true
        val repo = SessionsRepository(api, repoScope)

        repo.transcript("s1")
        advanceUntilIdle()
        assertEquals(1, api.streamCallCount)

        repo.refresh("s1")
        advanceUntilIdle()

        assertEquals("forced reconnect opened a fresh stream (old job was cancelled)", 2, api.streamCallCount)
        assertEquals("the fresh stream resumes from the reseeded cursor", seedEvents.size, api.streamSinceArgs.last())
        assertEquals("refresh refetched the authoritative session", 2, api.sessionEventsCallCount("s1"))
        repoScope.cancel()
    }

    @Test fun `refresh coalesces concurrent triggers into a single reconnect`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(runningSession())
        api.streamHoldsOpen = true
        val repo = SessionsRepository(api, repoScope)

        repo.transcript("s1")
        advanceUntilIdle()
        assertEquals(1, api.streamCallCount)

        // them). The second must coalesce into the first via `reconnecting`, so exactly ONE fresh stream
        // opens — not two reconnects fighting over the same job (which would reach 3).
        repo.refresh("s1")
        repo.refresh("s1")
        advanceUntilIdle()

        assertEquals("coalesced: only one fresh stream opened", 2, api.streamCallCount)
        repoScope.cancel()
    }

    // Stops the per-session WebSocket leak: every opened session used to keep a persistent stream +
    // reconnect loop forever (released only on kill/delete). With idleReleaseMs set, a session whose UI
    // has had ZERO subscribers for that long is released; re-opening just re-creates + reseeds. The
    // unit-test default (idleReleaseMs = null) disables this, so the rest of the suite is unaffected.

    @Test fun `an idle transcript stream is released after the configured idle window`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(runningSession())
        api.streamHoldsOpen = true
        val repo = SessionsRepository(api, repoScope, idleReleaseMs = 30_000L)

        val first = repo.transcript("s1")
        val sub = backgroundScope.launch { first.collect {} }  // a live subscriber (mirrors the detail screen)
        runCurrent()                                           // establish the subscription → reaper idle-timer cancelled
        advanceUntilIdle()
        assertEquals("stream opened while observed", 1, api.streamCallCount)

        sub.cancel()
        runCurrent()                                           // let the unsubscribe propagate → reaper starts its timer
        advanceTimeBy(29_000L); runCurrent()
        assertSame("not reaped before the idle window", first, repo.transcript("s1"))

        advanceTimeBy(2_000L); runCurrent()
        api.streamHoldsOpen = false
        api.sessionEventsResult = eventsResponse(terminalSession())
        val second = repo.transcript("s1")
        assertNotSame("idle stream released → a fresh flow on re-open", first, second)
        advanceUntilIdle()
        assertEquals("re-open ran a second load", 2, api.sessionEventsCallCount("s1"))
        repoScope.cancel()
    }

    @Test fun `a returning subscriber before the idle window cancels the pending release`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(runningSession())
        api.streamHoldsOpen = true
        val repo = SessionsRepository(api, repoScope, idleReleaseMs = 30_000L)

        val first = repo.transcript("s1")
        val sub1 = backgroundScope.launch { first.collect {} }
        runCurrent(); advanceUntilIdle()
        sub1.cancel(); runCurrent()
        advanceTimeBy(20_000L); runCurrent()
        val sub2 = backgroundScope.launch { first.collect {} }
        runCurrent()
        advanceTimeBy(60_000L); runCurrent()
        assertSame("a returning subscriber keeps the session warm", first, repo.transcript("s1"))
        assertEquals("no extra load — the same warm flow was reused", 1, api.sessionEventsCallCount("s1"))
        sub2.cancel(); repoScope.cancel()
    }


    // reconnectLiveSessions only reconnects sessions with a LIVE UI subscriber (an off-screen/idle one
    // would just be reaped 30s later), so these tests keep a subscriber attached — which also makes the
    // terminal case prove the TERMINAL skip rather than a 0-subscriber skip.

    @Test fun `reconnectLiveSessions force-reconnects every live session`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(runningSession())
        api.streamHoldsOpen = true
        val repo = SessionsRepository(api, repoScope)

        val flow = repo.transcript("s1")
        val sub = backgroundScope.launch { flow.collect {} }   // the session is on screen
        advanceUntilIdle()
        assertEquals(1, api.streamCallCount)

        repo.reconnectLiveSessions()   // app returned to the foreground
        advanceUntilIdle()

        assertEquals("a live, on-screen session's stream is force-reconnected on foreground", 2, api.streamCallCount)
        sub.cancel(); repoScope.cancel()
    }

    @Test fun `reconnectLiveSessions skips terminal sessions`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(terminalSession())  // terminal → opens no stream
        val repo = SessionsRepository(api, repoScope)

        val flow = repo.transcript("s1")
        val sub = backgroundScope.launch { flow.collect {} }   // subscribed, so the skip is due to TERMINAL
        advanceUntilIdle()
        assertEquals(0, api.streamCallCount)

        repo.reconnectLiveSessions()
        advanceUntilIdle()
        assertEquals("a terminal session is not reconnected", 0, api.streamCallCount)
        sub.cancel()
    }

    // Root fix for the stale error banner (and the ~18 sibling stale-session surfaces): TranscriptState
    // .session was refreshed ONLY on load/reseed, so it stayed frozen for a whole live turn. refreshSession
    // swaps in the authoritative server row (session field only — never nodes); sessionRefreshStream polls
    // it every 2s while observed. Must reflect REAL server state (no optimistic clearing).

    private fun erroredRunning(id: String = "s1") =
        Session(id = id, prompt = "go", status = "running", errorKind = "claude_error", error = "boom")

    @Test fun `refreshSession swaps in the fresh session row without touching nodes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(erroredRunning())
        api.streamHoldsOpen = true                              // live turn: stream stays open, never reseeds
        val repo = SessionsRepository(api, repoScope)
        val flow = repo.transcript("s1")
        advanceUntilIdle(); runCurrent()
        assertEquals("claude_error", flow.value.session?.errorKind)
        val nodesBefore = flow.value.nodes

        api.sessionEventsResult = eventsResponse(runningSession())   // server cleared the error
        repo.refreshSession("s1"); runCurrent()

        assertEquals("session refreshed to the cleared row", null, flow.value.session?.errorKind)
        assertEquals("nodes are owned by the stream, untouched by refresh", nodesBefore, flow.value.nodes)
        repoScope.cancel()
    }

    @Test fun `refreshSession does not downgrade a terminal session (reseed wins over a late poll)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(terminalSession())  // terminal → opens no stream
        val repo = SessionsRepository(api, repoScope)
        val flow = repo.transcript("s1")
        advanceUntilIdle()
        assertEquals("done", flow.value.session?.status)
        api.sessionEventsResult = eventsResponse(runningSession())   // a stale/late running row
        repo.refreshSession("s1"); runCurrent()
        assertEquals("terminal guard prevents the downgrade", "done", flow.value.session?.status)
    }

    // Recovery: an errored terminal session (failed/usage_limit) has the user click Resume; the
    // server's queued follow_up branch patches the row to status=pending with error/errorKind
    // cleared. The 2s refresh poll must surface that so the error banner drops immediately, not
    // wait for the turn to actually start. Mirrors the user's reported scenario (5f20c824-style
    // sessions that "still show couldn't resume try again" after a successful retry).
    @Test fun `refreshSession allows terminal-to-non-terminal for an errored session (recovery path)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        val failedSession = Session(
            id = "s1", prompt = "go", status = "failed",
            claudeSessionId = "claude-1", errorKind = "usage_limit",
            error = "session limit reached",
        )
        api.sessionEventsResult = eventsResponse(failedSession)
        val repo = SessionsRepository(api, repoScope)
        val flow = repo.transcript("s1")
        advanceUntilIdle()
        assertEquals("failed", flow.value.session?.status)
        assertEquals("usage_limit", flow.value.session?.errorKind)

        val pendingSession = Session(
            id = "s1", prompt = "go", status = "running",
            claudeSessionId = "claude-1", awaitingInput = false,
        )
        api.sessionEventsResult = eventsResponse(pendingSession)
        repo.refreshSession("s1"); runCurrent()

        assertEquals(
            "the recovery transition (failed/usage_limit → running/no-error) must land in the flow",
            "running", flow.value.session?.status,
        )
        assertNull(
            "errorKind must be cleared by the refresh after the queued branch patched it",
            flow.value.session?.errorKind,
        )
        repoScope.cancel()
    }

    @Test fun `refreshSession keeps the last-good session when the fetch fails`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(erroredRunning())
        api.streamHoldsOpen = true
        val repo = SessionsRepository(api, repoScope)
        val flow = repo.transcript("s1")
        advanceUntilIdle(); runCurrent()
        assertEquals("claude_error", flow.value.session?.errorKind)
        api.sessionEventsException = java.io.IOException("down")
        repo.refreshSession("s1"); runCurrent()
        assertEquals("a failed refresh keeps the prior session (no null wipe)",
            "claude_error", flow.value.session?.errorKind)
        repoScope.cancel()
    }

    @Test fun `sessionRefreshStream polls the session row roughly every 2s`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(runningSession())
        api.streamHoldsOpen = true
        val repo = SessionsRepository(api, repoScope)
        repo.transcript("s1")
        advanceUntilIdle(); runCurrent()
        val afterLoad = api.sessionEventsCallCount("s1")
        val job = repoScope.launch { repo.sessionRefreshStream("s1").collect {} }
        runCurrent()                                            // pollFlow emits immediately (tick 0)
        advanceTimeBy(2_000L); runCurrent()
        advanceTimeBy(2_000L); runCurrent()
        val extra = api.sessionEventsCallCount("s1") - afterLoad
        assertTrue("expected the 2s poll to add sessionEvents() calls, got $extra", extra >= 2)
        job.cancel(); repoScope.cancel()
    }


    @Test fun `contentSearch emits response for non blank query`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.searchHandler = { q -> if (q == "build") SearchResponse("build", emptyList()) else null }
        val repo = SessionsRepository(api, repoScope)

        val query = MutableStateFlow("")
        query.value = "build"
        val first = repo.contentSearch(query)
            .filterIsInstance<Outcome.Success<SearchResponse>>()
            .first()

        assertEquals("build", first.value.query)
    }

    // Verifies last-write-wins: a NEW query while an older one is still in flight cancels the old
    // mapLatest block. We gate the "first" handler on an inflight Deferred so the test can prove the
    // older call was interrupted — otherwise it would have raced to emit and the cancellation would
    // be invisible.
    //
    // Setup: the StateFlow starts at "first" so the pipeline sees that distinct value BEFORE we set
    // "second"; `advanceTimeBy(300)` lets "first" pass debounce and reach `api.searchSessions`, where
    // the handler parks on `inflight.await()`. Only THEN do we set query="second" and advance time —
    // mapLatest cancels the parked "first" call (proved via the catch arm setting `firstCancelled`),
    // then "second" runs to completion. The fresh `contentSearch(MutableStateFlow("second"))` is just
    // an extra downstream assertion that the latest query wins.
    @Test fun `contentSearch only latest query wins`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        val inflight = CompletableDeferred<Unit>()
        var firstCancelled = false
        api.searchHandler = { q ->
            when (q) {
                "first" -> {
                    try { inflight.await(); SearchResponse("first", emptyList()) }
                    catch (ce: CancellationException) { firstCancelled = true; throw ce }
                }
                "second" -> SearchResponse("second", listOf(SearchHit(Session(id = q, prompt = "p"), 1f, emptyList())))
                else -> SearchResponse(q, emptyList())
            }
        }
        val repo = SessionsRepository(api, repoScope)

        val query = MutableStateFlow("first")
        val firstJob = backgroundScope.launch { repo.contentSearch(query).collect { /* keep open */ } }
        advanceTimeBy(300) // let "first" pass debounce and reach the api call
        runCurrent()      // give the suspending inflight.await() a chance to park
        query.value = "second"
        advanceTimeBy(300)
        runCurrent()

        assertTrue(
            "expected the in-flight 'first' call to be cancelled by the newer 'second' query",
            firstCancelled,
        )

        val second = repo.contentSearch(MutableStateFlow("second"))
            .filterIsInstance<Outcome.Success<SearchResponse>>()
            .first()
        assertEquals("second", second.value.query)

        inflight.complete(Unit)  // unblock if not yet cancelled, for cleanup
        firstJob.cancel()
    }
}
