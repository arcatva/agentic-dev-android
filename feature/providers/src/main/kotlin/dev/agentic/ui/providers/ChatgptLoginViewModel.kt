package dev.agentic.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.log.AppLog
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.runCatchingOutcome
import dev.agentic.data.repo.ProvidersRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Connection status mirrored from the backend: not_connected | pending | connected | needs_reauth. */
data class ChatgptUiState(
    val status: String = "not_connected",
    val accountEmail: String? = null,
    val expiresAt: Long? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Drives the "Login with ChatGPT" flow. The OAuth callback is handled SERVER-SIDE (the redirect is a
 * fixed loopback on the platform host), so the client's job is: kick off the login, open the returned
 * authorize URL in a browser, then poll status until the server reports connected — at which point the
 * model catalog is refreshed so GPT shows up.
 */
class ChatgptLoginViewModel(private val repo: ProvidersRepository) : ViewModel() {

    private val _ui = MutableStateFlow(ChatgptUiState())
    val ui: StateFlow<ChatgptUiState> = _ui.asStateFlow()

    init { refreshStatus() }

    fun refreshStatus() {
        viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.chatgptStatus() }) {
                is Outcome.Success -> _ui.update {
                    it.copy(
                        status = r.value.status,
                        accountEmail = r.value.accountEmail,
                        expiresAt = r.value.expiresAt,
                    )
                }
                is Outcome.Failure -> AppLog.w("VM", "chatgpt status failed err=${r.error}")
            }
        }
    }

    /**
     * Begin a login: ask the server for the authorize URL, hand it to [openUrl] to open in a browser,
     * then poll until the server resolves the login. [onConnected] fires once, on success, so the
     * caller can refresh the provider/model lists.
     */
    fun login(openUrl: (String) -> Unit, onConnected: () -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.startChatgptLogin() }) {
                is Outcome.Success -> {
                    val url = r.value.authorizeUrl
                    if (url.isBlank()) {
                        _ui.update { it.copy(busy = false, error = "server returned no authorize URL") }
                        return@launch
                    }
                    openUrl(url)
                    _ui.update { it.copy(status = "pending") }
                    pollUntilResolved(onConnected)
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "chatgpt start failed err=${r.error}")
                    _ui.update { it.copy(busy = false, error = r.error.toString()) }
                }
            }
        }
    }

    /** Poll status ~every 2s for up to ~5 min (the server's login timeout). */
    private suspend fun pollUntilResolved(onConnected: () -> Unit) {
        repeat(150) {
            delay(2_000)
            when (val s = runCatchingOutcome { repo.chatgptStatus() }) {
                is Outcome.Success -> {
                    _ui.update {
                        it.copy(
                            status = s.value.status,
                            accountEmail = s.value.accountEmail,
                            expiresAt = s.value.expiresAt,
                        )
                    }
                    // Any status other than pending is terminal: connected (success), needs_reauth,
                    // or not_connected (the server's listener timed out / was cancelled). Stop
                    // polling and clear busy so the button isn't stuck for the full 5 min.
                    if (s.value.status != "pending") {
                        _ui.update { it.copy(busy = false) }
                        if (s.value.status == "connected") onConnected()
                        return
                    }
                }
                is Outcome.Failure -> AppLog.w("VM", "chatgpt poll failed err=${s.error}")
            }
        }
        // Timed out waiting for the browser sign-in.
        _ui.update { it.copy(busy = false) }
    }

    fun logout(onDone: () -> Unit) {
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.chatgptLogout() }) {
                is Outcome.Success -> {
                    _ui.update {
                        it.copy(status = "not_connected", accountEmail = null, expiresAt = null, busy = false)
                    }
                    onDone()
                }
                is Outcome.Failure -> {
                    // Backend may still hold the connection — keep the status, surface the error.
                    AppLog.w("VM", "chatgpt logout failed err=${r.error}")
                    _ui.update { it.copy(busy = false, error = r.error.toString()) }
                }
            }
        }
    }
}
