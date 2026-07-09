package dev.agentic.ui.tree

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.log.AppLog
import dev.agentic.data.net.CommitFile
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.RepoCommits
import dev.agentic.data.net.userMessage
import dev.agentic.data.repo.FilesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Open detail sheet for one commit (or the uncommitted "working" node): the (repo, sha) it was
 * opened for, plus the loaded changed-file list and its loading/error state.
 */
data class CommitDetail(
    val repo: String,
    val sha: String,
    /** Display label for the sheet title (short sha, or "Uncommitted changes"). */
    val title: String = "",
    val files: List<CommitFile> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * UI state for the commit-graph screen. Loaded on demand (not a continuous stream), so a plain
 * MutableStateFlow is appropriate here — no stateIn/WhileSubscribed needed.
 *
 * [detail] is non-null while the changed-file bottom sheet is open.
 */
data class CommitGraphUiState(
    val repos: List<RepoCommits> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val detail: CommitDetail? = null,
)

/**
 * ViewModel for the commit-graph screen.
 *
 * Design note: uses a plain [MutableStateFlow] (not stateIn/WhileSubscribed) because the data is
 * fetched on demand — there is no infinite stream. [load] / [loadFilesFor] are bounded one-shot
 * coroutines; tests can safely use advanceUntilIdle. Cancellation is rethrown by the repo's
 * runCatchingOutcome (structured-concurrency rule), so ordinary navigation never runs a failure tail.
 *
 * [init] triggers the initial load so the screen shows data immediately on first composition.
 * [discard] issues a best-effort discard call then reloads.
 */
class CommitGraphViewModel(
    private val filesRepo: FilesRepository,
    handle: SavedStateHandle,
) : ViewModel() {

    private val id: String =
        requireNotNull(handle.get<String>("id")) { "CommitGraphViewModel requires an 'id' arg" }

    private val _uiState = MutableStateFlow(CommitGraphUiState())
    val uiState = _uiState.asStateFlow()

    init {
        load()
    }

    /** Fetch the per-repo commit history. Sets loading=true, then on completion sets repos or error. */
    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            when (val result = filesRepo.commits(id)) {
                is Outcome.Success -> {
                    AppLog.d("VM", "commits loaded id=$id repos=${result.value.size}")
                    _uiState.update {
                        it.copy(repos = result.value, loading = false, error = null)
                    }
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "commits load failed id=$id err=${result.error}")
                    _uiState.update {
                        it.copy(loading = false, error = result.error.userMessage())
                    }
                }
            }
        }
    }

    /**
     * Open the changed-file detail sheet for a commit (or the uncommitted "working" node) and load
     * its files. [title] is the label shown in the sheet header.
     */
    fun loadFilesFor(repo: String, sha: String, title: String = "") {
        _uiState.update { it.copy(detail = CommitDetail(repo = repo, sha = sha, title = title)) }
        viewModelScope.launch {
            when (val result = filesRepo.commitFiles(id, repo, sha)) {
                is Outcome.Success -> {
                    AppLog.d("VM", "commit files loaded id=$id repo=$repo sha=$sha count=${result.value.size}")
                    _uiState.update { st ->
                        // Guard against a race where the sheet was closed/reopened before this returned.
                        if (st.detail?.repo == repo && st.detail.sha == sha) {
                            st.copy(detail = st.detail.copy(files = result.value, loading = false, error = null))
                        } else st
                    }
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "commit files load failed id=$id repo=$repo sha=$sha err=${result.error}")
                    _uiState.update { st ->
                        if (st.detail?.repo == repo && st.detail.sha == sha) {
                            st.copy(detail = st.detail.copy(loading = false, error = result.error.userMessage()))
                        } else st
                    }
                }
            }
        }
    }

    /** Close the detail sheet. */
    fun closeDetail() {
        _uiState.update { it.copy(detail = null) }
    }

    /** Discard all uncommitted changes (best-effort) then reload the graph. */
    fun discard() {
        viewModelScope.launch {
            AppLog.d("VM", "discard uncommitted id=$id")
            filesRepo.discard(id)
            load()
        }
    }
}
