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

/**
 * Unit tests for [SessionsRepository].
 *
 * We inject a [CoroutineScope] backed by a [StandardTestDispatcher] tied to the test's scheduler so
 * the repo's `scope.launch { load(id) }` is queued (not run eagerly). That lets us observe the
 * synchronous `connecting=true` first emission BEFORE `runCurrent()`/`advanceUntilIdle()` drives the
 * load. The [FakeAgenticApi.stream] replays scripted frames synchronously into onLine, so once the
 * load coroutine runs the whole stream is applied deterministically (no real WS, no wall-clock).
 *
 * The stream engine is a RECONNECT LOOP: after each `api.stream` returns it re-fetches `api.session`
 * and breaks only when the refreshed status is terminal. So every test that drives a NON-terminal
 * session with `advanceUntilIdle()` must make the post-stream `session()` converge to terminal (via
 * [FakeAgenticApi.sessionScript] or a terminal [FakeAgenticApi.sessionDetails] entry) — otherwise the
 * loop would retry forever. Reconnect timing is stepped with `advanceTimeBy`, never `advanceUntilIdle`
 * on a still-looping session.
 */
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

    // ── sessionEvents() migration helpers ────────────────────────────────────────
    // The repo migrated its load/refetch/refresh paths from api.session(id) (SessionDetail + raw JSONL
    // `log`) to api.sessionEvents(id, …) (SessionEventsResponse + structured `events`). These are the
    // events-surface equivalents of [seedLog]/[toolLogEntry]/[answerLogEntry]: kind-tagged wire objects
    // that seedFromEvents folds into the SAME nodes buildFromLog produced for the old log lines, so the
    // reducer-equivalence assertions (e.g. `nodes == seedFromLog(seedLog).display()`) still hold.
    private fun ev(json: String): JsonElement = Json.parseToJsonElement(json)

    /** Structured-event twin of [seedLog]: prompt "go" @1 + result "ok" → PromptNode + AnswerNode. */
    private val seedEvents: List<JsonElement> = listOf(
        ev("""{"kind":"prompt","text":"go","at":1}"""),
        ev("""{"kind":"result","text":"ok"}"""),
    )
    /** Structured-event twin of [toolLogEntry]: a Read ToolNode. */
    private val toolEvent: JsonElement = ev("""{"kind":"tool","name":"Read","input":{"file_path":"/a/B.kt"}}""")
    /** Structured-event twin of [answerLogEntry]: an AnswerNode("done"). */
    private val answerEvent: JsonElement = ev("""{"kind":"result","text":"done"}""")

    /** Build a [SessionEventsResponse] the migrated load/refetch/refresh paths consume. `latestEventId`
     *  defaults to the event count (the live-stream resume cursor the repo opens `since` from); override
     *  it (with `firstEventLine`) to model a windowed load whose cursor is an offset into the FULL log. */
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

    /** How many times the migrated load/refetch/refresh paths hit api.sessionEvents for [id] — the
     *  events-surface twin of the old `api.sessionCalls[id]` counter. */
    private fun FakeAgenticApi.sessionEventsCallCount(id: String): Int = sessionEventsCalls.count { it == id }

    @Before fun setUp() {
        api = FakeAgenticApi()
    }

    @After fun tearDown() {
        if (::repoScope.isInitialized) repoScope.cancel()
    }

    // ── 0. composer draft persistence ───────────────────────────────────────────

    @Test fun `draftFor falls back to the persisted disk draft after the in-memory cache is gone`() {
        repoScope = CoroutineScope(StandardTestDispatcher())
        val settings = FakeSettingsStore()
        // Type a draft, then simulate process death: a fresh repo has an empty in-memory map but
        // shares the same persisted store, so the draft is recovered from disk.
        SessionsRepository(api, repoScope, settings).setDraft("s1", "half-typed message")
        assertEquals("half-typed message", SessionsRepository(api, repoScope, settings).draftFor("s1"))
        // Sending / removing the session clears the draft from disk too.
        SessionsRepository(api, repoScope, settings).clearDraft("s1")
        assertEquals("", SessionsRepository(api, repoScope, settings).draftFor("s1"))
    }

    // ── 1. connecting → loaded ──────────────────────────────────────────────────

    @Test fun `transcript first emits connecting then a loaded state matching reducer display`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(terminalSession())
        val repo = SessionsRepository(api, repoScope)

        val flow = repo.transcript("s1")
        // Before the launched load runs, the flow holds the initial connecting state.
        assertTrue("expected connecting=true initially", flow.value.connecting)

        advanceUntilIdle()

        val loaded = flow.value
        assertFalse(loaded.connecting)
        assertNotNull(loaded.session)
        assertEquals("s1", loaded.session?.id)
        // nodes equal what a reducer seeded with the same log would display.
        val expected = TranscriptReducer().apply { seedFromLog(seedLog) }.display()
        assertEquals(expected, loaded.nodes)
    }

    @Test fun `first-load failure sets loadError (no permanent blank) and reload recovers`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsException = java.io.IOException("server down")   // the initial load fails
        val repo = SessionsRepository(api, repoScope)
        val flow = repo.transcript("s1")
        advanceUntilIdle()
        // A failed first load must NOT leave the screen stuck connecting/blank — it flags a retryable error.
        assertFalse("must not be stuck connecting", flow.value.connecting)
        assertTrue("first-load failure must set loadError", flow.value.loadError)
        assertTrue("no session yet", flow.value.session == null)
        // Server recovers; reload() re-runs the load on the SAME cached flow.
        api.sessionEventsException = null
        api.sessionEventsResult = eventsResponse(terminalSession())
        repo.reload("s1")
        advanceUntilIdle()
        assertFalse("loadError clears after a successful reload", flow.value.loadError)
        assertNotNull("session loaded after reload", flow.value.session)
        assertEquals("s1", flow.value.session?.id)
    }

    // ── 2. non-terminal opens stream; frames append + flip busy ─────────────────

    @Test fun `non-terminal session opens stream and frames append nodes and flip busy`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        // load() sees a running session and opens the stream; after the stream returns the loop
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
        assertEquals(seedEvents.size, api.streamSinceArgs.first())  // streamed from since=latestEventId (== log size)
        assertTrue("tool frame should add a ToolNode", st.nodes.any { it is ToolNode })
        assertTrue("result frame should add an AnswerNode", st.nodes.any { it is AnswerNode })
        assertFalse("result frame sets busy=false", st.busy)
    }

    @Test fun `windowed load opens the stream from total, not the tail size`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        // The server returned only the LAST `seedLog.size` rendered lines of a longer transcript
        // (windowed ?limit path): start=17, total=seedLog.size+17. The reducer seeds from the tail, but
        // the live cursor is a rendered-offset into the FULL log — so the stream must resume at `total`,
        // NOT `log.size`, or it would re-stream 17 lines of already-shown history.
        val total = seedEvents.size + 17
        // The events response carries only the LAST `seedEvents.size` events (the tail window), but its
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

        assertEquals(total, api.streamSinceArgs.first())                     // resumes at total, not tail size
        assertEquals(SessionsRepository.INITIAL_LOG_LIMIT, api.sessionEventsLimitArgs.first())  // load passed the window limit
    }

    @Test fun `busy is true while a tool turn is generating before result`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(runningSession())
        // Only a tool frame, no result. The stream then keeps the socket open (never returns) so the
        // loop does NOT advance to the refetch — busy stays true. We step the stream open via
        // runCurrent (load + open) without ever closing it.
        api.streamFrames = listOf("""{"kind":"tool","name":"Read","input":{}}""")
        api.streamHoldsOpen = true
        val repo = SessionsRepository(api, repoScope)

        val flow = repo.transcript("s1")
        runCurrent()  // run load() + open the stream (which replays the tool frame then suspends open)

        assertTrue("a generating turn keeps busy=true", flow.value.busy)
        repoScope.cancel()  // stop the held-open stream so the test ends bounded
    }

    // ── 3. terminal session does NOT open a stream ──────────────────────────────

    @Test fun `terminal session does not open a stream`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(terminalSession())
        val repo = SessionsRepository(api, repoScope)

        repo.transcript("s1")
        advanceUntilIdle()

        assertEquals(0, api.streamCallCount)
    }

    // ── 4. transcript(id) cache: same StateFlow instance ────────────────────────

    @Test fun `transcript called twice returns the same StateFlow instance`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionEventsResult = eventsResponse(terminalSession())
        val repo = SessionsRepository(api, repoScope)

        val first = repo.transcript("s1")
        val second = repo.transcript("s1")
        advanceUntilIdle()

        assertSame(first, second)
        // Reopening did not trigger a second load.
        assertEquals(1, api.sessionEventsCallCount("s1"))
    }

    // ── 5. followUp returns since and opens a stream when none is active ─────────

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
        // Terminal session: load() does NOT open a stream, so there's no active job.
        api.sessionEventsResult = eventsResponse(terminalSession())
        val repo = SessionsRepository(api, repoScope)

        val flow = repo.transcript("s1")
        advanceUntilIdle()
        assertEquals(0, api.streamCallCount)  // no stream from load

        // The follow-up turn streams in place. followUp polls sessionEvents() until non-terminal, so
        // script a running response for that poll, then a terminal response for the loop's post-stream
        // refetch (whose events persist the streamed answer) so the loop converges.
        api.followUpSince = 7
        api.streamFrames = listOf("""{"kind":"result","text":"followup-answer"}""")
        api.sessionEventsScript["s1"] = mutableListOf(
            Result.success(eventsResponse(runningSession())),                              // followUp poll
            Result.success(eventsResponse(terminalSession(), events = seedEvents + answerEvent)), // post-stream
        )
        repo.followUp("s1", "more", setTitle = false)
        advanceUntilIdle()

        assertEquals(1, api.streamCallCount)
        assertEquals(7, api.streamSinceArgs.last())
        assertTrue(flow.value.nodes.any { it is AnswerNode })
    }

    // ── 6. sessionsStream keeps last-good list when a tick throws ────────────────

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

    // ── 6b. sessionsStreamWithState: PR-9 first-load error flag ────────────────────

    @Test fun `sessionsStreamWithState emits FirstLoadError when first tick fails`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        api.sessionsScript = mutableListOf(
            Result.failure(java.io.IOException("server down")),
        )
        val repo = SessionsRepository(api, repoScope)

        // take(1): the very first emission should be FirstLoadError.
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
        // First tick succeeds, second tick fails (blip).
        api.sessionsScript = mutableListOf(
            Result.success(good),
            Result.failure(java.io.IOException("blip")),
            Result.success(good),  // third tick recovers
        )
        val repo = SessionsRepository(api, repoScope)

        // Collect 2 Loaded emissions (ticks 1 and 3); the blip (tick 2) should be swallowed
        // as a null → no FirstLoadError emitted since lastGood is already non-null.
        val loadedEmissions = repo.sessionsStreamWithState()
            .filterIsInstance<SessionsLoadState.Loaded>()
            .take(2)
            .toList()

        assertEquals(2, loadedEmissions.size)
        assertEquals(good, loadedEmissions[0].sessions)
        assertEquals(good, loadedEmissions[1].sessions)
    }

    // ── 7. live stream ends → loop re-fetches a terminal session (Bugs 1+2+3) ────

    @Test fun `live stream end refreshes session to terminal clears busy and stops`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        // Initial load: a running session (so stream opens) that is busy (awaitingInput=false).
        // After the stream returns the loop re-fetches a TERMINAL session whose log persisted the
        // streamed answer → session refreshed, busy cleared, ended set, loop breaks.
        api.sessionEventsScript["s1"] = mutableListOf(
            Result.success(eventsResponse(runningSession().copy(awaitingInput = false))),
            Result.success(eventsResponse(terminalSession(), events = seedEvents + answerEvent)),
        )
        api.streamFrames = listOf("""{"kind":"result","text":"done"}""")
        val repo = SessionsRepository(api, repoScope)

        val flow = repo.transcript("s1")
        advanceUntilIdle()  // bounded: post-stream session is terminal, so the loop stops

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
        // Bug 3: a turn that was busy, then the stream drops (engine crash / Stop) with NO result.
        // The loop's terminal refetch must still clear busy.
        api.sessionEventsScript["s1"] = mutableListOf(
            Result.success(eventsResponse(runningSession().copy(awaitingInput = false))),
            Result.success(eventsResponse(terminalSession(id = "s1").copy(status = "killed"))),
        )
        api.streamFrames = listOf("""{"kind":"tool","name":"Read","input":{}}""")  // busy, no result
        val repo = SessionsRepository(api, repoScope)

        val flow = repo.transcript("s1")
        advanceUntilIdle()

        assertFalse("killed/terminal clears stuck busy", flow.value.busy)
        assertTrue(flow.value.ended)
        assertEquals("killed", flow.value.session?.status)
    }

    // ── 8. reconnect: stream drops on a still-live session, loop reopens ─────────

    @Test fun `dropped stream on a live session reconnects then converges`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        // First stream connection throws (transport drop). After it, session() is STILL running, so
        // the loop backs off and reconnects. The second connection returns; session() is then
        // terminal, so the loop stops. Asserts stream was called >1x then converged.
        api.streamScript.addAll(
            listOf(
                FakeAgenticApi.StreamStep(throws = java.io.IOException("socket drop")),
                FakeAgenticApi.StreamStep(frames = listOf("""{"kind":"result","text":"done"}""")),
            )
        )
        api.sessionEventsScript["s1"] = mutableListOf(
            Result.success(eventsResponse(runningSession())),                                 // load
            Result.success(eventsResponse(runningSession())),                                 // after drop: still live
            Result.success(eventsResponse(terminalSession(), events = seedEvents + answerEvent)),// after reopen: terminal
        )
        val repo = SessionsRepository(api, repoScope)

        val flow = repo.transcript("s1")
        runCurrent()  // load + first stream attempt (throws) + first refetch (still running)
        assertEquals("first stream attempt happened", 1, api.streamCallCount)
        assertFalse("not terminal yet", flow.value.ended)

        advanceTimeBy(2_000L)  // step past the 1s backoff so the loop reconnects
        runCurrent()

        assertTrue("stream reopened after the drop", api.streamCallCount >= 2)
        assertTrue("session converged to terminal", flow.value.ended)
        assertEquals("done", flow.value.session?.status)
    }

    @Test fun `a network-available signal wakes the reconnect loop without waiting out the backoff`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        val network = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        // First stream drops; still-live → the loop enters backoff. A network-available signal must
        // reconnect immediately, WITHOUT advancing the backoff timer; the 2nd stream then converges.
        api.streamScript.addAll(
            listOf(
                FakeAgenticApi.StreamStep(throws = java.io.IOException("socket drop")),
                FakeAgenticApi.StreamStep(frames = listOf("""{"kind":"result","text":"done"}""")),
            )
        )
        api.sessionEventsScript["s1"] = mutableListOf(
            Result.success(eventsResponse(runningSession())),                                 // load
            Result.success(eventsResponse(runningSession())),                                 // after drop: still live
            Result.success(eventsResponse(terminalSession(), events = seedEvents + answerEvent)),// after wake: terminal
        )
        val repo = SessionsRepository(api, repoScope, networkAvailable = network)

        val flow = repo.transcript("s1")
        runCurrent()  // load + first stream (drops) + refetch (still live) → now waiting in backoff
        assertEquals("first stream attempt happened", 1, api.streamCallCount)
        assertFalse("not terminal yet", flow.value.ended)

        network.emit(Unit)   // device regained a network — wake the loop without advancing the backoff timer
        runCurrent()

        assertTrue("network signal reconnected the stream", api.streamCallCount >= 2)
        assertTrue("session converged to terminal", flow.value.ended)
    }

    // ── 9. kill evicts and cancels the stream job ───────────────────────────────

    @Test fun `kill cancels the stream job and evicts the entry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        repoScope = CoroutineScope(dispatcher)
        // A live session whose stream holds open, so a reconnect job is alive when we kill.
        api.sessionEventsResult = eventsResponse(runningSession())
        api.streamFrames = emptyList()
        api.streamHoldsOpen = true
        val repo = SessionsRepository(api, repoScope)

        val first = repo.transcript("s1")
        runCurrent()  // load opens the (held-open) stream loop
        assertEquals("load ran once", 1, api.sessionEventsCallCount("s1"))
        assertEquals("stream opened and is held open", 1, api.streamCallCount)

        repo.kill("s1")
        advanceUntilIdle()  // bounded: with the job cancelled there is no reconnect loop left to run

        assertEquals(listOf("s1"), api.killCalls)
        // Job was cancelled: the held-open stream never returned, so no refetch/reconnect happened.
        assertEquals("no reconnect after kill", 1, api.streamCallCount)
        assertEquals("no extra session refetch after kill", 1, api.sessionEventsCallCount("s1"))

        // Entry was evicted: transcript("s1") now creates a FRESH flow (connecting=true) and a NEW
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

    // ── 9b. refresh() forces a reconnect: cancel a stuck/zombie stream, open a fresh one ─────────
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
        api.streamHoldsOpen = true              // stream #1 holds open forever (the stuck/live socket)
        val repo = SessionsRepository(api, repoScope)

        repo.transcript("s1")
        advanceUntilIdle()                      // load + open stream #1 (parked open)
        assertEquals(1, api.streamCallCount)

        repo.refresh("s1")                      // forced reconnect: cancel job #1, reseed, open stream #2
        advanceUntilIdle()

        assertEquals("forced reconnect opened a fresh stream (old job was cancelled)", 2, api.streamCallCount)
        assertEquals("the fresh stream resumes from the reseeded cursor", seedEvents.size, api.streamSinceArgs.last())
        assertEquals("refresh refetched the authoritative session", 2, api.sessionEventsCallCount("s1"))
        repoScope.cancel()                      // stop the held-open stream #2 so the test ends bounded
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

        // Two triggers fire before the dispatcher runs either launch body (StandardTestDispatcher queues
        // them). The second must coalesce into the first via `reconnecting`, so exactly ONE fresh stream
        // opens — not two reconnects fighting over the same job (which would reach 3).
        repo.refresh("s1")
        repo.refresh("s1")
        advanceUntilIdle()

        assertEquals("coalesced: only one fresh stream opened", 2, api.streamCallCount)
        repoScope.cancel()
    }

    // ── 9c. idle-stream reaper: release a session's stream after its last subscriber leaves ───────
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
        advanceUntilIdle()                                     // load + open the held-open stream
        assertEquals("stream opened while observed", 1, api.streamCallCount)

        // Leave the session: drop the subscriber. Before the idle window elapses it is still warm.
        sub.cancel()
        runCurrent()                                           // let the unsubscribe propagate → reaper starts its timer
        advanceTimeBy(29_000L); runCurrent()
        assertSame("not reaped before the idle window", first, repo.transcript("s1"))

        // Past the idle window with no subscriber → released (flow evicted, stream cancelled).
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
        // Leave, wait part of the window, then return (re-subscribe) — a quick navigate-away-and-back.
        sub1.cancel(); runCurrent()
        advanceTimeBy(20_000L); runCurrent()
        val sub2 = backgroundScope.launch { first.collect {} }
        runCurrent()
        // Even well past the original deadline, the session is NOT released (the return reset the timer).
        advanceTimeBy(60_000L); runCurrent()
        assertSame("a returning subscriber keeps the session warm", first, repo.transcript("s1"))
        assertEquals("no extra load — the same warm flow was reused", 1, api.sessionEventsCallCount("s1"))
        sub2.cancel(); repoScope.cancel()
    }

    // ── 9d. reconnectLiveSessions: the app-foreground (ProcessLifecycle) backstop ─────────────────

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

    // ── refreshSession / sessionRefreshStream: keep the detail-screen session fresh ──────────────
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

        // Server's queued follow_up branch patched the row: errored terminal → clean running.
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

    // ── contentSearch: debounced content search across sessions ───────────────────
    // Per plan Task 5: 250ms debounce, length<2 short-circuit, last-write-wins via mapLatest.

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
