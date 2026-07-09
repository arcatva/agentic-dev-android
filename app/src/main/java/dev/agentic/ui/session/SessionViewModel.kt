package dev.agentic.ui.session

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.log.AppLog
import dev.agentic.data.net.AppError
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.Session
import dev.agentic.data.net.WorkflowRun
import dev.agentic.data.net.userMessage
import dev.agentic.data.net.AgenticApi
import dev.agentic.data.repo.FilesRepository
import dev.agentic.data.repo.SessionsRepository
import dev.agentic.data.repo.TranscriptState
import dev.agentic.data.repo.WorkflowsRepository
import dev.agentic.domain.AskNode
import dev.agentic.domain.AttachmentNode
import dev.agentic.domain.Node
import dev.agentic.domain.PendingAttachment
import dev.agentic.domain.PermNode
import dev.agentic.domain.PlanNode
import dev.agentic.domain.AnswerNode
import dev.agentic.domain.PromptNode
import dev.agentic.domain.TextNode
import dev.agentic.domain.TERMINAL
import dev.agentic.domain.UploadState
import dev.agentic.domain.splitAttachments
import dev.agentic.domain.WorkflowNode
import dev.agentic.domain.interleaveShared
import dev.agentic.domain.isActive
import dev.agentic.domain.isForkAwaitingFirstTurn
import dev.agentic.domain.parseAskQuestions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Immutable UI state for one chat session. The input state machine that used to live as scattered
 * `val`s inside SessionScreen (running/canFollowUp/streaming/locked/composable/canSend) is computed
 * ONCE here in [SessionViewModel.buildUiState] from the single transcript source plus local overlays.
 */
data class SessionUiState(
    val nodes: List<Node> = emptyList(),
    val session: Session? = null,
    val busy: Boolean = false,
    val connecting: Boolean = true,
    /** The initial transcript load failed (server unreachable on open). The screen should show an error
     *  + a retry that calls [SessionViewModel.reload], instead of a permanent blank/spinner. */
    val loadError: Boolean = false,
    val input: String = "",
    val sending: Boolean = false,
    val runs: List<WorkflowRun> = emptyList(),
    val shared: List<AttachmentNode> = emptyList(),
    // derived input-state-machine flags (computed in one place):
    val terminal: Boolean = false,
    val streaming: Boolean = false,
    val composable: Boolean = false,
    val canSend: Boolean = false,
    val workflowActive: Boolean = false,
    val hasRuns: Boolean = false,
    /** Set when a queued turn could not be sent before the drain timed out; the input re-enables and
     *  the UI surfaces this so the user knows it never went through. Cleared on the next submit. */
    val queueError: String? = null,
    /** Files the user picked from the device that haven't been sent yet. Each is shown as a chip above
     *  the text field with its current [UploadState]; submit() awaits any in-flight uploads and embeds
     *  the successful paths in the trailing `[attached: ...]` marker. */
    val attachments: List<PendingAttachment> = emptyList(),
    /** True while at least one pending attachment's upload is in flight. UI uses this to gate Send (so
     *  submit can deterministically read the final set without an async race) and to show a subtle
     *  progress hint on the chip row. */
    val uploading: Boolean = false,
    val hasMore: Boolean = false,
    val loadingEarlier: Boolean = false,
    /** Bounded-window (ended sessions): newer pages were evicted and can be paged back in via
     *  [loadNewer]; drives the transcript's bottom auto-load. */
    val hasNewer: Boolean = false,
    val loadingNewer: Boolean = false,
    val latestEventId: Long = 0,
    val firstEventLine: Long = 0,
)

/** State of the fork action. Surfaced by [SessionViewModel.forkState] and observed by SessionScreen
 *  to drive the Fork button's enabled/disabled state and an inline error. */
sealed interface ForkState {
    data object Idle : ForkState
    data object InFlight : ForkState
    data class Success(val newId: String) : ForkState
    data class Failed(val message: String) : ForkState
}

/** One-shot result of a code-only rewind, surfaced for a toast. Null = nothing to show. */
sealed interface RewindResult {
    data class Done(val turnIndex: Int) : RewindResult
    data class Failed(val message: String) : RewindResult
}

/** One message in a fork's "previous conversation" preview (the collapsible card a forked session
 *  shows at the top). [fromUser] picks the You/Assistant label. */
data class ForkMsg(val fromUser: Boolean, val text: String)

/** The parent (forked-from) conversation a fork displays read-only in its collapsible history card.
 *  [title] is the parent's display title; [messages] is the parent transcript reduced to plain
 *  user/assistant text (tool calls, attachments, etc. are dropped for a clean preview). */
data class ForkParentPreview(val parentId: String, val title: String, val messages: List<ForkMsg>)

internal fun List<dev.agentic.domain.Node>.toForkMessages(): List<ForkMsg> = mapNotNull { n ->
    when (n) {
        is PromptNode -> n.text.takeIf { it.isNotBlank() }?.let { ForkMsg(fromUser = true, it) }
        is AnswerNode -> n.text.takeIf { it.isNotBlank() }?.let { ForkMsg(fromUser = false, it) }
        is TextNode -> n.text.takeIf { it.isNotBlank() }?.let { ForkMsg(fromUser = false, it) }
        else -> null
    }
}

/**
 * The core session ViewModel: chat-input state machine + transcript aggregation.
 *
 * Canonical pattern (Phase 4a): the public [uiState] is a `combine(...).stateIn(WhileSubscribed)` of
 * the repo's hot transcript flow, the two workflow poll flows, and a [local] event-driven flow.
 * NOTHING is collected eagerly in init — the infinite poll flows run only while the View observes
 * [uiState] (WhileSubscribed), so unit tests terminate.
 *
 * Backend branching in events (submit/answerAsk) reads the repo's hot transcript value directly
 * (`sessionsRepo.transcript(id).value`) rather than [uiState] — that value is always current even
 * with no [uiState] subscriber, keeping events correct in both the live UI and pure event tests.
 */
class SessionViewModel(
    private val sessionsRepo: SessionsRepository,
    private val workflowsRepo: WorkflowsRepository,
    private val filesRepo: FilesRepository,
    private val api: AgenticApi,
    private val id: String,
    private val initialPrompt: String? = null,
    // Dispatcher for the O(n) buildUiState combine (moved off Main via flowOn below). Injectable so unit
    // tests can pass their TestDispatcher — Dispatchers.Default escapes the test scheduler and would make
    // uiState emissions non-deterministic under runTest.
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** SavedStateHandle backing this VM, when constructed via the secondary ctor from a nav entry.
     *  Null when constructed via the primary ctor (wide-layout AdaptiveHome right pane has no per-session
     *  nav entry). Retained for forward compatibility (some call sites already pass it) even though
     *  the per-turn settings handoff was removed. */
    private val savedStateHandle: SavedStateHandle? = null,
) : ViewModel() {

    /** Secondary ctor for the NavHost call site (per-session SavedStateHandle from the route args). The
     *  wide-layout (AdaptiveHome) right pane has no per-session nav entry, so it uses the primary ctor
     *  with an explicit id instead. */
    constructor(
        sessionsRepo: SessionsRepository,
        workflowsRepo: WorkflowsRepository,
        filesRepo: FilesRepository,
        api: AgenticApi,
        handle: SavedStateHandle,
        computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(
        sessionsRepo, workflowsRepo, filesRepo, api,
        requireNotNull(handle.get<String>("id")) { "SessionViewModel requires an 'id' arg" },
        handle.get<String>("initialPrompt"),
        computeDispatcher,
        handle,
    )

    /** This session's id, exposed so the screen can show it under the title (helps when reporting
     *  a stuck/odd session — name the exact id). */
    val sessionId: String get() = id

    /** Event-driven local overlays merged into [uiState] (input text, send/queue state, optimistic
     *  prompt, optimistic ask answers). Mutated by events via [MutableStateFlow.update]. */
    private data class Local(
        val input: String = "",
        val sending: Boolean = false,
        val optimisticPrompt: String? = null,
        /** Texts of just-sent follow-up prompts shown optimistically at the END of the transcript
         *  until the backend echoes a matching real PromptNode. Distinct from [optimisticPrompt]
         *  (which is the FIRST/initial prompt and is structurally suppressed once any PromptNode
         *  exists — that guard would hide follow-up echoes). */
        val pendingSent: List<String> = emptyList(),
        // Keyed by a stable string derived from the ask's questions (NOT the mutable AskNode) so the
        // override survives markAnsweredAsks flipping answered/answer. A String key also sidesteps the
        // List-as-Iterable overload ambiguity that Map<List<…>,…> has with plus/minus.
        val answeredOverrides: Map<String, String> = emptyMap(),
        /** Optimistic perm/plan decisions, keyed by the request id, applied until the backend's
         *  permResolved marker lands (harmless once both agree). */
        val permDecided: Map<String, String> = emptyMap(),
        /** Set when the drain exhausts without sending; surfaced via UiState.queueError. */
        val queueError: String? = null,
        /** Files the user attached but hasn't sent. Each entry's [UploadState] tracks upload progress;
         *  successful uploads (Done) get appended to the next prompt's `[attached: ...]` marker, failed
         *  ones stay so the user can retry/remove. Cleared on successful submit. */
        val attachments: List<PendingAttachment> = emptyList(),
    )

    private val local = MutableStateFlow(
        // Seed the textfield from any persisted draft so re-entering the session restores what was typed.
        Local(input = sessionsRepo.draftFor(id), optimisticPrompt = initialPrompt),
    )

    val uiState: StateFlow<SessionUiState> =
        combine(
            // RE-ACQUIRE the transcript flow on every (re)subscription instead of capturing it once.
            // The repo's idle reaper can release(id) the flow (cancelling its live stream) whenever this
            // uiState has had no collector for a while — which happens routinely on a foldable, where a
            // fold/unfold is a config change that tears down + recreates this screen and the
            // WhileSubscribed(5s) grace can lapse before the new collector attaches. A captured reference
            // would then be permanently dead (frozen transcript, no stream) until an app restart. Wrapping
            // in `flow { emitAll(transcript(id)) }` means each re-collection calls transcript(id) again,
            // which returns the live cached flow or RE-CREATES it (fresh load + stream) if it was reaped —
            // so the screen self-heals on the next foreground/recompose. (transcript(id) is idempotent when
            // the flow already exists, so this adds no churn in the steady state.)
            flow { emitAll(sessionsRepo.transcript(id)) },
            workflowsRepo.runsStream(id),
            workflowsRepo.outboxStream(id),
            local,
            // Polls the authoritative session row every 2s WHILE this uiState is observed, folding it
            // into the transcript flow (refreshSession). Without it, session-derived UI (error banner,
            // tags, title, input state, cost) stays frozen at the last reseed for the whole live turn —
            // e.g. a resumed turn kept showing a stale error banner. Its ticks carry no data; the fresh
            // session arrives via the transcript arm above. Scoped to WhileSubscribed so it never leaks.
            sessionsRepo.sessionRefreshStream(id),
        ) { t, runs, shared, l, _ -> buildUiState(t, runs, shared, l) }
            // Run the O(n) buildUiState (interleaveShared + the nodes==stableNodes compare) OFF the main
            // thread; only the resulting StateFlow emission hops back to Main for rendering. This was the
            // streaming jank: per-token deltas ran the whole-transcript rebuild on Dispatchers.Main.
            // Bonus: with the collector now on a background dispatcher, the transcript StateFlow (already
            // conflated) naturally skips intermediate per-token states when buildUiState can't keep up,
            // so a token flood no longer forces one recomposition per token. Safe: the upstream is one
            // sequential coroutine (no concurrent stableNodes write) and the lone side effect,
            // clearMatchedPending, is an atomic local.update.
            .flowOn(computeDispatcher)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SessionUiState())

    /** Read-only preview of the parent (forked-from) conversation for the collapsible "previous
     *  conversation" card a fork shows at the top. Null unless this session is a fork. Reacts to the
     *  fork's own session load (to learn parentSessionId), then reuses the shared transcript(parentId)
     *  flow so the parent's log loads through the same cached path — no new API surface. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val parentPreview: StateFlow<ForkParentPreview?> =
        flow { emitAll(sessionsRepo.transcript(id)) }
            .map { it.session?.parentSessionId }
            .distinctUntilChanged()
            .flatMapLatest { pid ->
                if (pid.isNullOrBlank()) flowOf(null)
                else sessionsRepo.transcript(pid).map { pt ->
                    ForkParentPreview(
                        parentId = pid,
                        title = (pt.session?.prompt ?: pid).take(80),
                        messages = pt.nodes.toForkMessages(),
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /** Stable transcript reference (see [buildUiState]): reused when content is unchanged so the 2s
     *  session-refresh and 2.5s workflow poll ticks don't hand [Transcript] a fresh List instance every
     *  couple seconds (which would recompose it + recompute its remember(nodes) work — the periodic hitch). */
    private var stableNodes: List<Node> = emptyList()

    /** Builder: merges shared files into the transcript, applies optimistic overlays, and computes
     *  ALL derived input-state flags in one place (mirrors SessionScreen:505-510, 757-767). Mostly
     *  pure; the ONE side effect is [clearMatchedPending], which prunes sent-echo overlays once the
     *  real PromptNode arrives (converges in one extra emission — see [unmatchedPending]). */
    private fun buildUiState(
        t: TranscriptState,
        runs: List<WorkflowRun>,
        shared: List<AttachmentNode>,
        l: Local,
    ): SessionUiState {
        // 1. Merge outbox files into the transcript at the turn that produced them (domain logic).
        // hasMore = the loaded window is start-truncated (older events above): an anchorless file is
        // then HIDDEN like any other out-of-window card — paging back reveals it at its true position
        // (see interleaveShared KDoc) — instead of gluing to the streamed bottom.
        var nodes: List<Node> = interleaveShared(t.nodes, shared, truncatedStart = t.hasMore)
        // 2. Optimistic prompt: show the just-submitted INITIAL prompt until a real PromptNode lands.
        if (l.optimisticPrompt != null && nodes.none { it is PromptNode }) {
            nodes = listOf(PromptNode(l.optimisticPrompt)) + nodes
        }
        // 2b. Optimistic SENT echo: show each just-sent FOLLOW-UP prompt at the END (newest) of the
        // transcript until the backend echoes a matching real PromptNode, then drop the overlay so
        // there is no duplicate. [unmatchedPending] keeps each pending text only while no real
        // PromptNode of the same text remains unaccounted-for (one real echo cancels one overlay).
        if (l.pendingSent.isNotEmpty()) {
            // Match against the REAL transcript prompts (t.nodes), not the overlaid `nodes`, so the
            // optimistic INITIAL prompt prepended in step 2 can never count as a follow-up echo.
            val realPromptTexts = t.nodes.filterIsInstance<PromptNode>().map { it.text }
            val stillPending = unmatchedPending(l.pendingSent, realPromptTexts)
            if (stillPending.size != l.pendingSent.size) clearMatchedPending(realPromptTexts)
            nodes = nodes + stillPending.map { PromptNode(it) }
        }
        // 3. Optimistic ask answers: replace matching AskNodes with answered copies.
        if (l.answeredOverrides.isNotEmpty()) {
            nodes = nodes.map { n ->
                if (n is AskNode) l.answeredOverrides[askKey(n)]?.let { n.copy(answered = true, answer = it) } ?: n else n
            }
        }
        // 3b. Optimistic permission decisions: mark matching Perm/Plan nodes decided.
        if (l.permDecided.isNotEmpty()) {
            nodes = nodes.map { n ->
                when (n) {
                    is PermNode -> l.permDecided[n.id]?.let { n.copy(decided = true, decision = it) } ?: n
                    is PlanNode -> l.permDecided[n.id]?.let { n.copy(decided = true, decision = it) } ?: n
                    else -> n
                }
            }
        }

        val session = t.session
        val terminal = session != null && session.status in TERMINAL
        val streaming = session?.awaitingInput != null
        val running = session != null && !terminal
        // A terminal session can take a follow-up if it has a claudeSessionId to --resume, OR it is a
        // fork still waiting for its first turn (parentSessionId set, no claudeSessionId yet). Without
        // the fork case the input bar is hidden and the "stuck terminal" Retry card shows instead — so
        // a freshly-forked session looks broken and can't be continued. See isForkAwaitingFirstTurn.
        val canFollowUp = terminal && (session?.claudeSessionId != null ||
            isForkAwaitingFirstTurn(session?.status.orEmpty(), session?.claudeSessionId, session?.parentSessionId))
        val workflowActive = runs.any { it.isActive() } || (runs.isEmpty() && nodes.any { it is WorkflowNode })
        // A session that can take input: a non-terminal session (`running` covers both "running" and
        // "pending" — the persistent process accepts input mid-turn as type-ahead, and the server treats
        // any pending/running status as active in its own is_busy), a resumable terminal one
        // (canFollowUp), or one with a live workflow. `running` is the fix for the "no send button" bug:
        // awaitingInput is a LIVE-ONLY server signal that is null whenever the session isn't held in the
        // engine (an orphaned running row, a turn reaped before it registered) — so composability must
        // follow the non-terminal status, NOT `streaming` alone, or the input bar shows no button at all.
        val composable = streaming || running || canFollowUp || workflowActive
        // Send is enabled when the user has typed something OR has at least one attachment whose upload
        // is done (the marker still gets emitted) AND no send is in flight AND no upload is still in
        // flight — submit() awaits uploads, but disabling Send while `uploading` is true keeps the chip
        // row's progress state stable and avoids a UX where Send appears enabled then immediately jumps
        // disabled the moment the user taps it.
        val anyDone = l.attachments.any { it.state is UploadState.Done }
        val canSend = composable && !l.sending && (l.input.isNotBlank() || anyDone)
        val hasRuns = runs.isNotEmpty() || nodes.any { it is WorkflowNode }
        val uploading = l.attachments.any { it.state is UploadState.Uploading }

        // Reuse the PREVIOUS list instance when transcript content is unchanged. buildUiState runs on
        // every combine tick — including the 2s session-refresh poll (added 06-21) and the 2.5s workflow
        // polls — and interleaveShared() always allocates a NEW list. Handing Transcript a fresh instance
        // each tick (List is an unstable Compose type → compared by reference) made it recompose and
        // recompute its remember(nodes) work (spawnOrdinals, userMessageAnchors) every couple seconds:
        // the "几秒钟卡一次" periodic hitch. A metadata-only tick now yields the same instance → Transcript
        // skips. Equality is an O(n) value compare — far cheaper than recomposing the list.
        // 4. Authoritative awaiting-prompt: if the server says this session is PARKED on an
        // AskUserQuestion (the source of truth — set whenever the engine holds a pending ask) but the
        // transcript (possibly rebuilt from a warm reseed) doesn't already show that unanswered ask,
        // render it from the authoritative payload so the picker is never lost to a log-reconstruction
        // gap. Deduped by question content, so it never doubles the reducer's own ask card.
        (session?.pendingPrompt as? JsonObject)?.let { pp ->
            if (pp["kind"]?.jsonPrimitive?.contentOrNull == "ask") {
                val qs = parseAskQuestions(pp["questions"] as? JsonArray)
                if (qs.isNotEmpty() && nodes.none { it is AskNode && !it.answered && it.questions == qs }) {
                    nodes = nodes + AskNode(qs)
                }
            }
        }

        val outNodes = if (nodes == stableNodes) stableNodes else nodes.also { stableNodes = it }
        return SessionUiState(
            nodes = outNodes,
            session = session,
            busy = t.busy,
            connecting = t.connecting,
            loadError = t.loadError,
            input = l.input,
            sending = l.sending,
            runs = runs,
            shared = shared,
            terminal = terminal,
            streaming = streaming,
            composable = composable,
            canSend = canSend,
            workflowActive = workflowActive,
            hasRuns = hasRuns,
            queueError = l.queueError,
            attachments = l.attachments,
            uploading = uploading,
            hasMore = t.hasMore,
            loadingEarlier = t.loadingEarlier,
            hasNewer = t.hasNewer,
            loadingNewer = t.loadingNewer,
            latestEventId = t.latestEventId,
            firstEventLine = t.firstEventLine,
        )
    }

    /** Pending sent-echo texts that have NOT yet been matched by a real transcript prompt. One real
     *  prompt of a given text cancels exactly one pending overlay of that text (handles duplicates). */
    private fun unmatchedPending(pending: List<String>, realPromptTexts: List<String>): List<String> {
        // Match on a NORMALIZED form, not raw equality: the real PromptNode.text is produced by the
        // reducer's splitAttachments (trailing "[attached: ...]" marker stripped + trimEnd), while a
        // pendingSent text is the raw input.trim(). Comparing raw strings would miss the match when
        // the echo carries an attachment marker or extra whitespace, leaving BOTH the optimistic and
        // the real prompt rendered (the duplicate). Normalize both sides identically so one real
        // echo cancels exactly one pending overlay.
        val available = realPromptTexts.map { normalizePrompt(it) }.toMutableList()
        val still = ArrayList<String>(pending.size)
        for (text in pending) {
            // Remove the normalized match but keep the ORIGINAL pending text for display fidelity.
            if (!available.remove(normalizePrompt(text))) still.add(text)
        }
        return still
    }

    /** Canonical form for echo-matching: drop the trailing "[attached: ...]" marker the same way the
     *  transcript reducer does, then trim. Makes pending-vs-real matching whitespace/marker-insensitive. */
    private fun normalizePrompt(text: String): String = splitAttachments(text).first.trim()

    /** Drop from [local] every pending sent-echo text now matched by a real transcript prompt.
     *  Recomputed against the CURRENT local value (not the builder's snapshot) so concurrent submits
     *  are not lost. */
    private fun clearMatchedPending(realPromptTexts: List<String>) {
        local.update { it.copy(pendingSent = unmatchedPending(it.pendingSent, realPromptTexts)) }
    }

    // ── Events ──────────────────────────────────────────────────────────────────

    /** Sessions offered by the composer's @-mention dropdown: every session EXCEPT this one,
     *  most-recently-active first. Refreshed on demand by [refreshMentionCandidates]; the last
     *  fetched list stays available so the dropdown opens instantly while a refresh is in flight. */
    private val _mentionCandidates = MutableStateFlow<List<Session>>(emptyList())
    val mentionCandidates: StateFlow<List<Session>> = _mentionCandidates.asStateFlow()

    /** Single-flight guard for [refreshMentionCandidates] — typing inside an active mention fires
     *  the effect repeatedly; one GET at a time is plenty for a dropdown. */
    private var mentionFetch: Job? = null

    /** Fetch the sessions list for the @-mention dropdown. Best-effort: on failure the previous
     *  (possibly empty) list is kept — the dropdown just shows what it has. */
    fun refreshMentionCandidates() {
        if (mentionFetch?.isActive == true) return
        mentionFetch = viewModelScope.launch {
            runCatching { sessionsRepo.sessions() }.onSuccess { all ->
                _mentionCandidates.value = all
                    .filter { it.id != id }
                    .sortedByDescending { maxOf(it.lastUserMessageAt, it.createdAt) }
            }
        }
    }

    fun onInput(s: String) {
        sessionsRepo.setDraft(id, s)   // persist (memory + disk) so leaving/backgrounding + returning restores it
        local.update { it.copy(input = s) }
    }

    /** In-flight upload coroutines, keyed by [PendingAttachment.id] (the URI string). Held so a user
     *  removal mid-upload can cancel the network call instead of wasting the bandwidth and leaving a
     *  stray file in `<worktree>/uploads/`. Mutated from attachFiles, removePending, and the upload
     *  completion callback. Access is synchronized because those three paths can race on the main
     *  thread (attachFiles in particular spawns N coroutines that each finish independently). */
    private val uploadJobs = mutableMapOf<String, Job>()

    /** Pick [uris] from the device and start uploading them in parallel. Each URI becomes a
     *  [PendingAttachment] in [Local.attachments] (state Pending → Uploading → Done/Failed). The
     *  display name and size are queried synchronously via [resolver] so the chip can show them right
     *  away; failures during that query still produce a chip with a generic name and size=-1.
     *
     *  Called from the View via ActivityResultContracts.OpenMultipleDocuments. The contract hands us
     *  read-granted URIs scoped to our activity — we read them once into a ByteArray (so we don't
     *  depend on URI persistence past process death) and POST to the existing upload endpoint. */
    fun attachFiles(uris: List<Uri>, resolver: ContentResolver) {
        if (uris.isEmpty()) return
        AppLog.d("VM", "attachFiles id=${id.take(8)} count=${uris.size}")
        val newOnes = uris.map { uri ->
            val (name, size) = queryDisplayNameAndSize(resolver, uri)
            PendingAttachment.of(uri, name, size)
        }
        local.update { it.copy(attachments = it.attachments + newOnes) }
        for (att in newOnes) launchUpload(att, resolver)
    }

    /** Drop [id] from the pending list. If its upload is still in flight, cancel it so we don't
     *  leave an orphan on the server's uploads dir. Idempotent — removing twice is a no-op. */
    fun removePending(id: String) {
        synchronized(uploadJobs) { uploadJobs.remove(id) }?.cancel()
        local.update { l -> l.copy(attachments = l.attachments.filterNot { it.id == id }) }
    }

    /** Spawn the upload coroutine for [att]. State transitions are emitted through `local.update` so
     *  the chip row recomposes; on completion the entry is dropped from [uploadJobs] regardless of
     *  outcome (so a retry path that re-launches the same URI can register fresh). */
    private fun launchUpload(att: PendingAttachment, resolver: ContentResolver) {
        // Flip to Uploading so the chip shows progress; the Pending state is usually only visible for
        // a single frame and is mostly defensive.
        local.update { l ->
            l.copy(attachments = l.attachments.map { if (it.id == att.id) it.copy(state = UploadState.Uploading) else it })
        }
        val job = viewModelScope.launch {
            val outcome = runCatching {
                val bytes = readBytes(resolver, att.localUri)
                filesRepo.upload(id, bytes, att.displayName)
            }
            // Treat any thrown exception as a Failure (e.g. resolver.openInputStream returning null,
            // OOM, IO error) — the user sees a Failed chip with the message and can retry/remove.
            val finalState: UploadState = outcome.fold(
                onSuccess = { r ->
                    when (r) {
                        is Outcome.Success -> UploadState.Done(r.value).also {
                            AppLog.d("VM", "launchUpload id=${id.take(8)} uri=${att.localUri} => OK path=${r.value}")
                        }
                        is Outcome.Failure -> UploadState.Failed(r.error.userMessage()).also {
                            AppLog.w("VM", "launchUpload id=${id.take(8)} uri=${att.localUri} => FAILED: ${r.error.userMessage()}")
                        }
                    }
                },
                onFailure = { e ->
                    AppLog.w("VM", "launchUpload id=${id.take(8)} uri=${att.localUri} => FAILED: ${e.message ?: "unknown"}", e)
                    UploadState.Failed(e.message ?: "Couldn't read file")
                },
            )
            local.update { l ->
                l.copy(attachments = l.attachments.map { if (it.id == att.id) it.copy(state = finalState) else it })
            }
            synchronized(uploadJobs) { uploadJobs.remove(att.id) }
        }
        synchronized(uploadJobs) { uploadJobs[att.id] = job }
    }

    /** Read a SAF/document URI to bytes. ContentResolver handles both `content://` and `file://` URIs,
     *  so this is the single read path for all picker results. Throws if the stream can't be opened
     *  (the picker usually guarantees read permission for the lifetime of the result, so this only
     *  fires on truly broken inputs). */
    private fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot open $uri")

    /** Best-effort display name + size for a picker URI. SAF providers are encouraged to support the
     *  OpenableColumns projection; if a provider doesn't, we fall back to the URI's last path segment
     *  and size=-1 (the chip still renders fine, just without a byte count). */
    private fun queryDisplayNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: "file"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx) ?: name
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    /** One-shot download effects. The VM owns the network fetch; the View owns the platform save (it
     *  has the Context) and shows the result. BUFFERED so an emit before the View collects is kept. */
    private val _downloads = Channel<DownloadEffect>(Channel.BUFFERED)
    val downloads: Flow<DownloadEffect> = _downloads.receiveAsFlow()

    /** Per-attachment download progress, keyed by [AttachmentNode.path]. A present key means that file is
     *  downloading — its value is the 0f..1f fraction, or null when the size is unknown (indeterminate bar);
     *  an absent key means idle. The map's keys ALSO serve as the set of in-flight paths, so it does double
     *  duty:
     *   - a repeat tap on a file already downloading is ignored (no double save), and
     *   - two DIFFERENT files can download at once, each with its own bar, instead of sharing — and tearing
     *     down — one slot.
     *  A single shared slot used to flicker: two coroutines interleaved their writes, and whichever finished
     *  first nulled the slot out from under the other. */
    private val _downloadProgress = MutableStateFlow<Map<String, Float?>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float?>> = _downloadProgress

    /** State of the fork action. Idle/InFlight/Success/Failed — observed by SessionScreen to drive the
     *  Fork button's enabled/disabled state and an inline error. */
    private val _forkState = MutableStateFlow<ForkState>(ForkState.Idle)
    val forkState: StateFlow<ForkState> = _forkState.asStateFlow()

    /** One-shot navigation signal — set when a fork completes successfully. The UI collects it,
     *  navigates to the new session, then calls [acknowledgeFork] so the value doesn't re-trigger
     *  navigation on a subsequent re-read (config change, screen return). Mirrors the one-shot
     *  download-effect pattern (the VM owns the network call; the View owns the navigation).
     *
     *  MUST be an observable StateFlow, NOT a plain `var`: the consuming LaunchedEffect lives in a
     *  composition scope that the fork success does not otherwise recompose (forkState is read in a
     *  separate scope), so a plain-var mutation would never be observed and navigation would never
     *  fire. */
    private val _forkedTo = MutableStateFlow<String?>(null)
    val forkedTo: StateFlow<String?> = _forkedTo.asStateFlow()

    /** Clear [forkedTo] after the UI has consumed it. Call once on the receiving side of navigation. */
    fun acknowledgeFork() { _forkedTo.value = null }

    /** Reset [forkState] to Idle after the UI has shown a fork error. Prevents the (StateFlow-held)
     *  Failed value from re-toasting every time the screen is recomposed/re-entered. No-op unless
     *  currently Failed, so it can't clobber an InFlight/Success. */
    fun acknowledgeForkError() {
        if (_forkState.value is ForkState.Failed) _forkState.value = ForkState.Idle
    }

    /** One-shot rewind result for the UI to toast; cleared via [acknowledgeRewind]. */
    private val _rewindResult = MutableStateFlow<RewindResult?>(null)
    val rewindResult: StateFlow<RewindResult?> = _rewindResult.asStateFlow()
    /** True while a rewind request is in flight (the confirm dialog disables its button). */
    private val _rewinding = MutableStateFlow(false)
    val rewinding: StateFlow<Boolean> = _rewinding.asStateFlow()

    /** Restore the working tree to the snapshot before [turnIndex] (code only). On completion the
     *  result lands in [rewindResult] for a toast. Ignored if one is already in flight. */
    fun rewind(turnIndex: Int) {
        AppLog.d("VM", "rewind id=${id.take(8)} turnIndex=$turnIndex")
        if (_rewinding.value) return
        _rewinding.value = true
        viewModelScope.launch {
            // try/finally so _rewinding is always cleared, even on cancellation or an unexpected throw.
            try {
                _rewindResult.value = when (val r = filesRepo.rewind(id, turnIndex)) {
                    is Outcome.Success -> RewindResult.Done(turnIndex).also {
                        AppLog.d("VM", "rewind id=${id.take(8)} turnIndex=$turnIndex => OK")
                    }
                    is Outcome.Failure -> RewindResult.Failed(r.error.userMessage()).also {
                        AppLog.w("VM", "rewind id=${id.take(8)} turnIndex=$turnIndex => FAILED: ${r.error.userMessage()}")
                    }
                }
            } finally {
                _rewinding.value = false
            }
        }
    }

    /** Clear the one-shot [rewindResult] after the UI has shown it. */
    fun acknowledgeRewind() { _rewindResult.value = null }

    /** Fetch an attachment's raw bytes for an INLINE preview (e.g. an image thumbnail) — no save, no
     *  toast. Best-effort: returns null on any failure so the card silently falls back to its icon.
     *  Routed through the VM (not called from the composable) per the stateless-screen rule. */
    suspend fun attachmentBytes(node: AttachmentNode): ByteArray? =
        runCatching { filesRepo.fileBytes(id, node.path) }.getOrNull()

    /** Download the outbox file [node] points at, then hand the bytes to the View to save. Routed
     *  through the VM (not called from the composable) per the stateless-screen rule. The repo's
     *  per-request timeout means a stalled transfer surfaces as [DownloadEffect.Failed], not a hang. */
    fun downloadAttachment(node: AttachmentNode) {
        AppLog.d("VM", "downloadAttachment id=${id.take(8)} path=${node.path}")
        // Ignore a repeat tap while this exact file is already downloading (its path is already a key in the
        // progress map). Marked SYNCHRONOUSLY here — before the launch — so two taps arriving back-to-back on
        // the main thread can't both get past: the second sees the key already present. Covers both
        // affordances (the trailing icon AND the card-body onPrimary both call downloadAttachment).
        if (node.path in _downloadProgress.value) return
        _downloadProgress.update { it + (node.path to null) }
        val name = node.path.substringAfterLast('/')
        viewModelScope.launch {
            _downloads.send(DownloadEffect.Started(name))
            try {
                val bytes = filesRepo.fileBytes(id, node.path) { fraction ->
                    // update {} is an atomic CAS, so this is safe even though the callback can fire off the
                    // main thread and a concurrent download of another file may touch the same map.
                    _downloadProgress.update { it + (node.path to fraction) }
                }
                AppLog.d("VM", "downloadAttachment id=${id.take(8)} path=${node.path} => OK")
                _downloads.send(DownloadEffect.Ready(name, bytes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("VM", "downloadAttachment id=${id.take(8)} path=${node.path} => FAILED: ${e.message ?: "download failed"}")
                _downloads.send(DownloadEffect.Failed(name, e.message ?: "download failed"))
            } finally {
                // Clear ONLY this file's entry (runs on normal completion, failure, and cancellation), so a
                // concurrent download of another file keeps its own bar and this file can be fetched again.
                _downloadProgress.update { it - node.path }
            }
        }
    }

    /** Acknowledge the current "your turn" point for this session. Discord-style: sets
     *  lastAckedEventId to the session's current [Session.unreadEventId], which is a monotonic
     *  server-side counter. Called on ENTER (selecting/opening = reading).
     *
     *  Takes [eventId] from the caller (Compose state) rather than reading uiState internally,
     *  to avoid a race where the LaunchedEffect fires on `s.session?.unreadEventId` but
     *  `uiState.value.session` hasn't caught up yet — that would return early. */
    fun ackEvent(eventId: Long) {
        AppLog.v("Read", "ackEvent id=${id.take(8)} eventId=$eventId")
        viewModelScope.launch {
            // NonCancellable: a fast back-out clears the ViewModel and would cancel an in-flight
            // ack PUT — the session the user just read would then come back as unread (stale dot /
            // spurious completion cue on the next list refresh). Let the small request finish.
            // withTimeoutOrNull bounds it (review feedback): NonCancellable keeps the cleared VM
            // reachable until the call returns, so a hung connection must not pin it forever —
            // the timeout still fires inside NonCancellable (it cancels its own child job).
            withContext(NonCancellable) {
                try {
                    withTimeoutOrNull(5_000L) { api.ackSession(id, eventId) }
                } catch (_: Exception) { /* best-effort: reopening the session re-acks (self-heals) */ }
            }
        }
    }

    /** Load earlier messages — triggers a page fetch before the oldest loaded event,
     *  prepends events, and re-seeds the reducer. */
    fun loadEarlier() {
        viewModelScope.launch { sessionsRepo.loadEarlier(id) }
    }

    /** Load newer messages — for an ended session whose bounded window evicted its newest pages,
     *  pages a newer page back in and re-seeds the reducer. No-op otherwise. */
    fun loadNewer() {
        viewModelScope.launch { sessionsRepo.loadNewer(id) }
    }

    /** Stop button: interrupt the current turn but keep the session alive (the persistent process
     *  stays up and idle, ready for the next message) — not kill(), which would end the session. */
    fun stop() {
        AppLog.d("VM", "stop id=${id.take(8)}")
        viewModelScope.launch { sessionsRepo.interrupt(id) }
    }

    /** Retry the initial transcript load after a first-load failure (uiState.loadError). The screen wires
     *  this to a retry button so a transient server error doesn't leave the session permanently blank. */
    fun reload() {
        AppLog.d("VM", "reload id=${id.take(8)}")
        sessionsRepo.reload(id)
    }

    /** Force a fresh transcript reseed on screen resume / re-entry (wired to ON_RESUME in SessionScreen).
     *  Fixes warm-return staleness — most visibly a parked AskUserQuestion whose picker card vanished
     *  after backgrounding (or switching to another session and back) and stuck until the app was killed.
     *  Rebuilds from the authoritative log via the same path a cold start uses. */
    fun refresh() {
        AppLog.d("VM", "refresh id=${id.take(8)}")
        sessionsRepo.refresh(id)
    }

    /** Send the typed input as the next turn. Branches on the live backend state read straight from
     *  the repo's hot transcript value (current even with no uiState subscriber). When the user has
     *  attached files, awaits any in-flight uploads, then composes the trailing `[attached: ...]`
     *  marker (matching the regex in `splitAttachments`) with the successfully uploaded paths. */
    fun submit() {
        AppLog.d("VM", "submit id=${id.take(8)}")
        val text = local.value.input.trim()
        val attachmentsNow = local.value.attachments
        val hasReadyAttachment = attachmentsNow.any { it.state is UploadState.Done }
        if (text.isBlank() && !hasReadyAttachment) return

        // Snapshot upload jobs that are currently in flight. We join them before composing the prompt
        // so the marker reflects their final state; failures are filtered out and the user can retry.
        val jobsToAwait = synchronized(uploadJobs) { uploadJobs.values.toList() }
        // The optimistic echo stores the marker-FREE text so it matches the server's echo after the
        // reducer strips the marker (see normalizePrompt). Storing the marker too would break the
        // pending-vs-real match when uploads finish between snapshot and join — the echo's marker
        // would list fewer paths than the real prompt.
        val pendingText = text

        // Clear the input (and its persisted draft) and any stale send error now that we are sending.
        sessionsRepo.clearDraft(id)
        // Optimistic SENT echo so the just-sent message shows immediately (Fix 1). The persistent stream
        // injects the turn (live, mid-turn = type-ahead) or resumes a terminal session — no client queue.
        local.update {
            it.copy(input = "", queueError = null, sending = true, pendingSent = it.pendingSent + pendingText)
        }
        viewModelScope.launch {
            // Wait for any uploads that were in flight when the user tapped Send. joinAll does NOT
            // propagate failure — a single failed upload just leaves its attachment in the Failed state
            // for the user to retry/remove. `attachFiles` cancels via removePending, so a removal
            // mid-submit becomes a CancellationException here, which we re-raise to abort the send.
            try {
                jobsToAwait.forEach { it.join() }
            } catch (e: CancellationException) {
                throw e
            }
            // Re-read after the joins: Done paths are final; Failed attachments stay in the list so the
            // user can retry/remove them; Uploading attachments (shouldn't happen post-join) are kept too.
            val finalAtts = local.value.attachments
            val full = composePromptWithMarker(text, finalAtts)
            sendFollowUp(full, pendingText)
            // Clear every attachment that successfully uploaded — they live on the server now and the
            // next message's marker should reference NEW uploads, not stale ones.
            local.update { l -> l.copy(attachments = l.attachments.filter { it.state !is UploadState.Done }) }
        }
    }

    /** Build the prompt body the server will see: `<user text>` plus a trailing `[attached: a, b]`
     *  marker line listing the server-side paths of every Done attachment. The marker is optional and
     *  appended only when at least one attachment is Done — matches what `splitAttachments` parses on
     *  the reducer side, so the optimistic echo and the eventual server echo normalize to the same
     *  PromptNode text. */
    private fun composePromptWithMarker(text: String, attachments: List<PendingAttachment>): String {
        val paths = attachments.mapNotNull { (it.state as? UploadState.Done)?.path }
        if (paths.isEmpty()) return text
        val marker = "[attached: ${paths.joinToString(", ")}]"
        return if (text.isEmpty()) "\n\n$marker" else "$text\n\n$marker"
    }

    /** Send a follow-up and reconcile the optimistic echo with the result. On Failure: drop the one
     *  optimistic pendingSent overlay we added, put the un-sent text back in the input (unless the
     *  user already typed something new), and surface an error — so the message is never silently
     *  lost and never falsely shown as sent. Always clears `sending`.
     *
     *  No per-turn model/effort/mode/permissionMode overrides are sent here: the SessionSettings
     *  subpage is session-persistent only, so the server-side per-turn mechanism (Tasks 4-7 of the
     *  session-settings plan) stays in place but is intentionally unused from Android. The repo's
     *  followUp signature still accepts those nullable fields — we just call it without them.
     *
     *  `full` is the actual prompt sent (may include the `[attached: ...]` marker); `pendingText`
     *  is the marker-free text used as the pendingSent overlay so the rollback removes the right
     *  entry even when uploads finish mid-send and shift the marker's path list. */
    private suspend fun sendFollowUp(full: String, pendingText: String) {
        // setTitle=false: the session title is owned by the server — it is
        // auto-generated at submit and periodically refreshed (auto-retitle,
        // every 5 messages). Sending setTitle=true here would overwrite that
        // summary with the raw follow-up text on every message, which is what
        // made the title always equal the user's latest message and made the
        // (re)title feature look broken.
        val r = sessionsRepo.followUp(id, full, setTitle = false)
        if (r is Outcome.Success) {
            AppLog.d("VM", "submit id=${id.take(8)} => OK")
        } else {
            AppLog.w("VM", "submit id=${id.take(8)} => FAILED: ${followUpErrorMessage((r as Outcome.Failure).error)}")
        }
        local.update { l ->
            if (r is Outcome.Success) {
                l.copy(sending = false)
            } else {
                val rolledBack = l.pendingSent.toMutableList().apply { remove(pendingText) }
                l.copy(
                    sending = false,
                    pendingSent = rolledBack,
                    input = if (l.input.isBlank()) full else l.input,
                    queueError = followUpErrorMessage((r as Outcome.Failure).error),
                )
            }
        }
    }

    /** Optimistically mark [node] answered with [answer], then send it as a (non-title) follow-up.
     *  Guarded: if the session can't take a turn (not live and not a resumable terminal session),
     *  surface an error instead of optimistically answering and then rolling back a doomed send. */
    fun answerAsk(node: AskNode, answer: String) {
        AppLog.d("VM", "answerAsk id=${id.take(8)}")
        val session = sessionsRepo.transcript(id).value.session
        val streaming = session?.awaitingInput != null
        val canFollowUp = session != null && session.status in TERMINAL && session.claudeSessionId != null
        if (!streaming && !canFollowUp) {
            local.update { it.copy(queueError = "This session can't take an answer right now.") }
            return
        }
        val key = askKey(node)
        // Guard against a double-submit of the SAME ask: once an answer for this ask is optimistically
        // recorded, ignore the repeat call so it is never POSTed as two separate follow-up turns. Each
        // follow-up is stamped with its own server `at`, and the transcript's prompt-dedup keys on `at`,
        // so two turns cannot merge and the answer would show as two identical user bubbles. The
        // check-and-set is done INSIDE update() — a compareAndSet loop — so it stays atomic even if two
        // answerAsk calls ever race (the lambda may re-run, so it re-reads the flag each pass; the
        // committed pass is authoritative). Cleared on send failure (rollback below) so retry still works.
        var alreadyAnswering = false
        local.update { st ->
            alreadyAnswering = st.answeredOverrides.containsKey(key)
            if (alreadyAnswering) st else st.copy(answeredOverrides = st.answeredOverrides + (key to answer))
        }
        if (alreadyAnswering) return
        viewModelScope.launch {
            val r = sessionsRepo.followUp(id, answer, setTitle = false)
            if (r is Outcome.Success) {
                AppLog.d("VM", "answerAsk id=${id.take(8)} => OK")
            } else {
                AppLog.w("VM", "answerAsk id=${id.take(8)} => FAILED: ${(r as Outcome.Failure).error.userMessage()}")
                local.update {
                    it.copy(
                        answeredOverrides = it.answeredOverrides - key,
                        queueError = "Couldn't send your answer — try again.",
                    )
                }
            }
        }
    }

    /** Answer a parked permission/plan prompt: optimistically mark the card decided, then POST it.
     *  feedback is the deny reason / plan-revision note (null on a plain allow). On failure the
     *  overlay is rolled back and queueError is set so the user knows to retry. */
    fun respondPermission(permissionId: String, decision: String, feedback: String? = null) {
        AppLog.d("VM", "respondPermission sessionId=${id.take(8)} permissionId=$permissionId decision=$decision")
        local.update { it.copy(permDecided = it.permDecided + (permissionId to decision)) }
        viewModelScope.launch {
            val r = sessionsRepo.respondPermission(id, decision, feedback)
            if (r is Outcome.Success) {
                AppLog.d("VM", "respondPermission sessionId=${id.take(8)} => OK")
            } else {
                AppLog.w("VM", "respondPermission sessionId=${id.take(8)} => FAILED: ${(r as Outcome.Failure).error.userMessage()}")
                local.update {
                    it.copy(
                        permDecided = it.permDecided - permissionId,
                        queueError = "Couldn't send your response — try again.",
                    )
                }
            }
        }
    }

    /** Stable key for an ask's optimistic-answer override: depends only on the (immutable) questions,
     *  so it is unaffected by markAnsweredAsks flipping the node's answered/answer. */
    private fun askKey(node: AskNode): String = node.questions.toString()

    fun resume() {
        AppLog.d("VM", "resume id=${id.take(8)}")
        viewModelScope.launch {
            // Pre-clear any stale "Couldn't resume — try again." from a previous failed click so a
            // successful retry on this same user gesture doesn't leave the red banner stuck in the
            // input bar (it's only cleared today by the next submit(), which a Resume click doesn't
            // touch). The clear is monotonic — a new failure from THIS click re-sets it.
            local.update { it.copy(queueError = null) }
            val r = sessionsRepo.followUp(id, "Continue from where you left off.", setTitle = false)
            if (r is Outcome.Success) {
                AppLog.d("VM", "resume id=${id.take(8)} => OK")
            } else {
                AppLog.w("VM", "resume id=${id.take(8)} => FAILED: Couldn't resume")
                local.update { it.copy(queueError = "Couldn't resume — try again.") }
            }
        }
    }

    /** Re-run the session's original [prompt] as a FRESH turn — used for an error that died before
     *  Claude initialized, so there is no claude session to [resume]. The backend spawns a new claude
     *  run (no --resume) for the same prompt. setTitle=false: the prompt is already the session title. */
    fun retry(prompt: String) {
        AppLog.d("VM", "retry id=${id.take(8)}")
        viewModelScope.launch {
            // Same pre-clear rationale as resume() above — a stale "Couldn't retry — try again."
            // must not survive a successful click on the same user gesture.
            local.update { it.copy(queueError = null) }
            val r = sessionsRepo.followUp(id, prompt, setTitle = false)
            if (r is Outcome.Success) {
                AppLog.d("VM", "retry id=${id.take(8)} => OK")
            } else {
                AppLog.w("VM", "retry id=${id.take(8)} => FAILED: Couldn't retry")
                local.update { it.copy(queueError = "Couldn't retry — try again.") }
            }
        }
    }

    /** Fork this session into a new (idle, pending) one. On success [forkedTo] holds the new session
     *  id so the UI can navigate; on failure [forkState] surfaces the message. A second tap while a
     *  fork is in flight is ignored (the InFlight guard mirrors the downloadAttachment pattern). */
    fun fork() {
        AppLog.d("VM", "fork id=${id.take(8)}")
        if (_forkState.value is ForkState.InFlight) return
        _forkState.value = ForkState.InFlight
        viewModelScope.launch {
            when (val r = sessionsRepo.fork(id)) {
                is Outcome.Success -> {
                    AppLog.d("VM", "fork id=${id.take(8)} => OK newId=${r.value.take(8)}")
                    _forkState.value = ForkState.Success(r.value)
                    _forkedTo.value = r.value
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "fork id=${id.take(8)} => FAILED: ${r.error.userMessage()}")
                    _forkState.value = ForkState.Failed(r.error.userMessage())
                }
            }
        }
    }

}

/** One-shot effects from [SessionViewModel.downloadAttachment]. The VM fetches the bytes; the View
 *  (which owns a platform Context) saves them and shows the outcome. */
sealed interface DownloadEffect {
    val name: String
    /** Fetch started — the View may show a "downloading…" hint. */
    data class Started(override val name: String) : DownloadEffect
    /** Bytes fetched, ready for the View to write to storage. (Plain class: ByteArray identity is
     *  fine — effects are consumed once, never compared.) */
    class Ready(override val name: String, val bytes: ByteArray) : DownloadEffect
    /** Fetch failed; [message] is a short human-readable reason. */
    data class Failed(override val name: String, val message: String) : DownloadEffect
}

/** Honest user copy for a failed follow-up. A non-terminal session shows an enabled Send even when the
 *  server can't currently take the turn (e.g. an orphaned "running" row): the server rejects that POST
 *  with EngineError::Busy -> HTTP 400, which must read as "busy", not the transport-failure copy. */
fun followUpErrorMessage(error: AppError): String = when (error) {
    is AppError.Http ->
        if (error.code == 400) "This session is still busy — try again in a moment."
        else "Couldn't send — server error (HTTP ${error.code})."
    AppError.Unauthorized -> "Session expired — please sign in again."
    is AppError.Network -> "Couldn't send — check your connection and try again."
    is AppError.CertUntrusted -> "The server's certificate isn't trusted — sign in again to verify it."
    is AppError.Unknown -> "Couldn't send — something went wrong."
}
