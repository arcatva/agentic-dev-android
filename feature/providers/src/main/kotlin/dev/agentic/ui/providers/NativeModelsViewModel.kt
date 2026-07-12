package dev.agentic.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.log.AppLog
import dev.agentic.data.repo.ProvidersRepository
import dev.agentic.data.net.NativeFamily
import dev.agentic.data.net.NativeOverrideReq
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.runCatchingOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NativeModelsUiState(
    val families: List<NativeFamily> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Manage per-family routing overrides for the native Claude models
 * (GET/POST/DELETE /api/native-models). Separate from ProvidersViewModel: native models are
 * discovered, not BYOK, and these overrides do not affect the session main-model picker.
 */
class NativeModelsViewModel(private val repo: ProvidersRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(NativeModelsUiState())
    val uiState: StateFlow<NativeModelsUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.nativeModels() }) {
                is Outcome.Success -> _uiState.update { it.copy(families = r.value, loading = false) }
                is Outcome.Failure -> {
                    AppLog.w("VM", "native models load failed err=${r.error}")
                    _uiState.update { it.copy(loading = false, error = r.error.toString()) }
                }
            }
        }
    }

    /** Save a family override, then refresh. [onResult] gets null on success or an error message. */
    fun save(family: String, req: NativeOverrideReq, onResult: (String?) -> Unit) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.putNativeOverride(family, req) }) {
                is Outcome.Success -> {
                    _uiState.update { it.copy(busy = false) }
                    refresh()
                    onResult(null)
                }
                is Outcome.Failure -> {
                    val msg = r.error.toString()
                    _uiState.update { it.copy(busy = false, error = msg) }
                    onResult(msg)
                }
            }
        }
    }

    /** Reset a family to defaults, then refresh. [onResult] gets null on success or an error message
     *  (symmetric with [save], so the dialog only dismisses on success). */
    fun reset(family: String, onResult: (String?) -> Unit) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.deleteNativeOverride(family) }) {
                is Outcome.Success -> {
                    _uiState.update { it.copy(busy = false) }
                    refresh()
                    onResult(null)
                }
                is Outcome.Failure -> {
                    val msg = r.error.toString()
                    _uiState.update { it.copy(busy = false, error = msg) }
                    onResult(msg)
                }
            }
        }
    }
}