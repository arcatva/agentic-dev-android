package dev.agentic.data.repo

import dev.agentic.data.SettingsStore
import dev.agentic.data.log.AppLog
import dev.agentic.data.net.AgenticApi
import dev.agentic.data.net.AdoptSessionReq
import dev.agentic.data.net.Adoptable
import dev.agentic.data.net.CreateGroupReq
import dev.agentic.data.net.DetachResp
import dev.agentic.data.net.Group
import dev.agentic.data.net.ModelEntry
import dev.agentic.data.net.NewSessionReq
import dev.agentic.data.net.UpdateGroupReq
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.PatchSessionReq
import dev.agentic.data.net.PluginInfo
import dev.agentic.data.net.RepoList
import dev.agentic.data.net.SearchResponse
import dev.agentic.data.net.Session
import dev.agentic.data.net.SessionDetail
import dev.agentic.data.net.SessionEventsResponse
import dev.agentic.data.net.SkillInfo
import dev.agentic.data.net.StagedUpload
import dev.agentic.data.net.Template
import dev.agentic.data.net.Usage
import dev.agentic.data.net.runCatchingOutcome
import dev.agentic.data.util.pollFlow
import dev.agentic.domain.Node
import dev.agentic.domain.TERMINAL
import dev.agentic.domain.TranscriptReducer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** Min interval between live-stream display() emissions during a burst (see openStream). A turn boundary (ended/busy=false) + the post-stream final flush bypass this so the last state is never throttled away — only high-frequency intermediate text deltas are coalesced. */
private const val DISPLAY_THROTTLE_MS = 75L

/** Sealed type for [sessionsStreamWithState]: UI distinguishes first-load failure (server unreachable at launch) from normal updates without changing [sessionsStream]'s shape. */
sealed interface SessionsLoadState {
    data class Loaded(val sessions: List<Session>) : SessionsLoadState
    data object FirstLoadError : SessionsLoadState
}

/** Snapshot of one session's chat/transcript.
 *  - [busy] = turn actively generating (seeded from awaitingInput==false; driven by frameBusy per stream frame).
 *  - [ended] = engineExit frame seen on the live stream.
 *  - [connecting] = initial `api.session(id)` load in flight.
 *  - [loadError] = initial load FAILED (server unreachable/5xx on open); screen shows error + retry via [reload]. */
data class TranscriptState(
    val nodes: List<Node> = emptyList(),
    val session: Session? = null,
    val busy: Boolean = false,
    val ended: Boolean = false,
    val connecting: Boolean = true,
    val loadError: Boolean = false,
    val hasMore: Boolean = false,
    val loadingEarlier: Boolean = false,
    // Bounded-window (terminal sessions): true when newer pages were evicted off the newest end and can
    // be paged back in via [loadNewer]. Drives the transcript's bottom (scroll-forward) auto-load.
    val hasNewer: Boolean = false,
    val loadingNewer: Boolean = false,
    val latestEventId: Long = 0,
    val firstEventLine: Long = 0,
)

/** One fetched page of a terminal session's bounded transcript window. [upper] is the exclusive `before` cursor it was fetched with (GET /events?before=upper); re-querying before=upper on an immutable (ended) log returns this exact page — an evicted page reloads byte-identically with no overlap. [start] is the oldest event's line (the page spans [start, upper)). */
private data class WindowPage(val start: Long, val upper: Long, val events: List<JsonElement>)

/** Descriptor retained for a page evicted off the NEWEST end (its events ARE dropped to free memory). [upper] is the exclusive `before` cursor to reload with; [count] is how many events it held so the reload is sliced via takeLast — bulletproofing against a reload returning more than the original and overlapping the still-resident newer page. */
private data class EvictedPage(val start: Long, val upper: Long, val count: Int)

/**
 * Single source of truth for session-list and per-session transcript state. The [TranscriptReducer]
 * and stream [Job] for each session live HERE, app-scoped, so navigating away and back reuses the
 * same hot [StateFlow] and reducer instantly — no blank reflash, no re-fetch.
 * Concurrency: per-id maps mutate from both UI thread (via [transcript]) and coroutines (via
 * [load]/[openStream]), all guarded by [lock]. At most ONE stream job runs per id at a time
 * (the [openStream] guard) so the reducer is only ever mutated by one coroutine.
 */
class SessionsRepository(
    private val api: AgenticApi,
    private val scope: CoroutineScope,
    private val settings: SettingsStore? = null,
    // Emits when the device regains a network. The reconnect loop waits on this to retry instantly after a
    // Wi-Fi blip instead of sitting out its backoff. Default never emits (so behaviour = plain backoff).
    private val networkAvailable: Flow<Unit> = MutableSharedFlow<Unit>(),
    // How long a transcript's live stream stays warm after its LAST UI subscriber leaves before it is
    // released (WS cancelled + reducer dropped). null = never auto-release (the unit-test default, so
    // the suite's `advanceUntilIdle()` can't trip the reaper's timer). Production passes a real value
    // (see AppContainer) so leaving a session for good stops leaking a persistent socket; returning
    // re-creates + reseeds via transcript()/restart() — one cheap reseed, not a permanent connection.
    private val idleReleaseMs: Long? = null,
    // Hard cap on how many structured events a TERMINAL session's transcript keeps resident while the
    // user pages back and forth. Past this, whole pages are evicted from the far end (a Discord-style
    // bounded window) so scrolling through a huge finished session can't grow memory without bound.
    // Injectable so tests can force eviction with a tiny window. LIVE sessions are never windowed (the
    // stream owns the tail; evicting there would fight the live fold), so this only applies once ended.
    // Sized as a true SLIDING window (~4 pages): small enough that a long ended transcript visibly slides
    // (older loads in, newer drops out) and memory stays bounded, large enough to keep a comfortable
    // off-screen buffer so normal reading rarely triggers a reseed. Content-based keys keep it jump-free.
    private val maxResidentEvents: Int = 400,
) {
    companion object {
        /** Requested `?limit` for initial/reseed /events fetch. NOTE: server clamps EVERY /events request to 100 logical events (`min(100)`, Discord-style), so this does NOT pull 2000; effective seed window is the newest 100, `hasMore` pages the rest via ?before. Sized above the clamp so if server cap is raised the client benefits automatically. Anything older than the seed window is ABSENT until paged in — downstream logic must not assume full history is resident. */
        const val INITIAL_LOG_LIMIT = 2000

        /** Max events a single /events response returns (server caps every request at `min(100)`). A WindowPage built from one such response is reload-safe: before=upper reconstructs it exactly. Eviction skips any oversized page so a transient accumulated blob is never evicted+reloaded with a gap. */
        const val PAGE_LIMIT = 100

        /** Default warm-keep window for an idle transcript stream. 30s comfortably exceeds the uiState `WhileSubscribed(5s)` grace plus rotation/recomposition blips, so a stream is reaped only once the user has genuinely left the session, never while on screen. */
        const val IDLE_STREAM_RELEASE_MS = 30_000L
    }

    // ── session list / one-shots ──

    /** Polls `api.sessions()` every 2s; a failed tick re-emits the last successful list (UI keeps showing sessions across a transient error). */
    fun sessionsStream(): Flow<List<Session>> = flow {
        var lastGood: List<Session> = emptyList()
        pollFlow(2_000L) {
            try {
                api.sessions().also { lastGood = it }
            } catch (e: Exception) {
                lastGood
            }
        }.collect { emit(it) }
    }

    /** Like [sessionsStream] but emits [SessionsLoadState] so UI can distinguish first-load failure (server unreachable at launch) from a later blip. First tick success → Loaded forever after; first tick fail → FirstLoadError once, then Loaded with last-good on subsequent ticks (existing keep-last-good preserved). */
    fun sessionsStreamWithState(): Flow<SessionsLoadState> = flow {
        var lastGood: List<Session>? = null
        pollFlow(2_000L) {
            try {
                api.sessions().also { lastGood = it }
            } catch (e: Exception) {
                null
            }
        }.collect { result ->
            if (result == null) {
                if (lastGood == null) emit(SessionsLoadState.FirstLoadError)
                // Subsequent failures: keep last-good — consumer retains the last Loaded emission.
            } else {
                emit(SessionsLoadState.Loaded(result))
            }
        }
    }

    /** One-shot fetch (pull-to-refresh) outside the 2s poll. */
    suspend fun sessions(): List<Session> = api.sessions()
    suspend fun usage(): Usage = api.usage()
    suspend fun session(id: String): Outcome<SessionDetail> = runCatchingOutcome { api.session(id) }
    /** Bare session row (no transcript log) for surfaces that don't need it (e.g. settings sub-page). Distinct from [session]. */
    suspend fun get(id: String): Outcome<Session> = runCatchingOutcome { api.get(id) }
    suspend fun create(req: NewSessionReq): Outcome<String> {
        val o = runCatchingOutcome { api.create(req) }
        when (o) {
            is Outcome.Success -> AppLog.d("Repo", "create OK id=${o.value}")
            is Outcome.Failure -> AppLog.w("Repo", "create FAILED: ${o.error}")
        }
        return o
    }
    suspend fun patchSession(id: String, req: PatchSessionReq): Outcome<Session> =
        runCatchingOutcome { api.patchSession(id, req) }
    /** Adopt picker: discover on disk (GET /api/adoptable). Wrapped in [Outcome] so picker VM can render a banner on failure instead of crashing (transient errors fall back to "no candidates"). */
    suspend fun adoptable(): Outcome<List<Adoptable>> = runCatchingOutcome { api.adoptable() }
    /** Returns the new id so the picker navigates to it; on failure [Outcome] carries the error for inline display. */
    suspend fun adoptSession(claudeSessionId: String, cwd: String): Outcome<String> {
        val o = runCatchingOutcome { api.adoptSession(AdoptSessionReq(claudeSessionId, cwd)) }
        when (o) {
            is Outcome.Success -> AppLog.d("Repo", "adoptSession OK csid=$claudeSessionId new=${o.value}")
            is Outcome.Failure -> AppLog.w("Repo", "adoptSession FAILED csid=$claudeSessionId: ${o.error}")
        }
        return o
    }
    /** Hand a session off to a local Claude Code CLI (POST /api/sessions/:id/detach). The returned `resumeCmd` is the exact command line to paste in a terminal — detail screen displays it copyable next to the "handed off" banner. */
    suspend fun detach(id: String): Outcome<DetachResp> {
        val o = runCatchingOutcome { api.detach(id) }
        when (o) {
            is Outcome.Success -> AppLog.d("Repo", "detach OK id=$id")
            is Outcome.Failure -> AppLog.w("Repo", "detach FAILED id=$id: ${o.error}")
        }
        return o
    }
    suspend fun kill(id: String) { AppLog.d("Repo", "kill id=$id"); runCatchingOutcome { api.kill(id) }; release(id) }
    /** Stop the current turn but keep the session live — no [release], so the transcript stream and reducer stay up and the user can immediately send the next message. */
    suspend fun interrupt(id: String) { AppLog.d("Repo", "interrupt id=$id"); runCatchingOutcome { api.interrupt(id) } }
    /** Returns [Outcome] so caller can roll back any optimistic overlay on failure (no reseed needed — live stream delivers the permResolved marker on success). */
    suspend fun respondPermission(id: String, decision: String, feedback: String? = null): Outcome<Unit> {
        AppLog.d("Repo", "respondPermission id=$id decision=$decision")
        val o = runCatchingOutcome { api.respondPermission(id, decision, feedback) }
        when (o) {
            is Outcome.Failure -> AppLog.w("Repo", "respondPermission FAILED id=$id: ${o.error}")
            is Outcome.Success -> {}
        }
        return o
    }
    suspend fun delete(id: String) { AppLog.d("Repo", "delete id=$id"); runCatchingOutcome { api.delete(id) }; release(id) }
    /** Fork [id] into a brand-new session (status="pending"). Original session is unchanged — no [release] here. */
    suspend fun fork(id: String): Outcome<String> {
        val o = runCatchingOutcome { api.fork(id) }
        when (o) {
            is Outcome.Success -> AppLog.d("Repo", "fork OK id=$id new=${o.value}")
            is Outcome.Failure -> AppLog.w("Repo", "fork FAILED id=$id: ${o.error}")
        }
        return o
    }

    // ── Session groups (folders) ──

    suspend fun listGroups(): Outcome<List<Group>> = runCatchingOutcome { api.listGroups() }
    suspend fun createGroup(name: String, icon: String? = null): Outcome<Group> =
        runCatchingOutcome { api.createGroup(CreateGroupReq(name, icon)) }
    suspend fun updateGroup(id: String, name: String? = null, icon: String? = null): Outcome<Group> =
        runCatchingOutcome { api.updateGroup(id, UpdateGroupReq(name, icon)) }
    suspend fun deleteGroup(id: String): Outcome<Unit> = runCatchingOutcome { api.deleteGroup(id) }
    /** Assign a session to a group (or null to remove). */
    suspend fun setSessionGroup(sessionId: String, groupId: String?): Outcome<Session> =
        runCatchingOutcome { api.patchSession(sessionId, PatchSessionReq(groupId = groupId ?: "")) }

    // ── Catalog passthroughs (NewRequestViewModel) ──
    suspend fun repos(): RepoList = api.repos()
    suspend fun skills(): List<SkillInfo> = api.skills()
    suspend fun plugins(): List<PluginInfo> = api.plugins()
    suspend fun templates(): List<Template> = runCatching { api.getTemplates() }.getOrDefault(emptyList())
    /** Globally-configured components (skills, plugins, MCP); callers filter by kind. */
    suspend fun globalSettings(): List<dev.agentic.data.net.ComponentInfo> = api.getGlobalSettings()
    /** Fetch the model catalog and cache it in [ModelCatalog] so [modelLabel] / [MODEL_OPTIONS] work without a suspend call. Returns entries or empty on error. */
    suspend fun modelCatalog(): List<ModelEntry> = try {
        api.models().also { dev.agentic.ui.ModelCatalog.init(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.d("Repo", "modelCatalog load failed: ${e.message}")
        emptyList()
    }
    /** Claude-only session-start catalog (GET /api/models?scope=session_start) — BYOK never appears here. Picker then shows only "Default" on error. */
    suspend fun sessionStartModelCatalog(): List<ModelEntry> = try {
        api.sessionStartModels().also { dev.agentic.ui.ModelCatalog.initSessionStart(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.d("Repo", "sessionStartModelCatalog load failed: ${e.message}")
        emptyList()
    }
    suspend fun putTemplates(t: List<Template>) { runCatchingOutcome { api.putTemplates(t) } }
    /** Stage a New-request attachment before the session exists. */
    suspend fun uploadStaging(bytes: ByteArray, name: String): Outcome<StagedUpload> =
        runCatchingOutcome { api.uploadStaging(bytes, name) }

    /**
     * Debounced, cancellable content search: trim → distinctUntilChanged → debounce(250) → mapLatest
     * (last-write-wins, cancels in-flight) → short-circuit `length < 2` returns empty Success so the
     * UI clears stale results without hitting the backend. Min length 2 mirrors the server's debounce.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun contentSearch(query: StateFlow<String>): Flow<Outcome<SearchResponse>> = query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce(250)
        .mapLatest { q ->
            if (q.length < 2) Outcome.Success(SearchResponse(q, emptyList()))
            else {
                AppLog.d("Repo", "contentSearch q=$q")
                runCatchingOutcome { api.searchSessions(q) }
            }
        }

    /** Optimistic prompt bridge: list screen writes the just-submitted prompt keyed by new session id; detail reads it to show the prompt before backend log catches up. Thread-safe map. */
    val pendingPrompts: MutableMap<String, String> = ConcurrentHashMap()

    /** Per-session UNSENT input draft. SessionViewModel is scoped to the Session nav entry and dies when the user leaves; this app-scoped map persists drafts so re-entering restores them. Cleared on submit + release. */
    val inputDrafts: MutableMap<String, String> = ConcurrentHashMap()

    /** In-memory cache first, else the persisted (disk) draft. Disk fallback survives process death (the in-memory map alone dies with the process). */
    fun draftFor(id: String): String =
        inputDrafts[id] ?: settings?.draft(id)?.also { inputDrafts[id] = it } ?: ""

    /** Persist a session's composer draft to both in-memory cache and disk. */
    fun setDraft(id: String, text: String) {
        if (text.isEmpty()) { clearDraft(id); return }
        inputDrafts[id] = text
        settings?.setDraft(id, text)
    }

    /** Drop a session's composer draft from memory and disk (on send or removal). */
    fun clearDraft(id: String) {
        inputDrafts.remove(id)
        settings?.setDraft(id, null)
    }

    // ── SINGLE-SOURCE transcript ──

    private val lock = Any()
    private val reducers = HashMap<String, TranscriptReducer>()
    private val flows = HashMap<String, MutableStateFlow<TranscriptState>>()
    // Read-only view per id, created once so transcript(id) returns the SAME instance on every call
    // (asStateFlow() allocates a fresh wrapper each time — would break the cache contract).
    private val views = HashMap<String, StateFlow<TranscriptState>>()
    private val jobs = HashMap<String, Job>()
    // Per-id log cursor: how many persisted log entries the reducer has consumed. The stream loop opens
    // from this offset and rewrites it on each reseed so a reconnect never double-applies events the
    // fresh log already contains.
    private val cursors = HashMap<String, Long>()
    // Per-id cache of the last successful fetch. When a reconnect's reseed-refetch FAILS, the cursor
    // isn't advanced but the reducer still holds the dropped connection's live-applied events;
    // resetting the reducer to this cached log makes the next backfill from the stale cursor re-apply
    // those events ONCE instead of duplicating the whole turn.
    private val eventsCache = HashMap<String, List<JsonElement>>()
    private val firstEventLines = HashMap<String, Long>()
    // ── Bounded transcript window (TERMINAL sessions only) ──
    // Resident transcript as an ordered deque of pages (oldest → newest). Present only for ended
    // sessions being paged; live sessions keep the flat [eventsCache] and are never evicted.
    // Guarded by [lock]. [evictedNewerPages] is a stack of descriptors of pages evicted off the
    // NEWEST end, so [loadNewer] can reload them via before=upper.
    private val residentPages = HashMap<String, ArrayDeque<WindowPage>>()
    private val evictedNewerPages = HashMap<String, ArrayDeque<EvictedPage>>()
    // Ids with a forced reconnect ([restart]) currently in flight. Coalesces concurrent refresh
    // triggers (ON_RESUME + programmatic) so they collapse to ONE cancel+reseed+reopen instead of
    // stacking N reconnects racing over the same job + reducer. Guarded by [lock].
    private val reconnecting = HashSet<String>()
    // Per-id idle-reaper job (started only when [idleReleaseMs] != null): watches subscriber count
    // and releases the whole id once it has had ZERO subscribers for [idleReleaseMs]. Stops a long-
    // lived app from leaking one persistent WebSocket + reconnect loop per session ever opened.
    private val idleReapers = HashMap<String, Job>()

    // ── Discord-style gateway frame metadata ──
    // Each live frame carries monotonic `seq` (server's rendered-line cursor); HELLO/HEARTBEAT carry
    // the authoritative `status` and are NOT rendered. Parsed in [openStream] so the reconnect loop
    // RESUMEs from `seq` and a terminal heartbeat clears a stuck-busy turn even when the closing
    // `result` frame was missed.
    private val gatewayJson = Json { ignoreUnknownKeys = true }
    private data class GatewayMeta(val kind: String?, val seq: Int?, val status: String?)
    private fun gatewayMeta(frame: String): GatewayMeta? = runCatching {
        val o = gatewayJson.parseToJsonElement(frame).jsonObject
        GatewayMeta(
            kind = o["kind"]?.jsonPrimitive?.contentOrNull,
            seq = o["seq"]?.jsonPrimitive?.intOrNull,
            status = o["status"]?.jsonPrimitive?.contentOrNull,
        )
    }.onFailure { e -> AppLog.w("Repo", "gatewayMeta parse FAILED: ${e.message}", e) }.getOrNull()

    /** Hot, app-scoped transcript state. FIRST call creates the flow (emitting connecting=true) and kicks off initial [load] via [restart]. Repeat calls return the SAME flow instance — that referential reuse IS the cache. Initial load goes through [restart] (not bare `scope.launch { load }`) so it shares the [reconnecting] guard: ON_RESUME refresh + initial load collapse to ONE load rather than two coroutines reseeding the same non-thread-safe reducer. */
    fun transcript(id: String): StateFlow<TranscriptState> {
        val (view, isNew) = synchronized(lock) {
            views[id]?.let { return@synchronized it to false }
            val created = MutableStateFlow(TranscriptState(connecting = true))
            val readOnly = created.asStateFlow()
            flows[id] = created
            views[id] = readOnly
            reducers[id] = TranscriptReducer()
            cursors[id] = 0L
            eventsCache[id] = emptyList()
            firstEventLines[id] = 0L
            readOnly to true
        }
        if (isNew) {
            // Fresh flow — first open OR re-acquire after the reaper released it. Makes the
            // freeze-and-self-heal cycle visible: `reaper FIRED … release` → `transcript() create … (re-acquire)`.
            AppLog.v("Repo", "transcript() create id=${id.take(8)} (fresh flow → load + stream)")
            restart(id)
            startIdleReaper(id)
        }
        return view
    }

    /** When [idleReleaseMs] is set, watch [id]'s transcript flow and [release] it once it has had ZERO UI subscribers for that long. Detail screen's uiState (a `WhileSubscribed(5s)` combine over `transcript(id)`) is the subscriber, so this fires ~`idleReleaseMs` after the user leaves (or the app is backgrounded past the grace) — never while on screen. collectLatest restarts on flips so a quick navigate-away-and-back cancels the pending release. No-op without a configured timeout (unit tests). */
    private fun startIdleReaper(id: String) {
        val idleMs = idleReleaseMs ?: return
        val flow = synchronized(lock) { flows[id] } ?: return
        val reaper = scope.launch {
            flow.subscriptionCount
                .map { it == 0 }
                .distinctUntilChanged()
                .collectLatest { idle ->
                    if (idle) {
                        AppLog.v("Repo", "idle id=${id.take(8)} — reaper armed (${idleMs}ms to release)")
                        delay(idleMs)
                        AppLog.v("Repo", "reaper FIRED id=${id.take(8)} — releasing flow after ${idleMs}ms with no UI subscriber")
                        release(id)   // cancels the stream + this reaper (release cancels idleReapers[id])
                    } else {
                        AppLog.v("Repo", "subscribed id=${id.take(8)} — reaper disarmed")
                    }
                }
        }
        synchronized(lock) { idleReapers[id] = reaper }
    }

    /** App returned to foreground (wired to ProcessLifecycle ON_START): force every LIVE (non-terminal) session currently ON SCREEN to reconnect. Centralized backstop to per-screen ON_RESUME refresh — heals any live session whose socket went half-open while backgrounded. [restart] is coalesced + identity-guarded, safe to call for several ids and harmless for already-healthy streams. Only sessions with a live UI subscriber (`subscriptionCount > 0`) are reconnected — an idle/off-screen session has no UI to keep fresh and the idle reaper would release it anyway, so reconnecting just churns a WebSocket. Terminal and still-connecting (session == null) flows likewise skipped. */
    fun reconnectLiveSessions() {
        val live = synchronized(lock) {
            flows.keys.filter { id ->
                val flow = flows[id] ?: return@filter false
                val s = flow.value.session
                s != null && s.status !in TERMINAL && flow.subscriptionCount.value > 0
            }
        }
        AppLog.v("Repo", "reconnectLiveSessions: ${live.size}/${synchronized(lock) { flows.size }} live → ${live.map { it.take(8) }}")
        live.forEach { restart(it) }
    }

    /** Fetch session + persisted log, seed the reducer once, record the cursor, then (if live) open the reconnect loop from `cursor = log.size` so only NEW events stream in — no full backfill. On a failed fetch, clears `connecting` and leaves nodes/session untouched (screen shows error + retry via [reload]). A terminal session opens no stream. */
    private suspend fun load(id: String) {
        val flow = flowFor(id) ?: return
        val reducer = reducerFor(id) ?: return
        val d: SessionEventsResponse = try {
            api.sessionEvents(id, INITIAL_LOG_LIMIT)
        } catch (e: CancellationException) {
            throw e   // navigated away — propagate, don't treat as a load failure
        } catch (e: Exception) {
            // First load failed (transient 5xx/timeout/offline). Flag it so the screen can show an error +
            // retry instead of a permanent blank; reload(id) re-runs this.
            AppLog.w("Repo", "load FAILED (fetch) id=$id: ${e.message}")
            flow.update { it.copy(connecting = false, loadError = true) }
            return
        }
        // Seed + render INSIDE a guard: seedFromEvents/display fold structured events into nodes and can
        // throw on an unexpected element shape. Degrade to the retry banner instead of crashing.
        val seeded: List<Node> = try {
            reducer.seedFromEvents(d.events)
            reducer.display()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            flow.update { it.copy(connecting = false, loadError = true) }
            return
        }
        // The cursor is the latest event ordinal (latestEventId field = max event ID).
        setCursor(id, d.latestEventId)
        setEvents(id, d.events)
        firstEventLines[id] = d.firstEventLine
        resetWindow(id)   // fresh tail — drop any stale bounded-window pages from a prior view of this id
        flow.update {
            it.copy(
                nodes = seeded,
                session = d.session,
                busy = d.session.awaitingInput == false,
                ended = d.session.status in TERMINAL,
                connecting = false,
                loadError = false,
                hasMore = d.hasMore,
                hasNewer = false,
                latestEventId = d.latestEventId,
                firstEventLine = d.firstEventLine,
            )
        }
        AppLog.v("Repo", "load OK id=$id latestEventId=${d.latestEventId} status=${d.session.status} aw=${d.session.awaitingInput}")
        if (d.session.status !in TERMINAL) openStream(id)
    }

    /** Load earlier messages (scroll back). LIVE: prepend the page before the oldest cached event and re-seed (unbounded — the stream owns the tail). ENDED: page into the bounded window, evicting whole pages off the NEWEST end past [maxResidentEvents]. Idempotent under concurrency: a single atomic flow.update claim serializes ALL paging (earlier AND newer) so a rapid scroll can't stack duplicate fetches. */
    suspend fun loadEarlier(id: String) {
        val flow = flowFor(id) ?: return
        // Idempotent under concurrency: serialize ALL paging (earlier AND newer) through one claim so a
        // rapid scroll can't stack duplicate fetches or let loadEarlier + loadNewer mutate the page deque
        // at once. The check + flag flip are a single atomic flow.update; the UI's distinctUntilChanged
        // is a second, cheaper guard.
        var proceed = false
        flow.update { s ->
            if (s.loadingEarlier || s.loadingNewer || !s.hasMore) { proceed = false; s }
            else { proceed = true; s.copy(loadingEarlier = true) }
        }
        if (!proceed) return
        // We OWN the loading slot now: every early-exit below MUST clear it, or the spinner sticks and
        // all future scroll-back stays blocked by the guard above.
        val reducer = reducerFor(id) ?: run { flow.update { it.copy(loadingEarlier = false) }; return }
        // NOT a settled ended session — either genuinely live, OR an ended session RESUMED into a new
        // streaming turn (where `ended` stays sticky but `busy` flips true). Use the simple flat prepend,
        // no window/eviction: the stream owns the tail, and windowing a live reducer would reseed away the
        // in-flight turn's content.
        if (!flow.value.ended || flow.value.busy) {
            val firstLine = firstEventLineOf(id) ?: run { flow.update { it.copy(loadingEarlier = false) }; return }
            try {
                val d = api.sessionEvents(id, limit = 100, before = firstLine)
                val merged = d.events + eventsOf(id)
                setEvents(id, merged); firstEventLines[id] = d.firstEventLine
                reducer.seedFromEvents(merged)
                val seeded = reducer.display()
                flow.update { it.copy(nodes = seeded, loadingEarlier = false, hasMore = d.hasMore) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { flow.update { it.copy(loadingEarlier = false) } }
            return
        }
        // ENDED session: bounded window. Fetch the page just older than the oldest resident page.
        val pages = ensureWindow(id) ?: run { flow.update { it.copy(loadingEarlier = false) }; return }
        val before = synchronized(lock) { pages.first().start }
        try {
            val d = api.sessionEvents(id, limit = PAGE_LIMIT, before = before)
            if (d.events.isEmpty()) {
                // Nothing older (only trailing deltas remain before this point) — stop the top trigger.
                flow.update { it.copy(loadingEarlier = false, hasMore = false) }
                return
            }
            val events = synchronized(lock) {
                pages.addFirst(WindowPage(start = d.firstEventLine, upper = before, events = d.events))
                evictNewestOverCap(id, pages)   // drops whole pages off the NEWEST end past the cap
                pages.flatMap { it.events }
            }
            // No manual scroll anchor: the prepend lands at the OLDEST end and any eviction at the NEWEST
            // (off-screen) end; content-based LazyColumn keys keep the visible middle nodes pinned for free.
            setEvents(id, events); synchronized(lock) { firstEventLines[id] = pages.first().start }
            reducer.seedFromEvents(events)
            val seeded = reducer.display()
            flow.update { it.copy(
                nodes = seeded,
                loadingEarlier = false,
                hasMore = d.firstEventLine > 0L,
                hasNewer = hasEvictedNewer(id),
            ) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { flow.update { it.copy(loadingEarlier = false) } }
    }

    /** Load NEWER history (scroll forward) for an ENDED session whose window evicted newer pages. Reloads the most-recently-evicted page via before=<its stored upper>; on an immutable log that returns the exact page. Evicts off the OLDEST end past [maxResidentEvents]. No-op with no evicted newer pages (live sessions never have any). */
    suspend fun loadNewer(id: String) {
        val flow = flowFor(id) ?: return
        var proceed = false
        flow.update { s ->
            if (s.loadingEarlier || s.loadingNewer || !s.hasNewer || s.busy) { proceed = false; s }
            else { proceed = true; s.copy(loadingNewer = true) }
        }
        if (!proceed) return
        val reducer = reducerFor(id) ?: run { flow.update { it.copy(loadingNewer = false) }; return }
        val pages = synchronized(lock) { residentPages[id] } ?: run { flow.update { it.copy(loadingNewer = false) }; return }
        val evicted = synchronized(lock) { evictedNewerPages[id]?.firstOrNull() }
            ?: run { flow.update { it.copy(loadingNewer = false, hasNewer = false) }; return }
        try {
            val d = api.sessionEvents(id, limit = PAGE_LIMIT, before = evicted.upper)
            // Slice to the page's ORIGINAL event count (newest-first). On an immutable log the reload
            // already equals the evicted page, but takeLast bulletproofs tiling if a reload ever returns
            // extra OLDER events — it keeps exactly [evicted.start, evicted.upper), never overlapping the
            // still-resident newer page.
            val addedEvents = d.events.takeLast(evicted.count)
            val events = synchronized(lock) {
                if (addedEvents.isNotEmpty()) pages.addLast(WindowPage(start = evicted.start, upper = evicted.upper, events = addedEvents))
                evictedNewerPages[id]?.removeFirstOrNull()   // this page is resident again
                evictOldestOverCap(pages)
                pages.flatMap { it.events }
            }
            // No manual scroll anchor: the append lands at the NEWEST end and any eviction at the OLDEST
            // (off-screen) end; content-based LazyColumn keys keep the visible middle nodes pinned for free.
            setEvents(id, events); synchronized(lock) { firstEventLines[id] = pages.first().start }
            reducer.seedFromEvents(events)
            val seeded = reducer.display()
            flow.update { it.copy(
                nodes = seeded,
                loadingNewer = false,
                hasMore = synchronized(lock) { pages.first().start } > 0L,
                hasNewer = hasEvictedNewer(id),
            ) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { flow.update { it.copy(loadingNewer = false) } }
    }

    /** Lazily build the bounded-window page deque for an ENDED session from its current flat resident events (the initial tail load) so paging can start. The tail page's upper bound is the total rendered-line count ([cursorOf], = latestEventId), so before=upper reloads the tail after eviction. Returns null if per-id state is missing. */
    private fun ensureWindow(id: String): ArrayDeque<WindowPage>? = synchronized(lock) {
        residentPages[id]?.let { return@synchronized it }
        val events = eventsCache[id] ?: return@synchronized null
        val start = firstEventLines[id] ?: return@synchronized null
        val total = cursors[id] ?: return@synchronized null
        // Initial tail is ONE page: events endpoint hard-caps at 100 (server min(100)), so this is
        // always ≤100 — no need (and, without per-event line offsets, no way) to sub-chunk by line.
        val dq = ArrayDeque<WindowPage>().apply { add(WindowPage(start = start, upper = total, events = events)) }
        residentPages[id] = dq
        evictedNewerPages.getOrPut(id) { ArrayDeque() }
        dq
    }

    /** Evict whole pages off the NEWEST end until resident events ≤ [maxResidentEvents]; push each evicted page's descriptor (newest on top) so [loadNewer] restores them in order. Only RELOAD-SAFE newest pages (≤ PAGE_LIMIT, one server response) are evicted — a rare oversized transient page (accumulated live blob gone terminal before a clean reseed) is kept until the next reseed replaces it; reloading via the 100-capped before=upper would gap. Caller holds [lock]. */
    private fun evictNewestOverCap(id: String, pages: ArrayDeque<WindowPage>) {
        val stack = evictedNewerPages.getOrPut(id) { ArrayDeque() }
        while (pages.size > 1 && pages.sumOf { it.events.size } > maxResidentEvents &&
               pages.last().events.size <= PAGE_LIMIT) {
            val p = pages.removeLast()
            stack.addFirst(EvictedPage(p.start, p.upper, p.events.size))
        }
    }

    /** Evict whole pages off the OLDEST end until resident events ≤ [maxResidentEvents]. Caller holds [lock]. */
    private fun evictOldestOverCap(pages: ArrayDeque<WindowPage>) {
        while (pages.size > 1 && pages.sumOf { it.events.size } > maxResidentEvents) pages.removeFirst()
    }

    private fun hasEvictedNewer(id: String) = synchronized(lock) { evictedNewerPages[id]?.isNotEmpty() == true }

    /** Drop all bounded-window state so the next paging rebuilds it from the CURRENT resident events. Called on every reseed (load/post-stream refetch) so a freshly-loaded tail can never be paged over by stale pages. */
    private fun resetWindow(id: String) = synchronized(lock) {
        residentPages.remove(id)
        evictedNewerPages.remove(id)
    }

    /** Retry the initial transcript load after a first-load failure (a transient server error otherwise leaves the detail screen blank forever — cached flow is never re-run on re-entry). Re-runs [load] on the EXISTING flow so the same cached StateFlow updates in place. */
    fun reload(id: String) {
        val flow = flowFor(id) ?: return
        flow.update { it.copy(connecting = true, loadError = false) }
        restart(id)
    }

    /** Force a fresh transcript reseed AND a fresh live stream for an ALREADY-LOADED session, NOW. Wired to detail screen's ON_RESUME (both layouts) so a warm return rebuilds from the authoritative server log AND reopens the socket via the same proven path a cold start uses. Quiet: load() leaves `connecting` false, so no spinner. No-op if flow not loaded yet — initial load() runs instead. Delegates to [restart], which CANCELS the current stream job before reseeding — the actual fix for the "frozen until force-killed" bug. */
    fun refresh(id: String) {
        restart(id)
    }

    /**
     * Forced reconnect for [id]: cancel the current stream job, WAIT for it to fully stop, then
     * reseed from the authoritative log and open a FRESH stream. Backs [refresh]/[reload].
     *
     * Why cancel+join, not the old "just re-run load()": the previous refresh re-ran load() while
     * the existing stream job stayed alive, and load()'s openStream() early-returns when
     * `jobs[id].isActive`. A coroutine blocked reading a HALF-OPEN socket (Wi-Fi/cell handover while
     * backgrounded, no TCP FIN) is still `isActive == true` yet receives nothing — so the guard
     * refused to replace it. The reseed updated nodes ONCE, but no live stream ever resumed, and the
     * running session stayed frozen until the whole process was killed (the user's report). The ONLY
     * things that cleared such a zombie job were release() (kill/delete) or process death — hence
     * "force-close the app and reopen". Cancelling + joining the old job makes the warm-return
     * self-heal actually heal: 1) the dead/zombie socket is torn down, so load()'s openStream()
     * opens a genuinely fresh one; 2) the reducer is reseeded with NO other coroutine concurrently
     * folding frames into it, restoring the single-writer invariant.
     *
     * Coalesced via [reconnecting]: concurrent triggers collapse to one reconnect. Cancellation-safe:
     * if released (killed) while we await the join, load() is skipped.
     */
    private fun restart(id: String) {
        // Capture the CURRENT flow instance up front. After the suspending join() below we re-check
        // that the map still holds THIS exact instance before reseeding: a kill (release) + re-open
        // (transcript) racing during the join would install a DIFFERENT flow under the same id, and
        // a bare non-null check would wrongly run load() against that fresh session.
        val flow = flowFor(id) ?: return
        // add() returns false when an id is already present → a reconnect is already in flight, coalesce.
        val proceed = synchronized(lock) { reconnecting.add(id) }
        if (!proceed) return
        scope.launch {
            try {
                val old = synchronized(lock) { jobs.remove(id) }
                old?.let { it.cancel(); it.join() }   // tear the (possibly zombie) socket down before reseeding
                if (flowFor(id) === flow) load(id)    // reseed + open a fresh stream
            } finally {
                synchronized(lock) { reconnecting.remove(id) }
            }
        }
    }

    /**
     * Start (or no-op if already running) the single reconnecting stream loop for [id].
     * The loop is the unified fix for the frozen-session / non-reconnecting-stream bugs:
     * 1. Open `api.stream` from the current log cursor, folding each frame into the reducer and
     *    pushing display + live busy/ended into the flow. `result` clears busy; `engineExit` sets ended.
     * 2. When the stream returns/throws, re-fetch the authoritative session. RESEED the reducer from
     *    the fresh log and advance the cursor to `log.size` — reseeding from the full log (instead of
     *    appending) is what prevents a reconnect from double-applying events already in the persisted
     *    log. Refresh session/busy/ended from authoritative status (`busy = awaitingInput == false`,
     *    `ended = status in TERMINAL`); this also clears a stuck-busy turn that closed with no `result`.
     * 3. If refreshed status is TERMINAL, break — the session is over. Otherwise wait a capped
     *    exponential backoff and reconnect (live viewing across a dropped socket).
     * Guarded by [lock] so at most one loop job runs per id.
     */
    private fun openStream(id: String) {
        synchronized(lock) {
            if (jobs[id]?.isActive == true) return  // a loop is already running for this id
            jobs[id] = scope.launch {
                var backoff = 1_000L
                while (isActive) {
                    val reducer = reducerFor(id) ?: break  // entry evicted (kill/delete/release)
                    val flow = flowFor(id) ?: break
                    val since = cursorOf(id)
                    // display() is an O(n) 4-stage transform; running it (and a flow.update) on
                    // EVERY text-delta frame dominates a long answer. We still fold EVERY frame into
                    // the reducer (state must accumulate from all of them), but coalesce the expensive
                    // display()+emit so it fires at most ~every DISPLAY_THROTTLE_MS during a burst.
                    // INVARIANT — final state is never dropped: ended/engineExit + busy=false emit
                    // immediately; `pendingDisplay` forces a final flush after the stream loop ends
                    // even if the refetch then fails. So worst case: a content frame shown up to
                    // DISPLAY_THROTTLE_MS late, never lost.
                    var lastDisplayAt = 0L
                    var pendingDisplay = false
                    // RESUME cursor: advanced live from each frame's `seq` so a reconnect re-opens
                    // from exactly the last consumed line (gap-free, dup-free) even if the post-stream
                    // refetch below fails. Seeded from the offset we opened at.
                    var lastSeq = since
                    try {
                        api.stream(id, since.toInt()) { frame ->
                            val meta = gatewayMeta(frame)
                            meta?.seq?.let { lastSeq = it.toLong() }
                            val kind = meta?.kind
                            if (kind == "hello" || kind == "heartbeat") {
                                // Authoritative liveness + status tick — never rendered. A terminal
                                // status here clears a stuck-busy turn even if `result` was missed.
                                val st = meta?.status
                                if (st != null && st in TERMINAL) flow.update { it.copy(busy = false, ended = true) }
                                return@stream
                            }
                            val (ended, live) = reducer.applyFrameWithBusy(frame)
                            val now = System.currentTimeMillis()
                            if (ended || live == false || now - lastDisplayAt >= DISPLAY_THROTTLE_MS) {
                                lastDisplayAt = now
                                pendingDisplay = false
                                flow.update {
                                    it.copy(
                                        nodes = reducer.display(),
                                        busy = if (ended) false else (live ?: it.busy),
                                        ended = it.ended || ended,
                                    )
                                }
                            } else {
                                pendingDisplay = true
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e   // job cancelled (release/scope death) — never swallow as a transport drop
                    } catch (e: Exception) {
                        // stream closed (normal turn end) or transport drop — fall through to refetch.
                    }
                    // Final flush: emit the last coalesced display() so the tail of a burst is never
                    // dropped. Runs BEFORE the refetch, so even a failed refetch (d == null) still
                    // leaves the UI on the complete accumulated transcript. busy/ended left as-is here
                    // (the refetch sets the authoritative values); only catches up `nodes`.
                    if (pendingDisplay && isActive) {
                        pendingDisplay = false
                        flow.update { it.copy(nodes = reducer.display()) }
                    }
                    if (!isActive) break
                    // Advance the RESUME cursor to the last consumed frame's seq BEFORE reconnecting.
                    // A successful refetch below overwrites it with d.latestEventId; a FAILED one
                    // leaves it here, so the next reconnect RESUMEs from `lastSeq` instead of
                    // re-streaming from the stale opening offset.
                    setCursor(id, lastSeq)
                    AppLog.v("Repo", "stream ended id=$id lastSeq=$lastSeq; refetching")
                    val d = try { api.sessionEvents(id, INITIAL_LOG_LIMIT) } catch (e: CancellationException) { throw e } catch (e: Exception) { AppLog.w("Repo", "refetch FAILED id=$id"); null }
                    if (d != null) {
                        val r = reducerFor(id) ?: break
                        // Same guard as load(): a reseed re-folds structured events and can throw. On
                        // failure keep the current nodes but still apply authoritative session/busy/
                        // ended so status-driven UI stays correct.
                        val reseeded: List<Node>? = try {
                            r.seedFromEvents(d.events)
                            r.display()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        if (reseeded != null) { setCursor(id, d.latestEventId); setEvents(id, d.events); firstEventLines[id] = d.firstEventLine; resetWindow(id) }
                        val terminal = d.session.status in TERMINAL
                        flow.update {
                            it.copy(
                                nodes = reseeded ?: it.nodes,
                                session = d.session,
                                busy = if (terminal) false else (d.session.awaitingInput == false),
                                ended = it.ended || terminal,
                                // Successful reseed replaced the resident set with a fresh tail →
                                // window was reset, so nothing newer is evicted anymore. FAILED reseed
                                // leaves the old window (and its hasNewer) untouched.
                                hasNewer = if (reseeded != null) false else it.hasNewer,
                            )
                        }
                        if (terminal) break
                    } else {
                        // Refetch failed: cursor wasn't advanced, but the reducer still holds the
                        // dropped connection's live-applied events. Reset to the cached cursor-state
                        // events so the NEXT reconnect's backfill from `since` re-applies that turn
                        // once instead of appending a second time ("prompt shows twice" duplicate).
                        reducer.seedFromEvents(eventsOf(id))
                    }
                    // Non-terminal (live session, dropped socket, or failed refetch): reconnect after
                    // a capped exponential backoff — but wake instantly on network regain so a
                    // recovery isn't stuck waiting out the timer. No signal → plain delay(backoff).
                    val wokenByNetwork = withTimeoutOrNull(backoff) { networkAvailable.first() } != null
                    backoff = if (wokenByNetwork) 1_000L else minOf(backoff * 2, 5_000L)
                }
            }
        }
    }

    /** Send a follow-up turn. On success the returned `since` is where the new turn begins. If NO stream is currently active (e.g. resuming a terminal session whose previous stream closed), open one from `since` so the new turn streams into the same reducer/flow. For a live session the persistent stream is already open and delivers it (no second open). */
    suspend fun followUp(id: String, prompt: String, setTitle: Boolean = true, model: String? = null, effort: String? = null, permissionMode: String? = null): Outcome<Int> {
        AppLog.d("Repo", "followUp submit id=$id")
        val r = runCatchingOutcome { api.followUp(id, prompt, setTitle, model, effort, permissionMode) }
        if (r is Outcome.Success) {
            AppLog.d("Repo", "followUp OK id=$id since=${r.value}")
            val active = synchronized(lock) { jobs[id]?.isActive == true }
            if (!active && flowFor(id) != null) {
                // CLASSIC backend only (no active stream): backend leaves its terminal status a beat
                // after the POST returns. Opening the stream while status is still the stale TERMINAL
                // value backfills and closes immediately on that stale status — the live turn never
                // shows. Poll briefly first so we open from a fresh (non-terminal) status.
                var tries = 0
                while (tries < 10) {
                    val s = (runCatchingOutcome { api.sessionEvents(id, limit = 1) } as? Outcome.Success)?.value?.session
                    if (s == null || s.status !in TERMINAL) break
                    delay(400)
                    tries++
                }
                setCursor(id, r.value.toLong())
                openStream(id)
            }
        }
        if (r is Outcome.Failure) AppLog.w("Repo", "followUp FAILED id=$id: ${r.error}")
        return r
    }

    /** Refresh ONLY [TranscriptState.session] for [id] from the authoritative server row, leaving nodes/busy/ended owned by the stream loop + reducer. Without this, the detail screen's session-derived UI (error banner, repo/model/effort tags, title, input enablement, cost) is frozen at the last load/reseed for the WHOLE duration of a live streaming turn. Discriminates on current state: a clean terminal ("done") is blocked from being downgraded (stale-poll case), but an errored terminal ("failed" with errorKind, or a stopped-by-user "killed") is allowed through (user-initiated recovery: Resume/Retry or a fresh follow-up). Non-terminal→terminal updates are always applied. Failed fetch = no-op (keep-last-good). */
    suspend fun refreshSession(id: String) {
        val flow = flowFor(id) ?: return
        val newSession = (runCatchingOutcome { api.sessionEvents(id, limit = 1) } as? Outcome.Success)?.value?.session ?: return
        flow.update { st ->
            val current = st.session
            val newTerminal = newSession.status in TERMINAL
            val newStatus = newSession.status
            // Skip only when the poll is a redundant same-terminal ping OR a stale-poll downgrade
            // of a clean "done" back to non-terminal. terminal→non-terminal for an ERRORED terminal
            // is the recovery path and MUST be applied.
            val isCleanTerminal = current?.status == "done" && current.errorKind == null
            val isNoOpSameTerminal = current != null && current.status == newStatus && newTerminal
            if (isCleanTerminal && !newTerminal) st
            else if (isNoOpSameTerminal) st
            else st.copy(session = newSession)
        }
    }

    /** While collected (detail screen observes its uiState), poll the authoritative session row every 2s and fold it into the cached transcript via [refreshSession]. Reflects REAL current server state within ~2s instead of only at turn end. [pollFlow] + the swallowed fetch error keep it alive across blips. */
    fun sessionRefreshStream(id: String): Flow<Unit> = pollFlow(2_000L) { refreshSession(id) }

    private fun flowFor(id: String) = synchronized(lock) { flows[id] }
    private fun reducerFor(id: String) = synchronized(lock) { reducers[id] }
    private fun cursorOf(id: String) = synchronized(lock) { cursors[id] ?: 0L }
    private fun setCursor(id: String, value: Long) = synchronized(lock) { cursors[id] = value }
    private fun firstEventLineOf(id: String) = synchronized(lock) { firstEventLines[id] }
    private fun eventsOf(id: String) = synchronized(lock) { eventsCache[id] ?: emptyList() }
    private fun setEvents(id: String, value: List<JsonElement>) = synchronized(lock) { eventsCache[id] = value }

    /** Cancel the stream loop and drop all per-id state (job, flow, view, reducer, cursor). Stops the unbounded reconnect loop and lets the maps shrink. Called on kill/delete and from the idle reaper. */
    fun release(id: String) {
        AppLog.v("Repo", "release id=${id.take(8)} — flow dropped, stream cancelled (kill/delete or idle-reap)")
        synchronized(lock) {
            idleReapers.remove(id)?.cancel()
            jobs.remove(id)?.cancel()
            flows.remove(id)
            views.remove(id)
            reducers.remove(id)
            cursors.remove(id)
            residentPages.remove(id)
            evictedNewerPages.remove(id)
            reconnecting.remove(id)
            inputDrafts.remove(id)
            settings?.setDraft(id, null)
        }
    }
}
