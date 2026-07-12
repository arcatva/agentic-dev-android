package dev.agentic.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.log.AppLog
import dev.agentic.data.repo.ProvidersRepository
import dev.agentic.data.net.NewProviderReq
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.Provider
import dev.agentic.data.net.runCatchingOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProvidersUiState(
    val providers: List<Provider> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Manage the BYOK provider registry (GET/POST/DELETE /api/providers). The delegate fan-out routes
 * cheap workers to these providers. Keys are write-only: the list never carries the key back
 * (`Provider.hasKey` only reflects whether one is stored).
 */
class ProvidersViewModel(private val repo: ProvidersRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ProvidersUiState())
    val uiState: StateFlow<ProvidersUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.providers() }) {
                is Outcome.Success -> {
                    AppLog.d("VM", "providers list loaded count=${r.value.size}")
                    _uiState.update { it.copy(providers = r.value, loading = false) }
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "providers list load failed err=${r.error}")
                    _uiState.update { it.copy(loading = false, error = r.error.toString()) }
                }
            }
        }
    }

    /** Add or replace a provider, then refresh. [onResult] gets null on success or an error message
     *  on failure (so the screen can clear the form only on success). */
    fun add(req: NewProviderReq, onResult: (String?) -> Unit) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.addProvider(req) }) {
                is Outcome.Success -> {
                    AppLog.d("VM", "provider added name=${req.name}")
                    _uiState.update { it.copy(busy = false) }
                    dev.agentic.ui.ModelCatalog.invalidate()
                    refresh()
                    onResult(null)
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "provider add failed name=${req.name} err=${r.error}")
                    val msg = r.error.toString()
                    _uiState.update { it.copy(busy = false, error = msg) }
                    onResult(msg)
                }
            }
        }
    }

    fun remove(name: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.deleteProvider(name) }) {
                is Outcome.Success -> {
                    AppLog.d("VM", "provider removed name=$name")
                    _uiState.update { it.copy(busy = false) }
                    dev.agentic.ui.ModelCatalog.invalidate()
                    refresh()
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "provider remove failed name=$name err=${r.error}")
                    _uiState.update { it.copy(busy = false, error = r.error.toString()) }
                }
            }
        }
    }
}
