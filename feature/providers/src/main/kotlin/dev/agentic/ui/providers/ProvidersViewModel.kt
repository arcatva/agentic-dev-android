package dev.agentic.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.log.AppLog
import dev.agentic.data.repo.ProvidersRepository
import dev.agentic.data.net.NewProviderReq
import dev.agentic.data.net.OAuthCompleteReq
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.Provider
import dev.agentic.data.net.runCatchingOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProvidersUiState(
    val providers: List<Provider> = emptyList(),
    /** Global cost⇄quality routing knob (0=cheapest..1=strongest). */
    val tradeoff: Float = 0.5f,
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
            // Global tradeoff knob (best-effort; keeps the default on failure).
            when (val rc = runCatchingOutcome { repo.getRouting() }) {
                is Outcome.Success -> _uiState.update { it.copy(tradeoff = rc.value.tradeoff) }
                is Outcome.Failure -> AppLog.w("VM", "routing load failed err=${rc.error}")
            }
        }
    }

    /** In-flight tradeoff POST, cancelled when a newer release supersedes it (below). */
    private var tradeoffSaveJob: Job? = null

    /** Persist the global tradeoff. Optimistic: the UI value updates immediately (on drag release);
     *  on a network failure it rolls back to the previous value and surfaces the error. Rapid
     *  releases cancel the prior in-flight save so only the LATEST released value can win (a slow
     *  older request can't land last and persist a stale value). */
    fun saveTradeoff(value: Float) {
        val previous = _uiState.value.tradeoff
        _uiState.update { it.copy(tradeoff = value, error = null) }
        tradeoffSaveJob?.cancel()
        tradeoffSaveJob = viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.setRouting(value) }) {
                is Outcome.Success -> AppLog.d("VM", "tradeoff saved value=$value")
                is Outcome.Failure -> {
                    AppLog.w("VM", "tradeoff save failed err=${r.error}")
                    _uiState.update { it.copy(tradeoff = previous, error = r.error.toString()) }
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

    /** Begin the ChatGPT OAuth login; [onUrl] receives the browser authorize URL to open. */
    fun startOauth(onUrl: (String) -> Unit) {
        _uiState.update { it.copy(error = null) }
        viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.oauthStart() }) {
                is Outcome.Success -> {
                    AppLog.d("VM", "oauth start ok")
                    onUrl(r.value.authorizeUrl)
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "oauth start failed err=${r.error}")
                    _uiState.update { it.copy(error = r.error.toString()) }
                }
            }
        }
    }

    /** Finish OAuth by relaying the code+state parsed out of the pasted redirect URL, then refresh so
     *  the GPT model appears. [onResult] gets null on success or an error message. */
    fun completeOauth(redirectUrl: String, model: String?, onResult: (String?) -> Unit) {
        val (code, state) = parseCallback(redirectUrl)
        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            onResult("Couldn't find code & state in that URL")
            return
        }
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val req = OAuthCompleteReq(code, state, model?.ifBlank { null })
            when (val r = runCatchingOutcome { repo.oauthComplete(req) }) {
                is Outcome.Success -> {
                    AppLog.d("VM", "oauth complete ok model=${r.value.model}")
                    _uiState.update { it.copy(busy = false) }
                    dev.agentic.ui.ModelCatalog.invalidate()
                    refresh()
                    onResult(null)
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "oauth complete failed err=${r.error}")
                    val msg = r.error.toString()
                    _uiState.update { it.copy(busy = false, error = msg) }
                    onResult(msg)
                }
            }
        }
    }

    /** Extract `code` and `state` from a pasted redirect URL (query params). */
    private fun parseCallback(url: String): Pair<String?, String?> {
        val query = url.substringAfter('?', "").substringBefore('#')
        var code: String? = null
        var state: String? = null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            when (pair.substring(0, eq)) {
                "code" -> code = pair.substring(eq + 1)
                "state" -> state = pair.substring(eq + 1)
            }
        }
        return code to state
    }
}
