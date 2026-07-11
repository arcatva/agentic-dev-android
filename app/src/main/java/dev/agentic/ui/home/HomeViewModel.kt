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
    /** True only on the FIRST sessionsStream failure; cleared on first success, so a later blip keeps last-good and does not re-flag. */
    val serverUnreachable: Boolean = false,
    /** True while a user-triggered pull-to-refresh is in flight. */
    val refreshing: Boolean = false,
    /** Pruned to ids still in [sessions] so the count bar never names a row that has scrolled off. */
    val selectedIds: Set<String> = emptySet(),
    /** Finished sessions the user has not read since completion — render the list-row dot. */
    val unreadIds: Set<String> = emptySet(),
    // ── Content search ───────────────────────────────────────────────────────────
    val searchQuery: String = "",
    val searchResults: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    /** Last query that COMPLETED (success or failure). `searchQuery != lastSearchedQuery` ⇒ in flight — spinner beats "No match". */
    val lastSearchedQuery: String = "",
    // ── Session groups ───────────────────────────────────────────────────────────
    val groups: List<Group> = emptyList(),
    val selectedGroupFilter: String? = null,
) {
    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    val selectedCount: Int get() = selectedIds.size
}

class HomeViewModel(
    private val sessionsRepo: SessionsRepository,
) : ViewModel() {

    init {
        refreshGroups()
    }

    // Pull-to-refresh: manual fetches emit here and are merged into the poll flows so they surface
    // immediately instead of waiting for the next tick. MutableSharedFlow (replay=0) NOT StateFlow —
    // a retained value would replay an OLD snapshot over live data after every background→foreground
    // (uiState is WhileSubscribed, so its upstream tears down + restarts on resume), flashing stale %.
    private val manualSessions = MutableSharedFlow<List<Session>>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val manualUsage = MutableSharedFlow<Usage>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _refreshing = MutableStateFlow(false)

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())

    // ── Session groups ───────────────────────────────────────────────────────────
    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    private val _selectedGroupFilter = MutableStateFlow<String?>(null)

    // ── Content-search plumbing ──────────────────────────────────────────────────
    private val _searchQuery = MutableStateFlow("")
    private val _searchResults = MutableStateFlow<List<SearchHit>>(emptyList())
    private val _searching = MutableStateFlow(false)
    private val _lastSearchedQuery = MutableStateFlow("")
    /** Cancelled on every new query so the prior mapLatest block is cancelled (last-write-wins) and no stale `searching=true` leaks past a fresh hit. */
    private var searchJob: Job? = null

    // Last good usage — re-emitted on a WhileSubscribed restart so a resumed Home is immediately
    // interactive instead of stalling until the live poll answers (combine waits for every source).
    // This is the value already on screen, not a manual snapshot, so re-emitting it never flashes stale.
    @Volatile private var lastUsage: Usage? = null

    private val usageFlow: Flow<Usage?> = flow {
        lastUsage?.let { emit(it) }
        emitAll(
            merge(
                pollFlow(60_000L) { runCatching { sessionsRepo.usage() }.getOrNull() },
                manualUsage,
            ).onEach { u -> if (u != null) lastUsage = u },
        )
    }

    private data class SessionsState(
        val sessions: List<Session> = emptyList(),
        val serverUnreachable: Boolean = false,
    )

    // Retained across WhileSubscribed restart: re-seeds scan() so resume doesn't re-fold from empty
    // and blank the list for a frame before the poll answers. If the FIRST post-restart tick fails,
    // we keep showing the retained list UNDER the "can't reach server" banner — intended last-good.
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
                // Prune ticks for vanished sessions — deleteSelected() never acts on a ghost id.
                val present = ss.sessions.mapTo(HashSet()) { it.id }
                _selectedIds.update { sel -> if (sel.isEmpty()) sel else sel.intersect(present) }
            },
        )
    }

    // combine has overloads up to 5 flows; we feed 7 — split across stages.
    val uiState: StateFlow<HomeUiState> =
        combine(sessionsState, usageFlow, _refreshing, _selectedIds) { ss, usage, refreshing, selected ->
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

    /** Re-fetch sessions + usage on demand; failures keep last-good. [refreshing] always clears. */
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

    /** Fire-and-forget; failures silently ignored. */
    fun delete(id: String) {
        viewModelScope.launch {
            sessionsRepo.delete(id)
            AppLog.d("VM", "delete: session $id")
        }
    }

    // ── multi-select delete ───────────────────────────────────────────────────────

    fun toggleSelection(id: String) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        _selectedIds.value = uiState.value.sessions.mapTo(LinkedHashSet()) { it.id }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    /** Ticks clear synchronously so the bar collapses immediately; deletes run on VM scope. */
    fun deleteSelected() {
        val ids = _selectedIds.value
        val n = ids.size
        _selectedIds.value = emptySet()
        viewModelScope.launch {
            ids.forEach { sessionsRepo.delete(it) }
            AppLog.d("VM", "delete: batch deleted $n sessions")
        }
    }

    /** Ticks clear synchronously so the bar collapses immediately; forks run on VM scope. */
    fun forkSelected() {
        val ids = _selectedIds.value
        val n = ids.size
        _selectedIds.value = emptySet()
        viewModelScope.launch {
            ids.forEach { sessionsRepo.fork(it) }
            AppLog.d("VM", "fork: batch forked $n sessions")
        }
    }

    /** Write the just-submitted prompt for [id] so the detail screen shows it before the log catches up. */
    fun setPendingPrompt(id: String, prompt: String) {
        sessionsRepo.pendingPrompts[id] = prompt
    }

    // ── Session groups ───────────────────────────────────────────────────────────

    fun selectGroupFilter(groupId: String?) {
        _selectedGroupFilter.value = groupId
    }

    fun refreshGroups() {
        viewModelScope.launch {
            sessionsRepo.listGroups().let { outcome ->
                if (outcome is Outcome.Success) _groups.value = outcome.value
            }
        }
    }

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

    /** Sessions in a deleted group become uncategorized. */
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

    fun moveSessionToGroup(sessionId: String, groupId: String?) {
        viewModelScope.launch {
            sessionsRepo.setSessionGroup(sessionId, groupId)
            AppLog.d("VM", "moveSessionToGroup: $sessionId -> ${groupId ?: "uncategorized"}")
        }
    }

    // ── Content search ───────────────────────────────────────────────────────────

    /**
     * Visible text mirrors into [_searchQuery] immediately (no perceived lag). The 250ms debounce
     * inside the repo only delays the BACKEND round-trip.
     *
     * Blank input cancels [searchJob], clears [_searchResults] / [_searching]; non-blank cancels
     * the prior job (repo mapLatest last-write-wins) and launches a fresh collector. Collector
     * runs on [viewModelScope] (NOT WhileSubscribed) so keystrokes can cancel it — the whole
     * point of last-write-wins.
     */
    fun setSearchQuery(q: String) {
        if (q.isBlank()) {
            _searchQuery.value = q
            searchJob?.cancel()
            searchJob = null
            _searchResults.value = emptyList()
            _searching.value = false
            _lastSearchedQuery.value = ""
            return
        }
        searchJob?.cancel()
        _searching.value = true
        _searchQuery.value = q
        AppLog.d("VM", "contentSearch: dispatched '$q'")
        searchJob = viewModelScope.launch {
            sessionsRepo.contentSearch(_searchQuery)
                .catch { e ->
                    if (e is CancellationException) throw e
                    // Last-good: keep prior results, just clear the spinner.
                    _searching.value = false
                    _lastSearchedQuery.value = _searchQuery.value
                    AppLog.w("VM", "contentSearch: '$q' failed")
                }
                .collect { outcome ->
                    when (outcome) {
                        is Outcome.Success<SearchResponse> -> {
                            val count = outcome.value.results.size
                            _searchResults.value = outcome.value.results
                            _searching.value = false
                            _lastSearchedQuery.value = _searchQuery.value
                            AppLog.d("VM", "contentSearch: '$q' -> $count results")
                        }
                        is Outcome.Failure -> {
                            _searching.value = false
                            _lastSearchedQuery.value = _searchQuery.value
                            AppLog.w("VM", "contentSearch: '$q' failed")
                        }
                    }
                }
        }
    }
}
