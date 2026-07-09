package dev.agentic.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.net.AppError
import dev.agentic.data.net.DiscoveredServer
import dev.agentic.data.net.LanScanner
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.ScanUpdate
import dev.agentic.data.net.userMessage
import dev.agentic.data.log.AppLog
import dev.agentic.data.repo.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which login sub-screen is showing. */
enum class LoginStep { Chooser, Scan, Manual }

/** An in-flight request to trust a self-signed server cert (trust-on-first-use). Shown as a dialog
 *  with the fingerprint; confirming pins it and retries the login. */
data class CertPrompt(val host: String, val hostKey: String, val fingerprint: String)

data class LoginUiState(
    val step: LoginStep = LoginStep.Chooser,
    // scan
    val scanning: Boolean = false,
    val scanProgress: Float? = null,            // 0f..1f, null = indeterminate
    val results: List<DiscoveredServer> = emptyList(),
    val selectedHost: String? = null,           // baseUrl of the chosen server
    val notOnLan: Boolean = false,
    // manual
    val host: String = "",
    // shared
    val password: String = "",
    val passwordVisible: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
    // Non-null while asking the user to trust a self-signed server cert.
    val certPrompt: CertPrompt? = null,
)

class LoginViewModel(
    private val authRepo: AuthRepository,
    private val lanScanner: LanScanner,
    initialHost: String = "",
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState(host = initialHost))
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init { startScan() }

    // Clear any prior submit error when moving between sub-screens, so a "Wrong password." / host
    // hint from one screen's Connect attempt doesn't linger on the next screen the user opens.
    fun goTo(step: LoginStep) { _uiState.update { it.copy(step = step, error = null) } }
    fun back() { _uiState.update { it.copy(step = LoginStep.Chooser, error = null) } }

    /** (Re)start a LAN scan, collecting the scanner flow into ui state. */
    fun startScan() {
        AppLog.d("VM", "Scan started")
        scanJob?.cancel()
        _uiState.update {
            it.copy(scanning = true, scanProgress = null, results = emptyList(),
                    selectedHost = null, notOnLan = false)
        }
        scanJob = viewModelScope.launch {
            lanScanner.scan().collect { update ->
                when (update) {
                    is ScanUpdate.Found -> {
                        AppLog.d("VM", "Found server at ${update.server.ip}")
                        _uiState.update { st ->
                        val merged = (st.results + update.server)
                            .distinctBy { it.baseUrl }
                            .sortedBy { it.latencyMs }
                        st.copy(results = merged)
                        }
                    }
                    is ScanUpdate.Progress -> _uiState.update {
                        it.copy(scanProgress = if (update.total > 0) update.scanned.toFloat() / update.total else null)
                    }
                    ScanUpdate.Done -> {
                        AppLog.d("VM", "Scan completed")
                        _uiState.update { st ->
                        val sole = st.results.singleOrNull()?.baseUrl
                        st.copy(scanning = false, selectedHost = st.selectedHost ?: sole)
                        }
                    }
                    ScanUpdate.NotOnLan -> {
                        AppLog.d("VM", "Not on LAN")
                        _uiState.update { it.copy(scanning = false, notOnLan = true) }
                    }
                }
            }
        }
    }

    fun rescan() = startScan()
    fun onSelectServer(baseUrl: String) { _uiState.update { it.copy(selectedHost = baseUrl) } }
    fun onHost(s: String) { _uiState.update { it.copy(host = s) } }
    fun onPassword(s: String) { _uiState.update { it.copy(password = s) } }
    fun togglePasswordVisible() { _uiState.update { it.copy(passwordVisible = !it.passwordVisible) } }

    /**
     * Log in against the host implied by the current step: the typed [LoginUiState.host] in Manual,
     * or the selected discovered server in Scan. A Scan submit with no selection is a no-op.
     */
    fun submit() {
        val state = _uiState.value
        val host = when (state.step) {
            LoginStep.Manual -> state.host
            else -> state.selectedHost ?: return
        }
        AppLog.d("Auth", "Login attempt to $host")
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val outcome = authRepo.login(host, state.password)) {
                is Outcome.Success -> {
                    AppLog.d("Auth", "Login success")
                    _uiState.update { it.copy(busy = false, done = true) }
                }
                is Outcome.Failure -> {
                    val err = outcome.error
                    if (err is AppError.CertUntrusted) {
                        // Self-signed cert not yet trusted → ask the user to verify + pin it.
                        AppLog.d("Auth", "cert untrusted for ${err.hostKey}: ${err.fingerprint}")
                        _uiState.update {
                            it.copy(busy = false, certPrompt = CertPrompt(host, err.hostKey, err.fingerprint))
                        }
                    } else {
                        val msg = loginErrorMessage(err)
                        AppLog.w("Auth", "Login failure: $msg")
                        _uiState.update { it.copy(busy = false, error = msg) }
                    }
                }
            }
        }
    }

    /** User verified the fingerprint and tapped Trust: pin the cert, then retry the login. */
    fun trustCertAndRetry() {
        val prompt = _uiState.value.certPrompt ?: return
        authRepo.trustCert(prompt.hostKey, prompt.fingerprint)
        _uiState.update { it.copy(certPrompt = null, busy = true, error = null) }
        submit()
    }

    /** User dismissed the trust prompt without pinning. */
    fun dismissCertPrompt() {
        _uiState.update { it.copy(certPrompt = null, busy = false) }
    }

    private fun loginErrorMessage(e: AppError): String = when {
        e is AppError.Http && e.code == 401 -> "Wrong password."
        e is AppError.Network -> "Can't reach the server — check the host address and your connection."
        else -> e.userMessage()
    }
}
