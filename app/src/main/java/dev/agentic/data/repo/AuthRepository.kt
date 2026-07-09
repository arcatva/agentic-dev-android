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
 *
 * - Exposes [token] and [isLoggedIn] as hot [StateFlow]s.
 * - [login] trims the host, sets [AgenticApi.baseUrl], calls [AgenticApi.login] (which sets
 *   [AgenticApi.token] internally), then persists both to [settings].
 * - [logout] clears [settings] and [api.token].
 * - [registerFcm] registers an FCM push token with the backend; best-effort (failures ignored).
 * - On construction, wires [AgenticApi.onUnauthorized] so any 401 triggers [logout].
 */
class AuthRepository(
    private val api: AgenticApi,
    private val settings: SettingsStore,
    scope: CoroutineScope,
) {
    /** Current auth token; mirrors [SettingsStore.token] exactly. */
    val token: StateFlow<String?> = settings.token

    private val _isLoggedIn = MutableStateFlow(settings.token.value != null)

    /** True when a non-null token is present. */
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    init {
        api.onUnauthorized = {
            AppLog.w("Auth", "got 401 from server — logging out")
            logout()
        }
        // Keep isLoggedIn in sync with settings.token reactively.
        scope.launch {
            settings.token.collect { _isLoggedIn.value = it != null }
        }
    }

    /**
     * Attempt login against [host] with [password].
     * On success the token is stored in [settings] and [api.token]; [token] and [isLoggedIn]
     * update reactively. On failure the current auth state is unchanged.
     * After a successful login, [refreshFcm] is called best-effort to register the FCM token
     * with the backend (FCM is not available in unit tests — failures are silently swallowed).
     */
    suspend fun login(host: String, password: String): Outcome<Unit> {
        val trimmedHost = host.trim()
        val outcome = runCatchingOutcome {
            api.baseUrl = trimmedHost
            api.login(password)          // sets api.token internally
            settings.setHost(trimmedHost)
            settings.setToken(api.token) // persist what the server returned
        }
        if (outcome is Outcome.Success) {
            // Best-effort: FCM token re-registration after login. Failures silently ignored so
            // push registration never blocks or breaks the login flow.
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

    /**
     * Pin a self-signed server cert (trust-on-first-use): after the user verifies the fingerprint,
     * persist it so subsequent TLS handshakes to [hostKey] trust that exact cert; the next login
     * attempt then succeeds. [hostKey] + [fingerprint] come from [dev.agentic.data.net.AppError.CertUntrusted].
     */
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

    /**
     * Register an FCM device token with the backend. Best-effort: failures are silently swallowed
     * so a push-registration problem never prevents the user from using the app.
     */
    suspend fun registerFcm(fcmToken: String) {
        runCatchingOutcome { api.registerDevice(fcmToken) }.also {
            if (it is Outcome.Failure) AppLog.w("Auth", "registerFcm failed: ${it.error}")
        }
    }

    /**
     * Fetch the current FCM registration token from Firebase and register it with the backend.
     * Call this after a successful login and on app start when already logged in, so the backend
     * always has a valid FCM token even if [onNewToken] fired before login completed.
     *
     * Best-effort: [FirebaseMessaging] is unavailable in JVM unit tests; any exception (including
     * "Default FirebaseApp is not initialized") is silently swallowed.
     */
    suspend fun refreshFcm() {
        runCatching {
            val fcmToken = FirebaseMessaging.getInstance().token.await()
            registerFcm(fcmToken)
        }.onFailure {
            AppLog.w("Auth", "refreshFcm failed: ${it.message}", it)
        }
    }
}
