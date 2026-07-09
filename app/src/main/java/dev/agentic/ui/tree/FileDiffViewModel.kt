package dev.agentic.ui.tree

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.log.AppLog
import dev.agentic.data.net.FileDiff
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.userMessage
import dev.agentic.data.repo.FilesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the single-file diff screen. [path]/[sha] are known up-front (from the route) so the
 * top bar can render before the diff loads. [diff] is null until the fetch completes.
 */
data class FileDiffUiState(
    val path: String = "",
    val sha: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val diff: FileDiff? = null,
)

/**
 * ViewModel for [FileDiffScreen]. Reads (id, repo, sha, path) from the type-safe route's
 * [SavedStateHandle] and fetches the parsed diff once on creation. Like [CommitGraphViewModel] it
 * uses a plain [MutableStateFlow] because the data is a bounded one-shot fetch, not a stream.
 */
class FileDiffViewModel(
    private val filesRepo: FilesRepository,
    handle: SavedStateHandle,
) : ViewModel() {

    private val id: String =
        requireNotNull(handle.get<String>("id")) { "FileDiffViewModel requires an 'id' arg" }
    private val repo: String = handle.get<String>("repo").orEmpty()
    private val sha: String = handle.get<String>("sha").orEmpty()
    private val path: String = handle.get<String>("path").orEmpty()

    private val _uiState = MutableStateFlow(FileDiffUiState(path = path, sha = sha))
    val uiState = _uiState.asStateFlow()

    init {
        load()
    }

    /** Fetch the line-level diff for this file. Sets loading=true, then diff or error. */
    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            when (val result = filesRepo.commitDiff(id, repo, sha, path)) {
                is Outcome.Success -> {
                    AppLog.d("VM", "diff loaded id=$id repo=$repo sha=$sha path=$path")
                    _uiState.update {
                        it.copy(diff = result.value, loading = false, error = null)
                    }
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "diff load failed id=$id repo=$repo sha=$sha path=$path err=${result.error}")
                    _uiState.update {
                        it.copy(loading = false, error = result.error.userMessage())
                    }
                }
            }
        }
    }
}
