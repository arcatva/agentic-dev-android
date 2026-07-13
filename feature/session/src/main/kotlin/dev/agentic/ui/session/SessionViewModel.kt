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
import dev.agentic.data.repo.FilesRepository
import dev.agentic.data.repo.SessionsRepository
import dev.agentic.data.repo.TranscriptState
import dev.agentic.data.repo.WorkflowsRepository
import android.os.SystemClock
import dev.agentic.domain.AskNode
import dev.agentic.domain.AttachmentNode
import dev.agentic.domain.DownloadPace
import dev.agentic.domain.DownloadUi
import java.io.File
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

/** Immutable UI state for one chat session. All input-state-machine flags computed in [SessionViewModel.buildUiState]. */
data class SessionUiState(
    val nodes: List<Node> = emptyList(),
    val session: Session? = null,
    val busy: Boolean = false,
    val connecting: Boolean = true,
    /** Initial transcript load failed (server unreachable on open) — show error + retry calling [SessionViewModel.reload]. */
    val loadError: Boolean = false,
    val input: String = "",
    val sending: Boolean = false,
    val runs: List<WorkflowRun> = emptyList(),
    val shared: List<AttachmentNode> = emptyList(),
    // Derived input-state-machine flags (computed in one place):
    val terminal: Boolean = false,
    val streaming: Boolean = false,
    val composable: Boolean = false,
    val canSend: Boolean = false,
    val workflowActive: Boolean = false,
    val hasRuns: Boolean = false,
    /** Set when a queued turn could not be sent before the drain timed out; cleared on the next submit. */
    val queueError: String? = null,
    /** Files the user picked from the device that haven't been sent; submit() awaits in-flight uploads and appends `[attached: …]` with successful paths. */
    val attachments: List<PendingAttachment> = emptyList(),
    /** True while any pending attachment's upload is in flight; gates Send so submit can read the final set deterministically. */
    val uploading: Boolean = false,
    val hasMore: Boolean = false,
    val loadingEarlier: Boolean = false,
    /** Bounded-window ended sessions: newer pages evicted and paged back in via [loadNewer]. */
    val hasNewer: Boolean = false,
    val loadingNewer: Boolean = false,
    val latestEventId: Long = 0,
    val firstEventLine: Long = 0,
)

/** Fork action state observed by SessionScreen to drive the Fork button + an inline error. */
sealed interface ForkState {
    data object Idle : ForkState
    data object InFlight : ForkState
    data class Success(val newId: String) : ForkState
    data class Failed(val message: String) : ForkState
}

/** One-shot rewind result for a toast; null = nothing to show. */
sealed interface RewindResult {
    data class Done(val turnIndex: Int) : RewindResult
    data class Failed(val message: String) : RewindResult
}

/** One message in a fork's "previous conversation" preview; [fromUser] picks the You/Assistant label. */
data class ForkMsg(val fromUser: Boolean, val text: String)

/** Parent (forked-from) conversation a fork displays read-only in its collapsible history card. */
data class ForkParentPreview(val parentId: String, val title: String, val messages: List<ForkMsg>)

internal fun List<dev.agentic.domain.Node>.toForkMessages(): List<ForkMsg> = mapNotNull { n ->
    when (n) {
        is PromptNode -> n.text.takeIf { it.isNotBlank() }?.let { ForkMsg(fromUser = true, it) }
        is AnswerNode -> n.text.takeIf { it.isNotBlank() }?.let { ForkMsg(fromUser = false, it) }
        is TextNode -> n.text.takeIf { it.isNotBlank() }?.let { ForkMsg(fromUser = false, it) }
        else -> null
    }
}

/** Core session ViewModel: chat-input state machine + transcript aggregation. Events read the repo's hot transcript directly (not [uiState]) so they work without a UI subscriber. */
class SessionViewModel(
    private val sessionsRepo: SessionsRepository,
    private val workflowsRepo: WorkflowsRepository,
    private val filesRepo: FilesRepository,
    private val id: String,
    private val initialPrompt: String? = null,
    // Dispatcher for the O(n) buildUiState combine (moved off Main via flowOn below). Injectable so unit
    // tests can pass their TestDispatcher — Dispatchers.Default escapes the test scheduler and would make
    // uiState emissions non-deterministic under runTest.
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** SavedStateHandle backing this VM when constructed via the nav ctor; null in wide-layout (AdaptiveHome has no per-session nav entry). */
    private val savedStateHandle: SavedStateHandle? = null,
) : ViewModel() {

    /** Secondary ctor for the NavHost call site (per-session SavedStateHandle from the route args). */
    constructor(
        sessionsRepo: SessionsRepository,
        workflowsRepo: WorkflowsRepository,
        filesRepo: FilesRepository,
        handle: SavedStateHandle,
        computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(
        sessionsRepo, workflowsRepo, filesRepo,
        requireNotNull(handle.get<String>("id")) { "SessionViewModel requires an 'id' arg" },
        handle.get<String>("initialPrompt"),
        computeDispatcher,
        handle,
    )

    /** Session id; surfaced so the screen can show it under the title (helpful when reporting a stuck/odd session). */
    val sessionId: String get() = id

    /** Event-driven local overlays merged into [uiState]. Mutated by events via [MutableStateFlow.update]. */
    private data class Local(
        val input: String = "",
        val sending: Boolean = false,
        val optimisticPrompt: String? = null,
        /** Follow-up texts shown optimistically at the END of the transcript until a matching real PromptNode echoes back. */
        val pendingSent: List<String> = emptyList(),
        // Keyed by stable question-string (NOT the mutable AskNode) so the override survives markAnsweredAsks flipping answered/answer.
        val answeredOverrides: Map<String, String> = emptyMap(),
        /** Optimistic perm/plan decisions keyed by request id; harmless once backend's permResolved marker lands. */
        val permDecided: Map<String, String> = emptyMap(),
        /** Set when the drain exhausts without sending; surfaced via UiState.queueError. */
        val queueError: String? = null,
        /** Files the user attached but hasn't sent. Done entries feed the next prompt's `[attached: …]` marker; failed ones stay for retry/remove. */
        val attachments: List<PendingAttachment> = emptyList(),
    )

    private val local = MutableStateFlow(
        // Seed from persisted draft so re-entering the session restores typed text.
        Local(input = sessionsRepo.draftFor(id), optimisticPrompt = initialPrompt),
    )

    val uiState: StateFlow<SessionUiState> =
        combine(
            // Re-acquire each subscription: the repo's idle reaper can release(id) the flow when no collector
            // exists for a while (foldable config-change tears down+recreates this screen faster than the 5s
            // grace). A captured reference would be permanently dead; `flow { emitAll(transcript(id)) }`
            // returns the live cached flow or RE-CREATES it if reaped. (transcript(id) is idempotent when cached.)
            flow { emitAll(sessionsRepo.transcript(id)) },
            workflowsRepo.runsStream(id),
            workflowsRepo.outboxStream(id),
            local,
            // Polls the authoritative session row every 2s so session-derived UI (error banner, tags, title) doesn't stay frozen at the last reseed.
            sessionsRepo.sessionRefreshStream(id),
        ) { t, runs, shared, l, _ -> buildUiState(t, runs, shared, l) }
            // O(n) buildUiState off the main thread — per-token deltas used to rebuild the whole transcript on Main (streaming jank).
            // Conflation lets the StateFlow naturally skip intermediate per-token states when buildUiState can't keep up.
            .flowOn(computeDispatcher)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SessionUiState())

    /** Read-only parent (forked-from) preview for the collapsible card a fork shows. Reuses shared `transcript(pid)` flow — no new API surface. */
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

    /** Stable transcript reference reused when content is unchanged so poll ticks don't hand [Transcript] a fresh list instance every ~2s (periodic hitch). */
    private var stableNodes: List<Node> = emptyList()

    /** Builds UI state in one place: merges shared files, applies optimistic overlays, derives all input-state flags. Only side effect is [clearMatchedPending]. */
    private fun buildUiState(
        t: TranscriptState,
        runs: List<WorkflowRun>,
        shared: List<AttachmentNode>,
        l: Local,
    ): SessionUiState {
        // 1. Merge outbox files at the turn that produced them. hasMore (start-truncated window) hides anchorless files instead of gluing them to the streamed bottom — paging back reveals them at their true position.
        var nodes: List<Node> = interleaveShared(t.nodes, shared, truncatedStart = t.hasMore)
        // 2. Optimistic INITIAL prompt until a real PromptNode lands.
        if (l.optimisticPrompt != null && nodes.none { it is PromptNode }) {
            nodes = listOf(PromptNode(l.optimisticPrompt)) + nodes
        }
        // 2b. Optimistic SENT echo: each just-sent FOLLOW-UP stays at the END until matched by a real PromptNode; one real echo cancels one overlay.
        if (l.pendingSent.isNotEmpty()) {
            // Match against t.nodes (not overlaid `nodes`) so the prepended optimistic initial can't count as a follow-up echo.
            val realPromptTexts = t.nodes.filterIsInstance<PromptNode>().map { it.text }
            val stillPending = unmatchedPending(l.pendingSent, realPromptTexts)
            if (stillPending.size != l.pendingSent.size) clearMatchedPending(realPromptTexts)
            nodes = nodes + stillPending.map { PromptNode(it) }
        }
        // 3. Optimistic ask answers: replace matching AskNodes.
        if (l.answeredOverrides.isNotEmpty()) {
            nodes = nodes.map { n ->
                if (n is AskNode) l.answeredOverrides[askKey(n)]?.let { n.copy(answered = true, answer = it) } ?: n else n
            }
        }
        // 3b. Optimistic perm/plan decisions.
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
        // Terminal session can follow up if it has a claudeSessionId to --resume, OR is a fork awaiting first turn (parentSessionId set, no claudeSessionId).
        val canFollowUp = terminal && (session?.claudeSessionId != null ||
            isForkAwaitingFirstTurn(session?.status.orEmpty(), session?.claudeSessionId, session?.parentSessionId))
        val workflowActive = runs.any { it.isActive() } || (runs.isEmpty() && nodes.any { it is WorkflowNode })
        // composable follows non-terminal status (not awaitingInput alone): an orphaned running row or a turn reaped before registering has awaitingInput=null but is still actionable.
        val composable = streaming || running || canFollowUp || workflowActive
        // Disable Send while `uploading` so chip-row progress is stable and Send doesn't appear enabled then immediately jump disabled.
        val anyDone = l.attachments.any { it.state is UploadState.Done }
        val canSend = composable && !l.sending && (l.input.isNotBlank() || anyDone)
        val hasRuns = runs.isNotEmpty() || nodes.any { it is WorkflowNode }
        val uploading = l.attachments.any { it.state is UploadState.Uploading }

        // Reuse the PREVIOUS list instance when content is unchanged (List is an unstable Compose type): metadata-only ticks would otherwise hand Transcript a fresh instance each ~2s, recomputing spawnOrdinals/userMessageAnchors.
        // 4. Authoritative awaiting-prompt (server's `pendingPrompt` is the source of truth): restore an unanswered AskNode lost to a warm reseed. Deduped by question content.
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

    /** Pending sent-echo texts not yet matched by a real prompt. One real echo of a text cancels exactly one pending overlay of that text. */
    private fun unmatchedPending(pending: List<String>, realPromptTexts: List<String>): List<String> {
        // Normalize both sides: the reducer strips a trailing "[attached: …]" marker + trims; matching raw strings would miss when uploads finish mid-submit.
        val available = realPromptTexts.map { normalizePrompt(it) }.toMutableList()
        val still = ArrayList<String>(pending.size)
        for (text in pending) {
            // Remove the normalized match but keep the ORIGINAL pending text for display fidelity.
            if (!available.remove(normalizePrompt(text))) still.add(text)
        }
        return still
    }

    /** Canonical form for echo-matching: drop the trailing "[attached: …]" marker, then trim. */
    private fun normalizePrompt(text: String): String = splitAttachments(text).first.trim()

    /** Drop from [local] every pending sent-echo text now matched by a real prompt. Recomputes against CURRENT local value (not the builder's snapshot). */
    private fun clearMatchedPending(realPromptTexts: List<String>) {
        local.update { it.copy(pendingSent = unmatchedPending(it.pendingSent, realPromptTexts)) }
    }

    // ── Events ──────────────────────────────────────────────────────────────────

    /** @-mention candidates: every session EXCEPT this one, most-recently-active first. Refreshed on demand; last fetched list stays so dropdown opens instantly. */
    private val _mentionCandidates = MutableStateFlow<List<Session>>(emptyList())
    val mentionCandidates: StateFlow<List<Session>> = _mentionCandidates.asStateFlow()

    /** Single-flight guard for [refreshMentionCandidates] (typing fires the effect repeatedly; one GET at a time is plenty). */
    private var mentionFetch: Job? = null

    /** Best-effort: on failure the previous (possibly empty) list is kept. */
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

    /** `/`-command palette candidates (the SDK's live list). Fetched once, kept for instant open. */
    private val _commandCandidates =
        MutableStateFlow<List<dev.agentic.ui.components.CommandItem>>(emptyList())
    val commandCandidates: StateFlow<List<dev.agentic.ui.components.CommandItem>> =
        _commandCandidates.asStateFlow()
    private var commandsFetch: Job? = null

    /** Best-effort, single-flight, cached — the server already caches, so once per screen is plenty. */
    fun refreshCommands() {
        if (commandsFetch?.isActive == true || _commandCandidates.value.isNotEmpty()) return
        commandsFetch = viewModelScope.launch {
            runCatching { sessionsRepo.commands() }.onSuccess { cmds ->
                _commandCandidates.value =
                    cmds.map { dev.agentic.ui.components.CommandItem(it.name, it.description, it.argumentHint) }
            }
        }
    }

    fun onInput(s: String) {
        sessionsRepo.setDraft(id, s)   // persist (memory + disk) so leaving/backgrounding + returning restores it
        local.update { it.copy(input = s) }
    }

    /** In-flight uploads keyed by URI string; held so a user removal mid-upload can cancel the network call. Synchronized: attachFiles spawns N independent coroutines. */
    private val uploadJobs = mutableMapOf<String, Job>()

    /** Pick [uris] and start parallel uploads; each becomes [Local.attachments] (Pending → Uploading → Done/Failed). Reads each URI once into a ByteArray (no URI persistence past process death). */
    fun attachFiles(uris: List<Uri>, resolver: ContentResolver) {
        if (uris.isEmpty()) return
        AppLog.d("VM", "attachFiles id=${id.take(8)} count=${uris.size}")
        // Skip URIs already attached (and duplicates within this pick): two attachments sharing an
        // id collide on the input LazyRow's `key = { it.id }` and crash the layout pass, and
        // re-uploading a file already attached is pointless.
        val existing = local.value.attachments.mapTo(mutableSetOf()) { it.id }
        val newOnes = uris
            .distinctBy { it.toString() }
            .filterNot { it.toString() in existing }
            .map { uri ->
                val (name, size) = queryDisplayNameAndSize(resolver, uri)
                PendingAttachment.of(uri.toString(), name, size)
            }
        if (newOnes.isEmpty()) return
        local.update { it.copy(attachments = it.attachments + newOnes) }
        for (att in newOnes) launchUpload(att, resolver)
    }

    /** Remove a pending; cancel its in-flight upload so we don't leave an orphan on the server's uploads dir. */
    fun removePending(id: String) {
        synchronized(uploadJobs) { uploadJobs.remove(id) }?.cancel()
        local.update { l -> l.copy(attachments = l.attachments.filterNot { it.id == id }) }
    }

    /** Spawn the upload coroutine for [att]; state transitions emitted through [local.update]. */
    private fun launchUpload(att: PendingAttachment, resolver: ContentResolver) {
        local.update { l ->
            l.copy(attachments = l.attachments.map { if (it.id == att.id) it.copy(state = UploadState.Uploading) else it })
        }
        val job = viewModelScope.launch {
            val outcome = runCatching {
                val bytes = readBytes(resolver, android.net.Uri.parse(att.localUri))
                filesRepo.upload(id, bytes, att.displayName)
            }
            // Any thrown exception → Failed chip (openInputStream null, OOM, IO error).
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

    /** Read a SAF/document URI to bytes (handles both `content://` and `file://`). Throws if the stream can't be opened (only on truly broken inputs). */
    private fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot open $uri")

    /** Best-effort display name + size for a picker URI; falls back to last path segment + size=-1 when the provider doesn't expose OpenableColumns. */
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

    /** One-shot download effects; VM owns the network fetch, View owns the platform save. BUFFERED so emits before the View collects are kept. */
    private val _downloads = Channel<DownloadEffect>(Channel.BUFFERED)
    val downloads: Flow<DownloadEffect> = _downloads.receiveAsFlow()

    /** Drain the channel on clear and delete any buffered Ready temps (disk leak if the VM tears down before the View re-attaches). */
    override fun onCleared() {
        while (true) {
            val eff = _downloads.tryReceive().getOrNull() ?: break
            if (eff is DownloadEffect.Ready) eff.file.delete()
        }
        super.onCleared()
    }

    /** Per-attachment download progress keyed by [AttachmentNode.path]. Keys also serve as the set of in-flight paths: repeat taps ignored, two different files can download with independent bars (a single shared slot used to flicker as coroutines nulled each other). */
    private val _downloadProgress = MutableStateFlow<Map<String, DownloadUi>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadUi>> = _downloadProgress

    /** Fork action state observed by SessionScreen. */
    private val _forkState = MutableStateFlow<ForkState>(ForkState.Idle)
    val forkState: StateFlow<ForkState> = _forkState.asStateFlow()

    /** One-shot fork navigation signal. MUST be a StateFlow (not a plain var): the consuming LaunchedEffect lives in a scope the outer body doesn't otherwise recompose on fork success. */
    private val _forkedTo = MutableStateFlow<String?>(null)
    val forkedTo: StateFlow<String?> = _forkedTo.asStateFlow()

    /** Clear [forkedTo] after the UI has consumed it. */
    fun acknowledgeFork() { _forkedTo.value = null }

    /** Reset [forkState] to Idle after the UI has shown a fork error; no-op unless currently Failed (can't clobber InFlight/Success). */
    fun acknowledgeForkError() {
        if (_forkState.value is ForkState.Failed) _forkState.value = ForkState.Idle
    }

    /** One-shot rewind result for a toast; cleared via [acknowledgeRewind]. */
    private val _rewindResult = MutableStateFlow<RewindResult?>(null)
    val rewindResult: StateFlow<RewindResult?> = _rewindResult.asStateFlow()
    /** True while a rewind request is in flight (disables the confirm dialog button). */
    private val _rewinding = MutableStateFlow(false)
    val rewinding: StateFlow<Boolean> = _rewinding.asStateFlow()

    /** Restore the worktree to the snapshot before [turnIndex] (code only). Ignored if one is already in flight. */
    fun rewind(turnIndex: Int) {
        AppLog.d("VM", "rewind id=${id.take(8)} turnIndex=$turnIndex")
        if (_rewinding.value) return
        _rewinding.value = true
        viewModelScope.launch {
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

    /** Fetch attachment bytes for an INLINE preview (e.g. image thumbnail). Best-effort: null on failure so the card falls back to its icon. */
    suspend fun attachmentBytes(node: AttachmentNode): ByteArray? =
        runCatching { filesRepo.fileBytes(id, node.path) }.getOrNull()

    /** Stream [node] to a temp file with automatic mid-transfer resume; hand the file to the View to save. 500ms [DownloadPace] ticker samples speed and stall. */
    fun downloadAttachment(node: AttachmentNode) {
        AppLog.d("VM", "downloadAttachment id=${id.take(8)} path=${node.path}")
        // SYNCHRONOUS guard BEFORE the launch: two taps on main thread can't both get past.
        if (node.path in _downloadProgress.value) return
        _downloadProgress.update { it + (node.path to DownloadUi()) }
        val name = node.path.substringAfterLast('/')
        viewModelScope.launch {
            _downloads.send(DownloadEffect.Started(name))
            val pace = DownloadPace().apply { start(SystemClock.elapsedRealtime()) }
            // Speed/stall sampler — a child job so the `finally` below always tears it down.
            val ticker = launch {
                while (true) {
                    delay(500)
                    val p = pace.sample(SystemClock.elapsedRealtime())
                    _downloadProgress.update { m ->
                        m[node.path]?.let { cur ->
                            m + (node.path to cur.copy(bytesPerSec = p.bytesPerSec, stalled = p.stalled))
                        } ?: m
                    }
                }
            }
            // Created INSIDE try so a creation failure (cache gone, disk full) still emits Failed + clears the entry.
            var dest: File? = null
            try {
                val d = withContext(Dispatchers.IO) { File.createTempFile("download-", ".part") }
                dest = d
                filesRepo.downloadTo(id, node.path, d) { received, total ->
                    pace.onProgress(received, SystemClock.elapsedRealtime())
                    val fraction = total?.takeIf { it > 0 }
                        ?.let { (received.toFloat() / it).coerceIn(0f, 1f) }
                    // update {} is an atomic CAS; safe even though the callback fires off the main thread.
                    _downloadProgress.update { m ->
                        m[node.path]?.let { cur -> m + (node.path to cur.copy(fraction = fraction)) } ?: m
                    }
                }
                AppLog.d("VM", "downloadAttachment id=${id.take(8)} path=${node.path} => OK")
                _downloads.send(DownloadEffect.Ready(name, d))
            } catch (e: CancellationException) {
                dest?.delete()
                throw e
            } catch (e: Exception) {
                AppLog.w("VM", "downloadAttachment id=${id.take(8)} path=${node.path} => FAILED: ${e.message ?: "download failed"}")
                dest?.delete()
                _downloads.send(DownloadEffect.Failed(name, e.message ?: "download failed"))
            } finally {
                ticker.cancel()
                // Clear ONLY this file's entry: concurrent downloads of OTHER files keep their own bars.
                _downloadProgress.update { it - node.path }
            }
        }
    }

    /** Discord-style: set lastAckedEventId to the session's current unreadEventId (monotonic server counter). Takes [eventId] from caller to avoid a race where the LaunchedEffect fires before uiState emits the session. */
    fun ackEvent(eventId: Long) {
        AppLog.v("Read", "ackEvent id=${id.take(8)} eventId=$eventId")
        viewModelScope.launch {
            // NonCancellable: a fast back-out clears the VM and would cancel an in-flight ack PUT — the session would come back as unread.
            // withTimeoutOrNull bounds it; the timeout still fires inside NonCancellable (it cancels its own child job).
            withContext(NonCancellable) {
                try {
                    withTimeoutOrNull(5_000L) { sessionsRepo.ack(id, eventId) }
                } catch (_: Exception) { /* best-effort: reopening the session re-acks (self-heals) */ }
            }
        }
    }

    /** Page earlier events; prepends and re-seeds the reducer. */
    fun loadEarlier() {
        viewModelScope.launch { sessionsRepo.loadEarlier(id) }
    }

    /** Page newer events back into a bounded-window ended session; no-op otherwise. */
    fun loadNewer() {
        viewModelScope.launch { sessionsRepo.loadNewer(id) }
    }

    /** Interrupt the current turn; session stays alive (not kill()). */
    fun stop() {
        AppLog.d("VM", "stop id=${id.take(8)}")
        viewModelScope.launch { sessionsRepo.interrupt(id) }
    }

    /** Retry the initial transcript load after a first-load failure. */
    fun reload() {
        AppLog.d("VM", "reload id=${id.take(8)}")
        sessionsRepo.reload(id)
    }

    /** Force a fresh transcript reseed on screen resume (ON_RESUME in SessionScreen). Fixes warm-return staleness — most visibly a parked AskUserQuestion whose picker card vanished on a background-time reconnect reseed. */
    fun refresh() {
        AppLog.d("VM", "refresh id=${id.take(8)}")
        sessionsRepo.refresh(id)
    }

    /** Send typed input as the next turn. Awaits in-flight uploads and composes trailing `[attached: …]` marker matching `splitAttachments`. */
    fun submit() {
        AppLog.d("VM", "submit id=${id.take(8)}")
        val text = local.value.input.trim()
        val attachmentsNow = local.value.attachments
        val hasReadyAttachment = attachmentsNow.any { it.state is UploadState.Done }
        if (text.isBlank() && !hasReadyAttachment) return

        // Snapshot in-flight uploads; join them before composing so the marker reflects their final state.
        val jobsToAwait = synchronized(uploadJobs) { uploadJobs.values.toList() }
        // Optimistic echo stores marker-FREE text so it matches the server echo after the reducer strips the marker (see normalizePrompt).
        val pendingText = text

        // Clear input + persisted draft + stale send error.
        sessionsRepo.clearDraft(id)
        local.update {
            it.copy(input = "", queueError = null, sending = true, pendingSent = it.pendingSent + pendingText)
        }
        viewModelScope.launch {
            // joinAll does not propagate failure; removePending cancellation propagates as CancellationException → abort send.
            try {
                jobsToAwait.forEach { it.join() }
            } catch (e: CancellationException) {
                throw e
            }
            val finalAtts = local.value.attachments
            val full = composePromptWithMarker(text, finalAtts)
            sendFollowUp(full, pendingText)
            // Drop Done entries — they're on the server; next marker should reference NEW uploads.
            local.update { l -> l.copy(attachments = l.attachments.filter { it.state !is UploadState.Done }) }
        }
    }

    /** Append `[attached: paths…]` marker only when at least one attachment is Done; matches `splitAttachments` so the optimistic echo and server echo normalize to the same text. */
    private fun composePromptWithMarker(text: String, attachments: List<PendingAttachment>): String {
        val paths = attachments.mapNotNull { (it.state as? UploadState.Done)?.path }
        if (paths.isEmpty()) return text
        val marker = "[attached: ${paths.joinToString(", ")}]"
        return if (text.isEmpty()) "\n\n$marker" else "$text\n\n$marker"
    }

    /** On Failure: drop the one pendingSent overlay, put un-sent text back in the input (unless user typed something new), surface an error. Always clears `sending`. `full` is the prompt sent; `pendingText` is the marker-free overlay text so rollback targets the right entry. */
    private suspend fun sendFollowUp(full: String, pendingText: String) {
        // setTitle=false: server owns the title (auto-retitled every 5 messages); setTitle=true would overwrite with raw text on every send.
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

    /** Optimistically mark [node] answered, send as a (non-title) follow-up. Guarded: surfaces an error if the session can't take a turn. */
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
        // Compare-and-set inside update() — atomic against double-submit races so two identical answer bubbles never POST as separate turns.
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

    /** Optimistically mark a parked permission/plan prompt decided and POST it. feedback = deny reason / plan-revision note (null on plain allow). */
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

    /** Stable key for an ask's optimistic-answer override: depends only on the (immutable) questions. */
    private fun askKey(node: AskNode): String = node.questions.toString()

    fun resume() {
        AppLog.d("VM", "resume id=${id.take(8)}")
        viewModelScope.launch {
            // Pre-clear stale queueError from a previous failed Resume click: only cleared today by next submit(), which Resume doesn't touch.
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

    /** Re-run the session's original [prompt] as a FRESH turn (no `--resume`); used when Claude died before initializing. setTitle=false: prompt is already the session title. */
    fun retry(prompt: String) {
        AppLog.d("VM", "retry id=${id.take(8)}")
        viewModelScope.launch {
            // Same pre-clear as resume() — stale "Couldn't retry" must not survive a successful retry on the same gesture.
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

    /** Fork this session into a new (idle, pending) one. On success [forkedTo] holds the new id for navigation; on failure [forkState] surfaces the message. InFlight guard mirrors downloadAttachment. */
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

/** One-shot download effects: VM streams to temp, View (with Context) copies into Downloads and toasts the outcome. */
sealed interface DownloadEffect {
    val name: String
    /** Fetch started — the View may show a "downloading…" hint. */
    data class Started(override val name: String) : DownloadEffect
    /** Payload fully streamed into [file] (an app-cache temp). View OWNS the temp: must delete after save. */
    data class Ready(override val name: String, val file: java.io.File) : DownloadEffect
    /** Fetch failed; [message] is a short human-readable reason. */
    data class Failed(override val name: String, val message: String) : DownloadEffect
}

/** Map a follow-up error to user copy. EngineError::Busy → HTTP 400 reads as "busy", not transport failure (orphaned "running" sessions can still enable Send but the server rejects). */
fun followUpErrorMessage(error: AppError): String = when (error) {
    is AppError.Http ->
        if (error.code == 400) "This session is still busy — try again in a moment."
        else "Couldn't send — server error (HTTP ${error.code})."
    AppError.Unauthorized -> "Session expired — please sign in again."
    is AppError.Network -> "Couldn't send — check your connection and try again."
    is AppError.CertUntrusted -> "The server's certificate isn't trusted — sign in again to verify it."
    is AppError.Unknown -> "Couldn't send — something went wrong."
}
