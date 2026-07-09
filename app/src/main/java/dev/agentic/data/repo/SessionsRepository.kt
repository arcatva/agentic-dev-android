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

/** Min interval between live-stream display() emissions during a burst (see openStream). A turn
 *  boundary (ended / busy=false) and the post-stream final flush bypass this, so the last state is
 *  never throttled away — only high-frequency intermediate text deltas are coalesced. */
private const val DISPLAY_THROTTLE_MS = 75L

/**
 * PR-9: Sealed type emitted by [SessionsRepository.sessionsStreamWithState].
 * Lets the UI distinguish a first-load failure (server unreachable on launch) from a normal
 * tick-by-tick update, without changing the shape of the existing [sessionsStream] flow.
 */
sealed interface SessionsLoadState {
    /** A successful poll tick. [sessions] is the freshly-fetched list. */
    data class Loaded(val sessions: List<Session>) : SessionsLoadState
    /** The very first poll tick failed — server was unreachable at launch. */
    data object FirstLoadError : SessionsLoadState
}

/**
 * Snapshot of one session's chat/transcript, the unit the UI renders.
 *
 * - [busy]  — a turn is actively generating. Seeded from `session.awaitingInput == false` (a session
 *             that hasn't started its first turn reports awaitingInput=null → busy=false at seed) and
 *             then driven live by [frameBusy] on each stream frame (true while text/tool flow, false
 *             on `result`).
 * - [ended] — an engineExit frame was seen on the live stream.
 * - [connecting] — the initial `api.session(id)` load is still in flight.
 * - [loadError] — the initial load FAILED (server unreachable/5xx on open). The screen should show an
 *   error + retry (call [SessionsRepository.reload]) instead of a permanent blank.
 */
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

/** One fetched page of a terminal session's bounded transcript window: its structured [events] plus
 *  the rendered-line bounds it occupies. [upper] is the exclusive `before` cursor it was fetched with
 *  (GET /events?before=upper); re-querying before=upper on an immutable (ended) log returns this exact
 *  page, so an evicted page reloads byte-identically with no overlap. [start] is the oldest event's
 *  line (the page spans [start, upper)); consecutive pages tile since one page's start == the next
 *  page's upper. */
private data class WindowPage(val start: Long, val upper: Long, val events: List<JsonElement>)

/** Metadata retained for a page evicted off the NEWEST end (its events ARE dropped to free memory).
 *  [upper] is the exclusive `before` cursor to reload it with; [count] is how many events it held, so
 *  the reload is sliced to exactly those (`takeLast(count)`) — bulletproofing against a reload ever
 *  returning more than the page originally had (which would overlap the still-resident newer page).
 *  [start] restores the reloaded page's start line without trusting the sliced reload's firstEventLine. */
private data class EvictedPage(val start: Long, val upper: Long, val count: Int)

/**
 * Single source of truth for session-list and per-session transcript state.
 *
 * Replaces the scattered ownership that lived in SessionScreen (a `nodes` state mutated from three
 * separate effects) plus the process-global `transcriptCache`. The [TranscriptReducer] and the
 * stream [Job] for each session live HERE, app-scoped, so navigating away and back to a session
 * reuses the same hot [StateFlow] and reducer instantly — no blank reflash, no re-fetch.
 *
 * Concurrency model:
 * - The per-id maps ([reducers], [flows], [jobs]) are mutated from both the UI thread (via
 *   [transcript]) and from coroutines (via [load]/[openStream]); all access is guarded by [lock].
 * - [MutableStateFlow] updates are atomic on their own, so frame application uses `update { }`.
 * - At most ONE stream job runs per id at a time (the [openStream] guard), so the single reducer for
 *   an id is only ever mutated by one coroutine — mirroring SessionScreen's `followStream == null`
 *   guard that prevented a second concurrent stream from doubling every event.
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
        /** Requested `?limit` for the initial/reseed /events fetch. NOTE: the server clamps EVERY
         *  /events request to 100 logical events (`q.limit.unwrap_or(100).min(100)`, Discord-style) —
         *  so this does NOT pull 2000 events; the effective seed window is the newest 100 events and
         *  `hasMore` pages the rest via ?before. Sized above the clamp so if the server cap is ever
         *  raised the client benefits automatically. Anything older than the seed window is ABSENT from
         *  the transcript until paged in — downstream logic must not assume full history is resident
         *  (see interleaveShared's truncatedStart). */
        const val INITIAL_LOG_LIMIT = 2000

        /** Max events a single /events response returns (server caps every request at `min(100)`), which
         *  is also the `limit` the bounded-window pager requests. A WindowPage built from ONE such
         *  response is reload-safe: before=upper reconstructs it exactly. The one page that could exceed
         *  this is the lazily-seeded initial tail if [eventsCache] held an accumulated blob (a live session
         *  that scrolled back then went terminal before a clean reseed); eviction skips any page over this
         *  size so such a transient page is never evicted+reloaded with a gap — the next reseed replaces it. */
        const val PAGE_LIMIT = 100

        /** Default warm-keep window for an idle transcript stream (see the [idleReleaseMs] ctor arg);
         *  AppContainer wires this in production. 30s comfortably exceeds the uiState
         *  `WhileSubscribed(5s)` grace plus any rotation/recomposition blip, so a stream is reaped only
         *  once the user has genuinely left the session, never while it is on screen. */
        const val IDLE_STREAM_RELEASE_MS = 30_000L
    }

    // ── session list / one-shots ────────────────────────────────────────────────

    /**
     * Polls `api.sessions()` every 2s. Network blips never tear the flow down: a failed tick
     * re-emits the last successful list (so the UI keeps showing sessions across a transient error).
     */
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

    /**
     * PR-9: Like [sessionsStream] but emits [SessionsLoadState] so the UI can distinguish a
     * first-load failure (server unreachable on launch) from a later blip (where last-good is
     * kept and no banner is needed).
     *
     * - First tick succeeds → [SessionsLoadState.Loaded] (serverUnreachable = false forever after)
     * - First tick fails   → [SessionsLoadState.FirstLoadError] once, then continues retrying;
     *   subsequent ticks still emit [SessionsLoadState.Loaded] with the last-good list so the
     *   existing keep-last-good behaviour is preserved.
     */
    fun sessionsStreamWithState(): Flow<SessionsLoadState> = flow {
        var lastGood: List<Session>? = null   // null = haven't had a successful load yet
        pollFlow(2_000L) {
            try {
                api.sessions().also { lastGood = it }
            } catch (e: Exception) {
                null  // signal: tick failed
            }
        }.collect { result ->
            if (result == null) {
                if (lastGood == null) {
                    // First-ever load failed — server unreachable on launch.
                    emit(SessionsLoadState.FirstLoadError)
                }
                // Subsequent failures: keep showing last-good — no banner update needed here.
                // The consumer should retain the last Loaded emission for the session list.
            } else {
                emit(SessionsLoadState.Loaded(result))
            }
        }
    }

    /** One-shot session-list fetch for an on-demand (pull-to-refresh) reload, outside the 2s poll. */
    suspend fun sessions(): List<Session> = api.sessions()
    suspend fun usage(): Usage = api.usage()
    suspend fun session(id: String): Outcome<SessionDetail> = runCatchingOutcome { api.session(id) }
    /** Bare session row for surfaces that don't need the transcript log (e.g. the settings sub-page).
     *  Distinct from [session], which returns [SessionDetail] and is what the detail screen uses. */
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
    /** Adopt picker: discover Claude Code sessions on disk (GET /api/adoptable). Wrapped in [Outcome]
     *  so the picker ViewModel can render a banner on failure instead of crashing — matches the
     *  search-fetch pattern, where transient errors fall back to "no candidates". */
    suspend fun adoptable(): Outcome<List<Adoptable>> = runCatchingOutcome { api.adoptable() }
    /** Adopt a discovered Claude Code session as a native server session. Returns the new id so
     *  the picker navigates to it; on failure the [Outcome] carries the error message for the
     *  picker to show as an inline error. */
    suspend fun adoptSession(claudeSessionId: String, cwd: String): Outcome<String> {
        val o = runCatchingOutcome { api.adoptSession(AdoptSessionReq(claudeSessionId, cwd)) }
        when (o) {
            is Outcome.Success -> AppLog.d("Repo", "adoptSession OK csid=$claudeSessionId new=${o.value}")
            is Outcome.Failure -> AppLog.w("Repo", "adoptSession FAILED csid=$claudeSessionId: ${o.error}")
        }
        return o
    }
    /** Hand a session off to a local Claude Code CLI (POST /api/sessions/:id/detach). The returned
     *  `resumeCmd` is the exact command line to paste in a terminal — the detail screen displays it
     *  copyable next to the "handed off to a terminal" banner. */
    suspend fun detach(id: String): Outcome<DetachResp> {
        val o = runCatchingOutcome { api.detach(id) }
        when (o) {
            is Outcome.Success -> AppLog.d("Repo", "detach OK id=$id")
            is Outcome.Failure -> AppLog.w("Repo", "detach FAILED id=$id: ${o.error}")
        }
        return o
    }
    suspend fun kill(id: String) { AppLog.d("Repo", "kill id=$id"); runCatchingOutcome { api.kill(id) }; release(id) }
    /** Stop the current turn but keep the session live — no [release], so the transcript stream and
     *  reducer stay up and the user can immediately send the next message. */
    suspend fun interrupt(id: String) { AppLog.d("Repo", "interrupt id=$id"); runCatchingOutcome { api.interrupt(id) } }
    /** Answer a parked permission/plan prompt. Returns the [Outcome] so the caller can roll back
     *  any optimistic overlay on failure (no transcript reseed needed — the live stream delivers
     *  the permResolved marker on success). */
    suspend fun respondPermission(id: String, decision: String, feedback: String? = null): Outcome<Unit> {
        AppLog.d("Repo", "respondPermission id=$id decision=$decision")
        val o = runCatchingOutcome { api.respondPermission(id, decision, feedback) }
        when (o) {
            is Outcome.Failure -> AppLog.w("Repo", "respondPermission FAILED id=$id: ${o.error}")
            is Outcome.Success -> {} // quiet
        }
        return o
    }
    suspend fun delete(id: String) { AppLog.d("Repo", "delete id=$id"); runCatchingOutcome { api.delete(id) }; release(id) }
    /** Fork [id] into a brand-new session (status="pending"). Returns the new session's id so the
     *  UI can navigate. The original session is unchanged — no [release] here. */
    suspend fun fork(id: String): Outcome<String> {
        val o = runCatchingOutcome { api.fork(id) }
        when (o) {
            is Outcome.Success -> AppLog.d("Repo", "fork OK id=$id new=${o.value}")
            is Outcome.Failure -> AppLog.w("Repo", "fork FAILED id=$id: ${o.error}")
        }
        return o
    }

    // ── Session groups (folders) ──────────────────────────────────────────────

    suspend fun listGroups(): Outcome<List<Group>> = runCatchingOutcome { api.listGroups() }
    suspend fun createGroup(name: String, icon: String? = null): Outcome<Group> =
        runCatchingOutcome { api.createGroup(CreateGroupReq(name, icon)) }
    suspend fun updateGroup(id: String, name: String? = null, icon: String? = null): Outcome<Group> =
        runCatchingOutcome { api.updateGroup(id, UpdateGroupReq(name, icon)) }
    suspend fun deleteGroup(id: String): Outcome<Unit> = runCatchingOutcome { api.deleteGroup(id) }
    /** Assign a session to a group (or null to remove from group). */
    suspend fun setSessionGroup(sessionId: String, groupId: String?): Outcome<Session> =
        runCatchingOutcome { api.patchSession(sessionId, PatchSessionReq(groupId = groupId ?: "")) }

    // ── Catalog passthroughs (needed by NewRequestViewModel) ──────────────────
    suspend fun repos(): RepoList = api.repos()
    suspend fun skills(): List<SkillInfo> = api.skills()
    suspend fun plugins(): List<PluginInfo> = api.plugins()
    suspend fun templates(): List<Template> = runCatching { api.getTemplates() }.getOrDefault(emptyList())
    /** Fetch globally-configured components (skills, plugins, MCP).
     *  Delegates directly to [AgenticApi.getGlobalSettings] — callers filter by kind as needed. */
    suspend fun globalSettings(): List<dev.agentic.data.net.ComponentInfo> = api.getGlobalSettings()
    /** Fetch the model catalog and cache it in [ModelCatalog] so [modelLabel] / [MODEL_OPTIONS]
     *  work without a suspend call. Returns the entries or empty on error. */
    suspend fun modelCatalog(): List<ModelEntry> = try {
        api.models().also { dev.agentic.ui.ModelCatalog.init(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.d("Repo", "modelCatalog load failed: ${e.message}")
        emptyList()
    }
    /** Fetch the Claude-only session-start catalog (GET /api/models?scope=session_start) and cache
     *  it in [ModelCatalog] so [SESSION_START_MODEL_OPTIONS] works without a suspend call. The New
     *  Request and Session Settings pickers use this — BYOK providers never appear in it. Returns
     *  the entries or empty on error (pickers then show only "Default"). */
    suspend fun sessionStartModelCatalog(): List<ModelEntry> = try {
        api.sessionStartModels().also { dev.agentic.ui.ModelCatalog.initSessionStart(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.d("Repo", "sessionStartModelCatalog load failed: ${e.message}")
        emptyList()
    }
    suspend fun putTemplates(t: List<Template>) { runCatchingOutcome { api.putTemplates(t) } }
    /** Stage a New-request attachment before the session exists (POST /api/uploads). Returns the
     *  [StagedUpload] (token + name + uploads/<name> path) or [Outcome.Failure] on any error. */
    suspend fun uploadStaging(bytes: ByteArray, name: String): Outcome<StagedUpload> =
        runCatchingOutcome { api.uploadStaging(bytes, name) }

    /**
     * Debounced, cancellable content search across rendered transcripts.
     *
     * Pipeline (plan Task 5 Step 4):
     * 1. Trim whitespace from each query emission.
     * 2. `distinctUntilChanged` — no point re-running an identical query.
     * 3. `debounce(250)` — settle for a quarter-second after the user stops typing so a fast typer
     *    doesn't fire one request per keystroke against the backend.
     * 4. `mapLatest` — last-write-wins: if a NEW trimmed query arrives while the previous call is
     *    still in flight, the previous call is CANCELLED (so its in-flight `api.searchSessions`
     *    suspend point throws CancellationException, which is what we want — see step 5).
     * 5. `runCatchingOutcome` maps exceptions to [Outcome.Failure]; the `length < 2` short-circuit
     *    returns an empty [Outcome.Success] so the UI clears stale results when the user clears
     *    the box, without hitting the backend.
     *
     * Min query length 2 mirrors the backend's debounce threshold (Task 1) — one-character
     * "searches" never reach the server.
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

    /**
     * Optimistic prompt bridge: the list screen writes the just-submitted prompt here keyed by the
     * new session id, and the detail screen reads it to show the prompt immediately before the
     * backend log catches up. Replaces SessionScreen/Home's `pendingPrompts`. Thread-safe map.
     */
    val pendingPrompts: MutableMap<String, String> = ConcurrentHashMap()

    /** Per-session UNSENT input draft. SessionViewModel is scoped to the Session nav entry and is
     *  destroyed when the user leaves (its in-memory input is lost); this app-scoped map keeps the
     *  draft so re-entering the session restores what was typed. Cleared on submit and on release. */
    val inputDrafts: MutableMap<String, String> = ConcurrentHashMap()

    /** Read a session's composer draft: in-memory cache first, else the persisted (disk) draft.
     *  The disk fallback is what makes an un-sent message survive process death (OS killing a
     *  backgrounded app) — the in-memory map alone dies with the process. */
    fun draftFor(id: String): String =
        inputDrafts[id] ?: settings?.draft(id)?.also { inputDrafts[id] = it } ?: ""

    /** Persist a session's composer draft to both the in-memory cache and disk. */
    fun setDraft(id: String, text: String) {
        if (text.isEmpty()) { clearDraft(id); return }
        inputDrafts[id] = text
        settings?.setDraft(id, text)
    }

    /** Drop a session's composer draft from memory and disk (on send, or when the session is removed). */
    fun clearDraft(id: String) {
        inputDrafts.remove(id)
        settings?.setDraft(id, null)
    }

    // ── SINGLE-SOURCE transcript ────────────────────────────────────────────────

    private val lock = Any()
    private val reducers = HashMap<String, TranscriptReducer>()
    private val flows = HashMap<String, MutableStateFlow<TranscriptState>>()
    // Read-only view per id, created once so transcript(id) returns the SAME instance on every call
    // (asStateFlow() allocates a fresh wrapper each time, which would break the cache contract).
    private val views = HashMap<String, StateFlow<TranscriptState>>()
    private val jobs = HashMap<String, Job>()
    // Per-id log cursor: how many persisted log entries the reducer has already consumed. The stream
    // loop opens from this offset and rewrites it on each reseed so a reconnect never double-applies
    // events that the fresh log already contains.
    private val cursors = HashMap<String, Long>()
    // Per-id cache of the log from the last successful fetch (the cursor-state). When a reconnect's
    // reseed-refetch FAILS, the cursor isn't advanced but the reducer still holds the dropped
    // connection's live-applied events; resetting the reducer to this cached log makes the next backfill
    // from the (stale) cursor re-apply those events ONCE instead of duplicating the whole turn.
    private val eventsCache = HashMap<String, List<JsonElement>>()
    private val firstEventLines = HashMap<String, Long>()
    // ── Bounded transcript window (TERMINAL sessions only) ──────────────────────
    // The resident transcript as an ordered deque of pages (oldest → newest). Present only for ended
    // sessions being paged; a live session keeps the flat [eventsCache] and is never evicted. Guarded
    // by [lock]. [evictedNewerPages] is a stack (newest-evicted on top) of the descriptors of pages
    // evicted off the NEWEST end, so [loadNewer] can reload them in order via before=upper.
    private val residentPages = HashMap<String, ArrayDeque<WindowPage>>()
    private val evictedNewerPages = HashMap<String, ArrayDeque<EvictedPage>>()
    // Ids with a forced reconnect ([restart]) currently in flight. Coalesces concurrent refresh
    // triggers — most notably the detail screen's ON_RESUME firing on app-foreground alongside any
    // programmatic refresh — so they collapse to ONE cancel+reseed+reopen instead of stacking N
    // reconnects that race over the same job and reducer. Guarded by [lock].
    private val reconnecting = HashSet<String>()
    // Per-id idle-reaper job (started only when [idleReleaseMs] != null): watches the transcript flow's
    // subscriber count and releases the whole id (cancels the stream, drops the reducer/flow) once it has
    // had ZERO subscribers for [idleReleaseMs]. Cancelled in [release]. Stops a long-lived app from
    // leaking one persistent WebSocket + reconnect loop per session ever opened.
    private val idleReapers = HashMap<String, Job>()

    // ── Discord-style gateway frame metadata ────────────────────────────────────
    // Each live frame carries a monotonic `seq` (the server's rendered-line cursor); HELLO/HEARTBEAT
    // frames carry the authoritative `status` and are NOT rendered. We parse this lightweight meta in
    // [openStream] so the reconnect loop RESUMEs from `seq` and so a terminal heartbeat clears a
    // stuck-busy turn even when the closing `result` frame was missed.
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

    /**
     * Hot, app-scoped transcript state for [id]. The FIRST call creates the flow (emitting
     * `connecting=true`), stores it, and kicks off the initial [load] via [restart]. Repeat calls
     * return the SAME flow instance immediately — that referential reuse is the cache (replaces
     * `transcriptCache`).
     *
     * The initial load goes through [restart] (not a bare `scope.launch { load(id) }`) so it shares
     * the [reconnecting] guard: if an ON_RESUME refresh fires while the initial load is still in
     * flight, the two collapse to ONE load instead of two coroutines reseeding the same
     * (non-thread-safe) reducer at once.
     */
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
            // A FRESH flow — first open OR a re-acquire after the reaper released it (the SessionViewModel
            // re-calls transcript(id) per subscription; logging this makes the freeze-and-self-heal cycle
            // visible: `reaper FIRED … release` → later `transcript() create … (re-acquire)`.
            AppLog.v("Repo", "transcript() create id=${id.take(8)} (fresh flow → load + stream)")
            restart(id)
            startIdleReaper(id)
        }
        return view
    }

    /**
     * When [idleReleaseMs] is set, watch [id]'s transcript flow and [release] it once it has had ZERO
     * UI subscribers for that long. The detail screen's uiState (a `WhileSubscribed(5s)` combine over
     * `transcript(id)`) is the subscriber, so this fires ~`idleReleaseMs` after the user leaves the
     * session (or the app is backgrounded past the WhileSubscribed grace) — never while it is on screen.
     * collectLatest restarts the wait whenever the count flips, so a quick navigate-away-and-back (a
     * subscriber returning before the timer fires) cancels the pending release. No-op without a configured
     * timeout (unit tests), so the suite's `advanceUntilIdle()` can never trip the timer.
     */
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

    /**
     * The app returned to the foreground (wired to ProcessLifecycle ON_START): force every LIVE
     * (non-terminal) session that is currently ON SCREEN to reconnect. A centralized backstop to the
     * per-screen ON_RESUME refresh — it can't be lost to a UI refactor, and it heals any live session
     * whose socket went half-open while backgrounded. [restart] is coalesced + identity-guarded, so
     * this is safe to call for several ids at once and harmless for already-healthy streams (a cheap
     * reseed + reconnect).
     *
     * Only sessions with a live UI subscriber (`subscriptionCount > 0`) are reconnected: an
     * idle/off-screen session has no UI to keep fresh and the idle reaper would release it in
     * [idleReleaseMs] anyway, so reconnecting it would just churn a WebSocket for nothing (Gemini
     * review). Terminal and still-connecting (session == null) flows are likewise skipped.
     */
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

    /**
     * Fetch the session + persisted log, seed the reducer once, record the log cursor, then (if the
     * session is still live) start the reconnect loop from `cursor = log.size` so only NEW events
     * stream in — no full backfill. On a failed fetch, just clears `connecting` and leaves
     * nodes/session untouched. A terminal session opens no stream.
     */
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

    /**
     * Load earlier messages (scroll back). For a LIVE session: prepend the page before the oldest
     * cached event and re-seed (unbounded — the stream owns the tail). For an ENDED session: page into
     * the bounded window, evicting whole pages off the NEWEST end past [maxResidentEvents].
     */
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

    /**
     * Load NEWER history (scroll forward) for an ENDED session whose bounded window evicted newer pages.
     * Reloads the most-recently-evicted page via before=<its stored upper>; on an immutable log that
     * returns the exact page. Evicts off the OLDEST end past [maxResidentEvents]. No-op with no evicted
     * newer pages (live sessions never have any).
     */
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

    /** Lazily build the bounded-window page deque for an ENDED session from its current flat resident
     *  events (the initial tail load) so paging can start. The tail page's upper bound is the total
     *  rendered-line count ([cursorOf], = latestEventId), so before=upper reloads the tail after
     *  eviction. Returns null if per-id state is missing. */
    private fun ensureWindow(id: String): ArrayDeque<WindowPage>? = synchronized(lock) {
        residentPages[id]?.let { return@synchronized it }
        val events = eventsCache[id] ?: return@synchronized null
        val start = firstEventLines[id] ?: return@synchronized null
        val total = cursors[id] ?: return@synchronized null
        // The initial tail is ONE page: the events endpoint hard-caps a response at 100 events (server
        // min(100)), so this is always ≤100 — there's no need (and, without per-event line offsets, no
        // way) to sub-chunk it by line. Its upper bound is the total rendered-line count, so
        // before=total reloads it byte-identically after eviction.
        val dq = ArrayDeque<WindowPage>().apply { add(WindowPage(start = start, upper = total, events = events)) }
        residentPages[id] = dq
        evictedNewerPages.getOrPut(id) { ArrayDeque() }
        dq
    }

    /** Evict whole pages off the NEWEST end until resident events ≤ [maxResidentEvents]; push each
     *  evicted page's descriptor (newest on top) so [loadNewer] restores them in order. Caller holds [lock]. */
    private fun evictNewestOverCap(id: String, pages: ArrayDeque<WindowPage>) {
        val stack = evictedNewerPages.getOrPut(id) { ArrayDeque() }
        // Only evict a RELOAD-SAFE newest page (≤ PAGE_LIMIT, i.e. one server response). A rare oversized
        // transient page (accumulated live blob that went terminal before a clean reseed) is NOT evicted —
        // reloading it via the 100-capped before=upper would gap. It's kept until the next reseed replaces
        // it; the window is briefly larger but never loses data.
        while (pages.size > 1 && pages.sumOf { it.events.size } > maxResidentEvents &&
               pages.last().events.size <= PAGE_LIMIT) {
            val p = pages.removeLast()
            stack.addFirst(EvictedPage(p.start, p.upper, p.events.size))
        }
    }

    /** Evict whole pages off the OLDEST end until resident events ≤ [maxResidentEvents]. No stack needed:
     *  [loadEarlier] reloads via before=oldestResident.start. Caller holds [lock]. */
    private fun evictOldestOverCap(pages: ArrayDeque<WindowPage>) {
        while (pages.size > 1 && pages.sumOf { it.events.size } > maxResidentEvents) pages.removeFirst()
    }

    private fun hasEvictedNewer(id: String) = synchronized(lock) { evictedNewerPages[id]?.isNotEmpty() == true }

    /** Drop all bounded-window state for [id] so the next paging rebuilds it from the CURRENT resident
     *  events. Called on every reseed (load / post-stream refetch), so a freshly-loaded tail can never be
     *  paged over by stale pages, and a resumed ended session isn't paged with cursors from an old
     *  window. The caller clears [TranscriptState.hasNewer] in its own flow.update. */
    private fun resetWindow(id: String) = synchronized(lock) {
        residentPages.remove(id)
        evictedNewerPages.remove(id)
    }

    /**
     * Retry the initial transcript load for [id] after a first-load failure (a transient server error
     * otherwise leaves the detail screen blank forever — the cached flow is never re-run on re-entry).
     * Re-runs [load] on the EXISTING flow so the same cached StateFlow updates in place.
     */
    fun reload(id: String) {
        val flow = flowFor(id) ?: return
        flow.update { it.copy(connecting = true, loadError = false) }
        restart(id)
    }

    /**
     * Force a fresh transcript reseed AND a fresh live stream for an ALREADY-LOADED session, NOW.
     * Wired to the detail screen's ON_RESUME (both layouts) so a warm return — the app foregrounded,
     * or this session navigated back to — rebuilds from the authoritative server log AND reopens the
     * socket via the same proven path a cold start uses. Quiet: load() leaves `connecting` false, so
     * no spinner flashes. No-op if the flow isn't loaded yet — the initial load() runs instead.
     *
     * This delegates to [restart], which CANCELS the current stream job before reseeding. That cancel
     * is the actual fix for the "running session frozen until the app is force-killed" bug: see
     * [restart] for why the previous reseed-only refresh could not replace a half-open/zombie socket.
     */
    fun refresh(id: String) {
        restart(id)
    }

    /**
     * Forced reconnect for [id]: cancel the current stream job, WAIT for it to fully stop, then
     * reseed from the authoritative log and open a FRESH stream. Backs [refresh]/[reload].
     *
     * Why cancel+join, not the old "just re-run load()": the previous refresh re-ran load() while the
     * existing stream job stayed alive, and load()'s openStream() early-returns when
     * `jobs[id].isActive`. A stream coroutine blocked reading a HALF-OPEN socket (a Wi-Fi/cell handover
     * while backgrounded, with no TCP FIN) is still `isActive == true` yet receives nothing — so the
     * guard refused to replace it. The reseed updated nodes ONCE, but no live stream ever resumed, and
     * the running session stayed frozen until the whole process was killed (the user's report). The
     * ONLY things that cleared such a zombie job were release() (kill/delete) or process death — hence
     * "force-close the app and reopen". Cancelling + joining the old job here makes the warm-return
     * self-heal actually heal:
     *   1. the dead/zombie socket is torn down, so load()'s openStream() opens a genuinely fresh one;
     *   2. the reducer is reseeded with NO other coroutine concurrently folding frames into it,
     *      restoring the single-writer invariant the old refresh quietly broke (load()'s seedFromLog
     *      racing the live loop's applyFrame on the same non-thread-safe TranscriptReducer).
     *
     * Coalesced via [reconnecting]: concurrent triggers collapse to one reconnect (the in-flight one
     * already reseeds from the current server log, so a second is redundant churn). Cancellation-safe:
     * if the session is released (killed) while we await the join, load() is skipped.
     */
    private fun restart(id: String) {
        // Capture the CURRENT flow instance up front. After the suspending join() below we re-check that
        // the map still holds THIS exact instance before reseeding: a kill (release) + re-open
        // (transcript) racing during the join would install a DIFFERENT flow under the same id, and a
        // bare non-null check would wrongly run load() against that fresh session — a redundant load and
        // a possible duplicate stream. Reference identity (===) is the guard. (Gemini review.)
        val flow = flowFor(id) ?: return
        // add() returns false when an id is already present → a reconnect is already in flight, coalesce.
        val proceed = synchronized(lock) { reconnecting.add(id) }
        if (!proceed) return
        scope.launch {
            try {
                val old = synchronized(lock) { jobs.remove(id) }
                old?.let { it.cancel(); it.join() }   // tear the (possibly zombie) socket down before reseeding
                if (flowFor(id) === flow) load(id)    // reseed + open a fresh stream (same session, job removed)
            } finally {
                synchronized(lock) { reconnecting.remove(id) }
            }
        }
    }

    /**
     * Start (or no-op if already running) the single reconnecting stream loop for [id].
     *
     * The loop is the unified fix for the frozen-session / non-reconnecting-stream bugs:
     * 1. Open `api.stream` from the current log cursor, folding each frame into the reducer and
     *    pushing display + live busy/ended into the flow. `result` clears busy; `engineExit` sets
     *    ended.
     * 2. When the stream returns/throws (turn finished OR transport drop), re-fetch the authoritative
     *    session via `api.session`. RESEED the reducer from the fresh log and advance the cursor to
     *    `log.size` — reseeding from the full log (instead of appending) is what prevents a reconnect
     *    from double-applying events already in the persisted log. Refresh session/busy/ended from the
     *    authoritative status: `busy = awaitingInput == false`, `ended = status in TERMINAL`. This
     *    also clears a stuck-busy turn that closed with no `result` frame (kill / engine crash).
     * 3. If the refreshed status is TERMINAL, break — the session is over. Otherwise wait a capped
     *    exponential backoff and reconnect (live viewing across a dropped socket).
     *
     * Guarded by [lock] so at most one loop job runs per id: a concurrent call while a job is active
     * is skipped (preserving the one-job-per-id invariant that prevents doubled events).
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
                    // ── Interim display() throttle ────────────────────────────────────────────────
                    // display() is an O(n) 4-stage transform; running it (and a flow.update) on EVERY
                    // text-delta frame dominates a long answer. We still fold EVERY frame into the
                    // reducer (state must accumulate from all of them), but coalesce the expensive
                    // display()+emit so it fires at most ~every DISPLAY_THROTTLE_MS during a burst.
                    // INVARIANT — the final state is never dropped: an ended/engineExit frame and any
                    // busy=false (turn-finished) transition emit immediately, and `pendingDisplay`
                    // forces a final flush after the stream loop ends (below) even if the refetch then
                    // fails. So the worst case is a content frame shown up to DISPLAY_THROTTLE_MS late,
                    // never lost.
                    var lastDisplayAt = 0L
                    var pendingDisplay = false
                    // RESUME cursor: advanced live from each frame's `seq` so a reconnect re-opens from
                    // exactly the last consumed line (gap-free, dup-free) even if the post-stream refetch
                    // below fails. Seeded from the offset we opened at.
                    var lastSeq = since
                    try {
                        api.stream(id, since.toInt()) { frame ->
                            // Gateway meta (kind/seq/status) parsed without disturbing the reducer.
                            val meta = gatewayMeta(frame)
                            meta?.seq?.let { lastSeq = it.toLong() }
                            val kind = meta?.kind
                            if (kind == "hello" || kind == "heartbeat") {
                                // Authoritative liveness + status tick — never rendered. A terminal
                                // status here clears a stuck-busy turn even if the `result` frame was
                                // missed (the 76105257/316a4d9f "spinner stuck after the turn ended" case).
                                val st = meta?.status
                                if (st != null && st in TERMINAL) flow.update { it.copy(busy = false, ended = true) }
                                return@stream
                            }
                            // Parse the frame ONCE: applyFrameWithBusy folds it into the reducer and
                            // returns both the ended flag and the turn-state busy signal, replacing the
                            // old separate frameBusy(frame) re-parse.
                            val (ended, live) = reducer.applyFrameWithBusy(frame)
                            val now = System.currentTimeMillis()
                            // Emit now for a turn boundary (ended, or a definitive busy=false), else only
                            // once the throttle window has elapsed; otherwise mark a pending flush.
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
                        throw e   // job cancelled (release/scope death) — never swallow it as a transport drop
                    } catch (e: Exception) {
                        // stream closed (normal turn end) or transport drop — fall through to refetch.
                    }
                    // Final flush: emit the last coalesced display() so the tail of a burst is never
                    // dropped. Runs BEFORE the refetch, so even a failed refetch (d == null, which does
                    // not emit) still leaves the UI on the complete accumulated transcript. busy/ended
                    // are left as-is here (the refetch below sets the authoritative values); this only
                    // catches up `nodes` to every frame already folded into the reducer.
                    if (pendingDisplay && isActive) {
                        pendingDisplay = false
                        flow.update { it.copy(nodes = reducer.display()) }
                    }
                    if (!isActive) break
                    // Advance the RESUME cursor to the last consumed frame's seq BEFORE reconnecting. A
                    // successful refetch below overwrites it with the authoritative d.latestEventId (same value);
                    // a FAILED refetch leaves it here, so the next reconnect RESUMEs from `lastSeq`
                    // instead of re-streaming from the stale opening offset.
                    setCursor(id, lastSeq)
                    AppLog.v("Repo", "stream ended id=$id lastSeq=$lastSeq; refetching")
                    // Stream closed. Re-fetch the authoritative session; reseed from the fresh events
                    // so a reconnect never double-applies events the persisted log already holds.
                    val d = try { api.sessionEvents(id, INITIAL_LOG_LIMIT) } catch (e: CancellationException) { throw e } catch (e: Exception) { AppLog.w("Repo", "refetch FAILED id=$id"); null }
                    if (d != null) {
                        val r = reducerFor(id) ?: break
                        // Same guard as load(): a reseed re-folds structured events and can throw.
                        // On failure keep the current nodes but still apply the authoritative
                        // session/busy/ended so status-driven UI stays correct.
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
                                // A successful reseed replaced the resident set with a fresh tail → the
                                // bounded window was reset, so nothing newer is evicted anymore. A FAILED
                                // reseed leaves the old window (and its hasNewer) untouched.
                                hasNewer = if (reseeded != null) false else it.hasNewer,
                            )
                        }
                        if (terminal) break
                    } else {
                        // Refetch failed: the cursor wasn't advanced, but the reducer still holds the
                        // dropped connection's live-applied events. Reset it to the cached cursor-state
                        // events so the NEXT reconnect's backfill from `since` re-applies that turn once
                        // instead of appending it a second time (the "prompt shows twice" duplicate).
                        reducer.seedFromEvents(eventsOf(id))
                    }
                    // Non-terminal (live session, dropped socket, or a failed refetch): reconnect after a
                    // capped exponential backoff — but wake instantly if the device just regained a network
                    // (Wi-Fi back after a blip), so a recovery isn't stuck waiting out the timer. A network
                    // wake reconnects now and resets the backoff. No signal → behaves as plain delay(backoff).
                    val wokenByNetwork = withTimeoutOrNull(backoff) { networkAvailable.first() } != null
                    backoff = if (wokenByNetwork) 1_000L else minOf(backoff * 2, 5_000L)
                }
            }
        }
    }

    /**
     * Send a follow-up turn for [id]. On success the returned `since` offset is where the new turn
     * begins. If NO stream is currently active for this id (e.g. resuming a terminal session whose
     * previous stream already closed), open one from `since` so the new turn streams into the same
     * reducer/flow. For a live session the persistent stream is still open and already delivers it, so
     * we must not open a second one (the `jobs[id].isActive` guard handles both cases).
     */
    suspend fun followUp(id: String, prompt: String, setTitle: Boolean = true, model: String? = null, effort: String? = null, permissionMode: String? = null): Outcome<Int> {
        AppLog.d("Repo", "followUp submit id=$id")
        val r = runCatchingOutcome { api.followUp(id, prompt, setTitle, model, effort, permissionMode) }
        if (r is Outcome.Success) {
            AppLog.d("Repo", "followUp OK id=$id since=${r.value}")
            val active = synchronized(lock) { jobs[id]?.isActive == true }
            if (!active && flowFor(id) != null) {
                // CLASSIC backend only (no active stream): the backend leaves its terminal status a
                // beat after the POST returns. Opening the stream while status is still the stale
                // TERMINAL value backfills and closes immediately on that stale status — the live turn
                // never shows. Poll briefly first so we open from a fresh (non-terminal) status
                // (mirrors SessionScreen:484-490). The streaming backend never reaches here (its
                // persistent stream is still active), so no delay is added there.
                var tries = 0
                while (tries < 10) {
                    val s = (runCatchingOutcome { api.sessionEvents(id, limit = 1) } as? Outcome.Success)?.value?.session
                    if (s == null || s.status !in TERMINAL) break
                    delay(400)
                    tries++
                }
                // The new turn begins at `since`; point the cursor there so the reconnect loop opens
                // from it. The loop then reseeds from the fresh log on each stream-end as usual.
                setCursor(id, r.value.toLong())
                openStream(id)
            }
        }
        if (r is Outcome.Failure) AppLog.w("Repo", "followUp FAILED id=$id: ${r.error}")
        return r
    }

    /**
     * Refresh ONLY [TranscriptState.session] for [id] from the authoritative server row, leaving
     * nodes/busy/ended owned by the stream loop + reducer. Without this, the detail screen's
     * session-derived UI (error banner, repo/model/effort tags, title, input enablement, cost) is
     * frozen at the last load/reseed for the WHOLE duration of a live streaming turn — the live frame
     * handler only updates nodes/busy/ended, never session. That is why a resumed turn (server cleared
     * the error) kept showing the stale error banner until the turn ended.
     *
     * The guards here balance two competing risks:
     *
     *   1. The user's Resume click on an errored (failed/usage_limit/...) session must surface
     *      the server's queued follow_up patch (status→pending, error/errorKind→null) so the
     *      error banner clears during the pending window. Without the RECOVERY carve-out, the
     *      refresh poll would silently discard the new state because the row is still terminal
     *      (failed→pending is a terminal→non-terminal transition).
     *
     *   2. A clean "done" terminal row must not be downgraded back to "running" by a stale poll
     *      — the stream loop's authoritative reseed is the source of truth for terminal writes.
     *      Blocking clean-terminal→non-terminal updates keeps a late poll from regressing a
     *      just-finished turn.
     *
     * The carve-out discriminates on the CURRENT state: a clean terminal ("done") is blocked
     * from being downgraded (the stale-poll case), but an errored terminal ("failed" with
     * errorKind, or a stopped-by-user "killed") is allowed through to the new state — those are
     * always user-initiated recoveries (Resume/Retry or a fresh follow-up). Non-terminal→terminal
     * updates (a turn finishing while a poll is in flight) are always applied — the stream loop
     * also writes terminal, so this converges on the same end state. A failed fetch is a no-op
     * (keep-last-good).
     */
    suspend fun refreshSession(id: String) {
        val flow = flowFor(id) ?: return
        val newSession = (runCatchingOutcome { api.sessionEvents(id, limit = 1) } as? Outcome.Success)?.value?.session ?: return
        flow.update { st ->
            val current = st.session
            val newTerminal = newSession.status in TERMINAL
            val newStatus = newSession.status
            // Skip only when the poll is a redundant same-terminal ping (nothing to update) OR a
            // stale-poll downgrade of a clean "done" back to a non-terminal state. The terminal→
            // non-terminal transition for an ERRORED terminal is the recovery path and MUST be
            // applied (the user's reported bug); the same transition for a clean "done" is a
            // stale-poll regression and must be blocked.
            val isCleanTerminal = current?.status == "done" && current.errorKind == null
            val isNoOpSameTerminal = current != null && current.status == newStatus && newTerminal
            if (isCleanTerminal && !newTerminal) st
            else if (isNoOpSameTerminal) st
            else st.copy(session = newSession)
        }
    }

    /**
     * While collected (i.e. while the detail screen observes its uiState), poll the authoritative
     * session row every 2s and fold it into the cached transcript via [refreshSession], so
     * session-derived UI reflects the REAL current server state within ~2s instead of only at turn end.
     * Emits a Unit tick per poll; the refreshed data reaches the UI through the transcript StateFlow
     * that [refreshSession] mutates. [pollFlow] + the swallowed fetch error keep it alive across blips.
     */
    fun sessionRefreshStream(id: String): Flow<Unit> = pollFlow(2_000L) { refreshSession(id) }

    private fun flowFor(id: String) = synchronized(lock) { flows[id] }
    private fun reducerFor(id: String) = synchronized(lock) { reducers[id] }
    private fun cursorOf(id: String) = synchronized(lock) { cursors[id] ?: 0L }
    private fun setCursor(id: String, value: Long) = synchronized(lock) { cursors[id] = value }
    private fun firstEventLineOf(id: String) = synchronized(lock) { firstEventLines[id] }
    private fun eventsOf(id: String) = synchronized(lock) { eventsCache[id] ?: emptyList() }
    private fun setEvents(id: String, value: List<JsonElement>) = synchronized(lock) { eventsCache[id] = value }

    /**
     * Cancel the stream loop for [id] and drop all per-id state (job, flow, view, reducer, cursor).
     * Stops the unbounded reconnect loop and lets the maps shrink. Called when a session is
     * killed/deleted; also exposed for future lifecycle use (e.g. on screen disposal).
     */
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
