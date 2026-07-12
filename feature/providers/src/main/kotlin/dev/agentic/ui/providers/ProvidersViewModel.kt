package dev.agentic.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.log.AppLog
import dev.agentic.data.repo.ProvidersRepository
import dev.agentic.data.net.NewProviderReq
import dev.agentic.data.net.OauthStatus
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.Provider
import dev.agentic.data.net.runCatchingOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProvidersUiState(
    val providers: List<Provider> = emptyList(),
    /** Global cost⇄quality routing knob (0=cheapest..1=strongest). */
    val tradeoff: Float = 0.5f,
    /** ChatGPT subscription connection state (null until first load). */
    val oauthStatus: OauthStatus? = null,
    /** True while a browser OAuth round-trip is in flight. */
    val connecting: Boolean = false,
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
            // ChatGPT subscription status (best-effort; leaves it unknown on failure).
            when (val os = runCatchingOutcome { repo.oauthChatgptStatus() }) {
                is Outcome.Success -> _uiState.update { it.copy(oauthStatus = os.value) }
                is Outcome.Failure -> AppLog.w("VM", "oauth status load failed err=${os.error}")
            }
        }
    }

    /**
     * Connect a personal ChatGPT subscription. Asks the server for the authorize URL, opens it via
     * [openBrowser], then captures the localhost:1455 redirect and completes the exchange server-side.
     * On success the provider + model lists refresh so the GPT model appears.
     */
    fun connectChatgpt(openBrowser: (String) -> Unit) {
        if (_uiState.value.connecting) return
        _uiState.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            val start = when (val r = runCatchingOutcome { repo.oauthChatgptStart() }) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> {
                    _uiState.update { it.copy(connecting = false, error = r.error.toString()) }
                    return@launch
                }
            }
            if (start.authorizeUrl.isBlank()) {
                _uiState.update { it.copy(connecting = false, error = "server returned no authorize URL") }
                return@launch
            }
            openBrowser(start.authorizeUrl)
            // Block on the loopback listener off the main thread until the browser redirects back.
            // Pass `isActive` so leaving the screen (scope cancel) frees port 1455 within ~2s.
            val code = withContext(Dispatchers.IO) {
                awaitChatGptOAuthCode(start.state, shouldContinue = { isActive })
            }
            if (code == null) {
                _uiState.update {
                    it.copy(connecting = false, error = "ChatGPT login timed out or was cancelled")
                }
                return@launch
            }
            when (val c = runCatchingOutcome { repo.oauthChatgptComplete(start.state, code) }) {
                is Outcome.Success -> {
                    AppLog.d("VM", "chatgpt oauth connected")
                    _uiState.update { it.copy(connecting = false) }
                    dev.agentic.ui.ModelCatalog.invalidate()
                    refresh()
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "chatgpt oauth complete failed err=${c.error}")
                    _uiState.update { it.copy(connecting = false, error = c.error.toString()) }
                }
            }
        }
    }

    /** Disconnect the ChatGPT subscription: drops the stored tokens + the `chatgpt` provider. */
    fun disconnectChatgpt() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.oauthChatgptLogout() }) {
                is Outcome.Success -> {
                    AppLog.d("VM", "chatgpt oauth disconnected")
                    _uiState.update { it.copy(busy = false) }
                    dev.agentic.ui.ModelCatalog.invalidate()
                    refresh()
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "chatgpt oauth logout failed err=${r.error}")
                    _uiState.update { it.copy(busy = false, error = r.error.toString()) }
                }
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
}
