package dev.agentic.data.repo

import com.google.firebase.messaging.FirebaseMessaging
import dev.agentic.data.SettingsStore
import dev.agentic.data.log.AppLog
import dev.agentic.data.net.AgenticApi
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.runCatchingOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Single source of truth for authentication state.
 * - [login] trims host, sets api.baseUrl, calls api.login (which sets api.token), persists to [settings].
 * - Wires api.onUnauthorized so any 401 triggers [logout].
 * - [registerFcm] / [refreshFcm] are best-effort (failures silently swallowed).
 */
class AuthRepository(
    private val api: AgenticApi,
    private val settings: SettingsStore,
    scope: CoroutineScope,
) {
    val token: StateFlow<String?> = settings.token

    private val _isLoggedIn = MutableStateFlow(settings.token.value != null)

    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    init {
        api.onUnauthorized = {
            AppLog.w("Auth", "got 401 from server — logging out")
            logout()
        }
        scope.launch {
            settings.token.collect { _isLoggedIn.value = it != null }
        }
    }

    /** On success the token is in [settings] + [api.token]; [token]/[isLoggedIn] update reactively; FCM is re-registered best-effort (FCM unavailable in unit tests — swallowed). */
    suspend fun login(host: String, password: String): Outcome<Unit> {
        val trimmedHost = host.trim()
        val outcome = runCatchingOutcome {
            api.baseUrl = trimmedHost
            api.login(password)
            settings.setHost(trimmedHost)
            settings.setToken(api.token)
        }
        if (outcome is Outcome.Success) {
            runCatching { refreshFcm() }
        }
        return when (outcome) {
            is Outcome.Success -> {
                AppLog.i("Auth", "login ok @ $trimmedHost")
                Outcome.Success(Unit)
            }
            is Outcome.Failure -> {
                AppLog.w("Auth", "login failed @ $trimmedHost: $outcome")
                outcome
            }
        }
    }

    /** Pin a self-signed server cert (TOFU). [hostKey] + [fingerprint] come from [dev.agentic.data.net.AppError.CertUntrusted]. */
    fun trustCert(hostKey: String, fingerprint: String) {
        AppLog.i("Auth", "pinning server cert for $hostKey ($fingerprint)")
        settings.setPinnedCert(hostKey, fingerprint)
    }

    /** Clear the stored token and revoke api access. */
    fun logout() {
        AppLog.i("Auth", "logout")
        settings.setToken(null)
        api.token = null
    }

    /** Register an FCM device token with the backend. Best-effort. */
    suspend fun registerFcm(fcmToken: String) {
        runCatchingOutcome { api.registerDevice(fcmToken) }.also {
            if (it is Outcome.Failure) AppLog.w("Auth", "registerFcm failed: ${it.error}")
        }
    }

    /** Fetch the current FCM token from Firebase and register it with the backend. Best-effort: FCM unavailable in unit tests — exceptions silently swallowed. */
    suspend fun refreshFcm() {
        runCatching {
            val fcmToken = FirebaseMessaging.getInstance().token.await()
            registerFcm(fcmToken)
        }.onFailure {
            AppLog.w("Auth", "refreshFcm failed: ${it.message}", it)
        }
    }
}
