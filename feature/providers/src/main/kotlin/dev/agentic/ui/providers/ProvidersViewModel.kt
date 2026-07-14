package dev.agentic.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.log.AppLog
import dev.agentic.data.repo.ProvidersRepository
import dev.agentic.data.net.ChatGptStatus
import dev.agentic.data.net.NewProviderReq
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.Provider
import dev.agentic.data.net.runCatchingOutcome
import kotlinx.coroutines.delay
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
    /** ChatGPT subscription login state (null until first loaded / on load failure). */
    val chatgpt: ChatGptStatus? = null,
    /** True while a login flow is pending (waiting on the browser callback). */
    val chatgptBusy: Boolean = false,
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
        refreshChatGpt()
    }

    /** Load the ChatGPT subscription login status (best-effort; leaves prior state on failure). */
    fun refreshChatGpt() {
        viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.chatgptStatus() }) {
                is Outcome.Success -> _uiState.update { it.copy(chatgpt = r.value) }
                is Outcome.Failure -> AppLog.w("VM", "chatgpt status load failed err=${r.error}")
            }
        }
    }

    /** Kick off the ChatGPT OAuth login: ask the server for the authorize URL, hand it to [openUrl]
     *  (the screen opens a browser), then poll status until the browser callback completes on the
     *  server. On success the provider + model lists refresh so the GPT models appear. */
    fun startChatGptLogin(openUrl: (String) -> Unit) {
        if (_uiState.value.chatgptBusy) return
        _uiState.update { it.copy(chatgptBusy = true, error = null) }
        viewModelScope.launch {
            val url = when (val r = runCatchingOutcome { repo.chatgptStartLogin() }) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> {
                    _uiState.update { it.copy(chatgptBusy = false, error = r.error.toString()) }
                    return@launch
                }
            }
            if (url.isBlank()) {
                _uiState.update { it.copy(chatgptBusy = false, error = "server returned no authorize URL") }
                return@launch
            }
            openUrl(url)
            // Poll the server for the callback result. The server's listener times out at 5 min;
            // match that with ~150 polls at 2s. Stop as soon as the login lands.
            repeat(150) {
                delay(2_000)
                when (val s = runCatchingOutcome { repo.chatgptStatus() }) {
                    is Outcome.Success -> {
                        _uiState.update { it.copy(chatgpt = s.value) }
                        // Completion = a usable login. During a RE-auth the server keeps reporting
                        // loggedIn=true (old token present) with needsReauth=true until the new
                        // callback lands, so require !needsReauth or we'd stop polling too early.
                        if (s.value.loggedIn && !s.value.needsReauth) {
                            AppLog.d("VM", "chatgpt login complete")
                            _uiState.update { it.copy(chatgptBusy = false) }
                            dev.agentic.ui.ModelCatalog.invalidate()
                            refresh() // pull in the freshly-registered GPT providers
                            return@launch
                        }
                    }
                    is Outcome.Failure -> AppLog.w("VM", "chatgpt poll failed err=${s.error}")
                }
            }
            _uiState.update { it.copy(chatgptBusy = false) } // timed out; user can retry
        }
    }

    fun logoutChatGpt() {
        _uiState.update { it.copy(chatgptBusy = true, error = null) }
        viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.chatgptLogout() }) {
                is Outcome.Success -> {
                    _uiState.update { it.copy(chatgptBusy = false) }
                    dev.agentic.ui.ModelCatalog.invalidate()
                    refresh()
                }
                is Outcome.Failure -> _uiState.update {
                    it.copy(chatgptBusy = false, error = r.error.toString())
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
