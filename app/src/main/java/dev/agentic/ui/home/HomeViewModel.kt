package dev.agentic.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.log.AppLog
import dev.agentic.data.net.Group
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.SearchHit
import dev.agentic.data.net.SearchResponse
import dev.agentic.data.net.Session
import dev.agentic.data.net.Usage
import dev.agentic.data.repo.SessionsLoadState
import dev.agentic.data.repo.SessionsRepository
import dev.agentic.domain.isSessionUnread
import dev.agentic.data.util.pollFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val sessions: List<Session> = emptyList(),
    val usage: Usage? = null,
    val loading: Boolean = true,
    /**
     * PR-9: true when the very first sessionsStream load attempt failed (server unreachable at
     * launch). Cleared as soon as a successful tick arrives. Later blips keep last-good and do
     * NOT set this flag again (so the banner only shows on first-load failure).
     */
    val serverUnreachable: Boolean = false,
    /** True while a user-triggered pull-to-refresh is in flight (drives the PullToRefreshBox spinner). */
    val refreshing: Boolean = false,
    /**
     * Ids of sessions the user has ticked for multi-select delete. Pruned to ids still present in
     * [sessions] so the contextual bar never counts a session that has scrolled off the list.
     */
    val selectedIds: Set<String> = emptySet(),
    /** Ids of finished sessions the user hasn't read since they completed — render a list-row dot. */
    val unreadIds: Set<String> = emptySet(),
    // ── Task 6: content search state ─────────────────────────────────────────────
    /** Latest text the user typed in the search box. Mirrored from [HomeViewModel.setSearchQuery]
     *  with no debounce, so the UI shows the typed text immediately. */
    val searchQuery: String = "",
    /** Hits for [searchQuery] from the most recent completed `contentSearch` query. Empty until a
     *  non-blank query has been debounced + fetched, and again after the user clears the box. */
    val searchResults: List<SearchHit> = emptyList(),
    /** True while a content-search request is in flight (debounce + fetch + mapLatest). */
    val searching: Boolean = false,
    /** The last query for which a search COMPLETED (success or failure). When [searchQuery]
     *  differs from [lastSearchedQuery], a search is in flight — the UI shows a loading
     *  spinner rather than "No sessions match". Eliminates the one-frame gap where
     *  `searchQuery` is set but `searching` is still false. */
    val lastSearchedQuery: String = "",
    // ── Session groups ──────────────────────────────────────────────────────────
    /** All user-created groups (folders). */
    val groups: List<Group> = emptyList(),
    /** Which group id is selected in the filter row (null = "All"). */
    val selectedGroupFilter: String? = null,
) {
    /** Selection mode is on whenever at least one session is ticked (Google-Photos style). */
    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    val selectedCount: Int get() = selectedIds.size
}

class HomeViewModel(
    private val sessionsRepo: SessionsRepository,
) : ViewModel() {

    init {
        // Fetch groups once on startup; the list refreshes on every group mutation.
        refreshGroups()
    }

    // Pull-to-refresh plumbing: a manual reload emits the freshly-fetched values here, and these are
    // merged into the poll flows below so a refresh shows up immediately instead of waiting for the
    // next 2 s (sessions) / 60 s (usage) poll tick.
    // One-shot event streams (deliberately NOT MutableStateFlow). A pull-to-refresh pushes its
    // freshly-fetched value here and it is delivered ONLY to subscribers present at that instant.
    // A StateFlow would RETAIN the last value and replay it to every new collector — and because
    // uiState is a WhileSubscribed StateFlow, its whole upstream is torn down and restarted on each
    // background→foreground return, so a retained manual value would re-emit an OLD snapshot over the
    // live data before the poll answers: the usage meter flashes a stale % then snaps back, and the
    // session list briefly reverts to the old order. replay=0 kills that flash — on resume the last
    // good uiState value stays put until the live poll delivers fresh data.
    private val manualSessions = MutableSharedFlow<List<Session>>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val manualUsage = MutableSharedFlow<Usage>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _refreshing = MutableStateFlow(false)

    // Multi-select: ids the user has ticked for batch delete. Empty set ⇒ not in selection mode.
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())

    // ── Session groups ──────────────────────────────────────────────────────────
    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    private val _selectedGroupFilter = MutableStateFlow<String?>(null)

    // ── Task 6: content-search plumbing ──────────────────────────────────────────
    // The upstream of SessionsRepository.contentSearch(query: StateFlow<String>) — see plan Task 5.
    // setSearchQuery writes here, and a per-query collector on viewModelScope maps its results
    // back into _searchResults / _searching. The visible text is mirrored into uiState.searchQuery
    // synchronously so the input doesn't lag behind the user's keystrokes (the 250ms debounce only
    // delays the BACKEND round-trip, not the visible query).
    private val _searchQuery = MutableStateFlow("")
    private val _searchResults = MutableStateFlow<List<SearchHit>>(emptyList())
    private val _searching = MutableStateFlow(false)
    private val _lastSearchedQuery = MutableStateFlow("")
    /** Last-launched content-search collector. Cancelled on every new query so the previous
     *  mapLatest block in the repo is cancelled (last-write-wins) and no stale `searching=true`
     *  from an aborted run leaks past a fresh hit. */
    private var searchJob: Job? = null

    // Usage refreshed every 60 s (best-effort: a failed tick yields null and is ignored downstream),
    // plus any on-demand pull-to-refresh value.
    private val usageFlow: Flow<Usage?> =
        merge(
            pollFlow(60_000L) { runCatching { sessionsRepo.usage() }.getOrNull() },
            manualUsage,
        )

    /**
     * Accumulates sessionsStreamWithState emissions into a (sessions, serverUnreachable) pair so
     * combine() has a stable, non-nullable partner.
     *
     * PR-9 logic:
     * - FirstLoadError sets unreachable=true and keeps the empty session list.
     * - Loaded clears unreachable and updates the session list (keeps last-good on blips because
     *   sessionsStreamWithState itself never emits Loaded with a stale list on a blip —
     *   that is, null ticks are swallowed in the repo and only Loaded/FirstLoadError reach here).
     */
    private data class SessionsState(
        val sessions: List<Session> = emptyList(),
        val serverUnreachable: Boolean = false,
    )

    // Last SessionsState the pipeline produced, retained across a WhileSubscribed restart. The scan
    // below seeds from THIS (re-read per collection via the flow{} builder), not a fresh empty
    // SessionsState() — otherwise every background→foreground return re-folds from empty and blanks
    // the list for a frame before the poll answers (the sessions half of the resume-flash fix; the
    // usage half is manualUsage's replay=0). Confined to viewModelScope's single dispatcher and only
    // one active collector exists at a time (WhileSubscribed cancels+joins the prior collection before
    // restarting), so writes never race a read; @Volatile guards visibility as belt-and-suspenders.
    // Side effect on the PR-9 banner path: if the FIRST tick after a restart fails (FirstLoadError),
    // acc is now the retained last-good state, so we keep showing that list UNDER the "can't reach
    // server" banner instead of blanking to empty — the intended keep-last-good behaviour.
    @Volatile private var lastSessionsState = SessionsState()

    private val sessionsState: Flow<SessionsState> = flow {
        emitAll(
            merge(
                sessionsRepo.sessionsStreamWithState(),
                manualSessions.map { SessionsLoadState.Loaded(it) },
            ).scan(lastSessionsState) { acc, event ->
                when (event) {
                    is SessionsLoadState.FirstLoadError -> {
                        AppLog.w("VM", "FirstLoadError: server unreachable")
                        acc.copy(serverUnreachable = true)
                    }
                    is SessionsLoadState.Loaded ->
                        SessionsState(sessions = event.sessions, serverUnreachable = false)
                }
            }.onEach { ss ->
                lastSessionsState = ss
                // Reconcile the source-of-truth selection with the list: drop ticks for sessions that
                // have left so deleteSelected() never acts on a ghost id and a vanished id can't later
                // resurrect a tick. Runs only while uiState is subscribed (this flow is collected by the
                // WhileSubscribed combine), and never feeds back — it keys off sessions, not _selectedIds.
                val present = ss.sessions.mapTo(HashSet()) { it.id }
                _selectedIds.update { sel -> if (sel.isEmpty()) sel else sel.intersect(present) }
            },
        )
    }

    // Single source of truth. WhileSubscribed means the upstream polls ONLY while the UI observes —
    // no eager infinite collector leaking past the screen (and tests terminate). loading clears on
    // the first combined emission.
    //
    // Combine is split across two stages because kotlinx.coroutines.flow.combine has overloads up
    // to 5 flows — we now feed in 4 base flows + 3 search flows = 7.
    val uiState: StateFlow<HomeUiState> =
        combine(sessionsState, usageFlow, _refreshing, _selectedIds) { ss, usage, refreshing, selected ->
            // Prune ticks for sessions that have left the list so the count/actions stay consistent
            // with what is actually shown (preserves selection order via the intersect on `selected`).
            val present = ss.sessions.mapTo(HashSet()) { it.id }
            HomeUiState(
                sessions = ss.sessions,
                usage = usage,
                loading = false,
                serverUnreachable = ss.serverUnreachable,
                refreshing = refreshing,
                selectedIds = if (selected.isEmpty()) selected else selected.intersect(present),
            )
        }.combine(_searchQuery) { base, q ->
            val unread = base.sessions.asSequence()
                .filter { isSessionUnread(it) }
                .map { it.id }
                .toHashSet()
            base.copy(unreadIds = unread, searchQuery = q)
        }
            .combine(_searchResults) { base, results -> base.copy(searchResults = results) }
            .combine(_searching) { base, searching -> base.copy(searching = searching) }
            .combine(_lastSearchedQuery) { base, q -> base.copy(lastSearchedQuery = q) }
            .combine(_groups) { base, groups -> base.copy(groups = groups) }
            .combine(_selectedGroupFilter) { base, filter -> base.copy(selectedGroupFilter = filter) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), HomeUiState())

    /**
     * Pull-to-refresh: re-fetch the session list and credit-usage meters together, on demand, instead
     * of waiting for the next poll tick. Best-effort — a failed fetch keeps the last-good value (the
     * poll loop recovers it). [refreshing] drives the PullToRefreshBox spinner and is always cleared.
     */
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                val sessions = async { runCatching { sessionsRepo.sessions() }.getOrNull() }
                val usage = async { runCatching { sessionsRepo.usage() }.getOrNull() }
                val s = sessions.await()
                if (s != null) {
                    manualSessions.tryEmit(s)
                    AppLog.d("VM", "refresh: pulled ${s.size} sessions")
                } else {
                    AppLog.w("VM", "refresh: pull failed")
                }
                usage.await()?.let { manualUsage.tryEmit(it) }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Delete a session by id. Fire-and-forget; best-effort (failures silently ignored). */
    fun delete(id: String) {
        viewModelScope.launch {
            sessionsRepo.delete(id)
            AppLog.d("VM", "delete: session $id")
        }
    }

    // ── multi-select delete ───────────────────────────────────────────────────────

    /** Tick/untick [id]. A long-press on the first card ticks it and so enters selection mode; the
     *  last untick empties the set and leaves selection mode. */
    fun toggleSelection(id: String) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    /** Tick every session currently in the list. */
    fun selectAll() {
        _selectedIds.value = uiState.value.sessions.mapTo(LinkedHashSet()) { it.id }
    }

    /** Leave selection mode, dropping all ticks. */
    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    /** Delete every ticked session (best-effort, reusing the per-id endpoint) and leave selection
     *  mode. Ticks are cleared synchronously so the bar collapses immediately; the deletes run on
     *  the VM scope and the list reconciles on the next poll. */
    fun deleteSelected() {
        val ids = _selectedIds.value
        val n = ids.size
        _selectedIds.value = emptySet()
        viewModelScope.launch {
            ids.forEach { sessionsRepo.delete(it) }
            AppLog.d("VM", "delete: batch deleted $n sessions")
        }
    }

    /** Fork every ticked session. Best-effort (per-id failures are ignored — mirrors
     *  deleteSelected). Ticks are cleared synchronously so the bar collapses immediately;
     *  the forks run on the VM scope and the list reconciles on the next poll. */
    fun forkSelected() {
        val ids = _selectedIds.value
        val n = ids.size
        _selectedIds.value = emptySet()
        viewModelScope.launch {
            ids.forEach { sessionsRepo.fork(it) }
            AppLog.d("VM", "fork: batch forked $n sessions")
        }
    }

    /**
     * Write the just-submitted prompt for [id] into the repo so the detail screen can show it
     * immediately before the backend log catches up.
     */
    fun setPendingPrompt(id: String, prompt: String) {
        sessionsRepo.pendingPrompts[id] = prompt
    }

    // ── Session groups ──────────────────────────────────────────────────────────

    /** Select a group for filtering (null = "All"). */
    fun selectGroupFilter(groupId: String?) {
        _selectedGroupFilter.value = groupId
    }

    /** Refresh the group list from the backend. */
    fun refreshGroups() {
        viewModelScope.launch {
            sessionsRepo.listGroups().let { outcome ->
                if (outcome is Outcome.Success) _groups.value = outcome.value
            }
        }
    }

    /** Create a new group, then refresh. */
    fun createGroup(name: String, icon: String? = null) {
        viewModelScope.launch {
            sessionsRepo.createGroup(name, icon).let { outcome ->
                if (outcome is Outcome.Success) {
                    AppLog.d("VM", "createGroup: created '$name'")
                    refreshGroups()
                } else {
                    AppLog.w("VM", "createGroup: failed to create '$name'")
                }
            }
        }
    }

    /** Rename or change icon of a group, then refresh. */
    fun updateGroup(id: String, name: String? = null, icon: String? = null) {
        viewModelScope.launch {
            sessionsRepo.updateGroup(id, name, icon).let { outcome ->
                if (outcome is Outcome.Success) {
                    AppLog.d("VM", "updateGroup: updated $id")
                    refreshGroups()
                } else {
                    AppLog.w("VM", "updateGroup: failed to update $id")
                }
            }
        }
    }

    /** Delete a group (sessions in it become uncategorized), then refresh. */
    fun deleteGroup(id: String) {
        viewModelScope.launch {
            sessionsRepo.deleteGroup(id).let { outcome ->
                if (outcome is Outcome.Success) {
                    AppLog.d("VM", "deleteGroup: deleted $id")
                    refreshGroups()
                } else {
                    AppLog.w("VM", "deleteGroup: failed to delete $id")
                }
            }
        }
    }

    /** Move a session to a group (or null for uncategorized). Optimistic local update. */
    fun moveSessionToGroup(sessionId: String, groupId: String?) {
        viewModelScope.launch {
            sessionsRepo.setSessionGroup(sessionId, groupId)
            AppLog.d("VM", "moveSessionToGroup: $sessionId -> ${groupId ?: "uncategorized"}")
        }
    }

    // ── Task 6: content search ──────────────────────────────────────────────────

    /**
     * Update the search box and (after the 250ms debounce inside [SessionsRepository.contentSearch])
     * trigger a content search.
     *
     * Behaviour:
     * - Visible text is mirrored into [_searchQuery] immediately so the input field has no
     *   perceived lag — the debounce only delays the BACKEND round-trip, not what the user sees.
     * - Blank input cancels any in-flight search ([searchJob]), clears [_searchResults] (so stale
     *   hits disappear the moment the box is emptied), and clears [_searching]. No backend call.
     * - Non-blank input cancels any prior [searchJob] (which cancels the repo's mapLatest block,
     *   last-write-wins) and launches a fresh collector. [searching] flips on immediately so the
     *   UI can show a spinner the moment a new request starts.
     *
     * Note: we deliberately do NOT use [advanceUntilIdle]-style infinite collection here — the
     * collector runs on [viewModelScope] (lives for the VM lifetime, not the uiState subscription)
     * because cancelling it on a new keystroke is the whole point of last-write-wins. WhileSubscribed
     * would tear the collector down between keystrokes and break the cancellation contract.
     */
    fun setSearchQuery(q: String) {
        if (q.isBlank()) {
            _searchQuery.value = q
            // Cancel any in-flight search, drop stale results, mark not-searching.
            searchJob?.cancel()
            searchJob = null
            _searchResults.value = emptyList()
            _searching.value = false
            _lastSearchedQuery.value = ""
            return
        }
        // New query → cancel the prior collector, launch a fresh one. Do NOT
        // update _lastSearchedQuery here — it's only set when a response arrives.
        // The UI uses `searchQuery != lastSearchedQuery` (not the `searching` flag)
        // to decide whether to show a loading spinner or "No sessions match", which
        // is immune to one-frame ordering races.
        searchJob?.cancel()
        _searching.value = true
        _searchQuery.value = q
        AppLog.d("VM", "contentSearch: dispatched '$q'")
        searchJob = viewModelScope.launch {
            sessionsRepo.contentSearch(_searchQuery)
                .catch { e ->
                    if (e is CancellationException) throw e
                    // Any non-cancellation failure: drop searching; keep the prior results visible
                    // (last-good UX) instead of blanking the list on a transient backend error.
                    _searching.value = false
                    _lastSearchedQuery.value = _searchQuery.value  // mark this query as answered
                    AppLog.w("VM", "contentSearch: '$q' failed")
                }
                .collect { outcome ->
                    when (outcome) {
                        is Outcome.Success<SearchResponse> -> {
                            val count = outcome.value.results.size
                            _searchResults.value = outcome.value.results
                            _searching.value = false
                            _lastSearchedQuery.value = _searchQuery.value  // mark this query as answered
                            AppLog.d("VM", "contentSearch: '$q' -> $count results")
                        }
                        is Outcome.Failure -> {
                            // Same last-good policy as catch{}: keep prior results, just clear the
                            // spinner so the UI stops indicating an in-flight request.
                            _searching.value = false
                            _lastSearchedQuery.value = _searchQuery.value  // mark this query as answered
                            AppLog.w("VM", "contentSearch: '$q' failed")
                        }
                    }
                }
        }
    }
}
