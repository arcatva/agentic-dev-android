package dev.agentic.di

import android.app.Application
import dev.agentic.data.SessionUiStore
import dev.agentic.data.SettingsStore
import dev.agentic.data.SharedPrefsSettingsStore
import dev.agentic.data.log.LogStore
import dev.agentic.data.log.LogcatCollector
import dev.agentic.data.net.AgenticApi
import dev.agentic.data.net.DefaultLanScanner
import dev.agentic.data.net.HealthzServerProbe
import dev.agentic.data.net.KtorAgenticApi
import dev.agentic.data.net.LanScanner
import dev.agentic.data.net.NetworkMonitor
import dev.agentic.data.net.RealNetworkInfoProvider
import dev.agentic.data.repo.AuthRepository
import dev.agentic.data.repo.FilesRepository
import dev.agentic.data.repo.SessionsRepository
import dev.agentic.data.repo.WorkflowsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manual dependency-injection container (no Hilt/Koin). Created once in [dev.agentic.AgenticApp].
 *
 * Wires the data layer:
 *   - [appScope]    — app-lifetime CoroutineScope (SupervisorJob + Default dispatcher)
 *   - [settings]    — SharedPrefs-backed SettingsStore (token + host)
 *   - [api]         — Ktor HTTP client initialised from persisted host/token
 *   - [authRepo]    — single source of truth for auth state; wires 401 → logout automatically
 *   - [sessionsRepo]   — single source of truth for session-list + per-session transcript state
 *   - [workflowsRepo]  — polls workflow-run and outbox endpoints; shared across UI surfaces
 *   - [filesRepo]      — upload, download bytes, diff, and discard for a session's files
 *   - [close]          — cancels [appScope] and closes the Ktor client (call from Application.onTerminate)
 */
class AppContainer(val app: Application, val logStore: LogStore = LogStore(app)) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val logcatCollector: LogcatCollector = LogcatCollector(logStore)
    val settings: SettingsStore = SharedPrefsSettingsStore(app)
    val api: AgenticApi = KtorAgenticApi(settings.host, settings.token.value) { hostKey ->
        settings.pinnedCert(hostKey)
    }
    val authRepo: AuthRepository = AuthRepository(api, settings, appScope)
    val networkMonitor: NetworkMonitor = NetworkMonitor(app)
    val sessionsRepo: SessionsRepository = SessionsRepository(
        api, appScope, settings, networkMonitor.available,
        idleReleaseMs = SessionsRepository.IDLE_STREAM_RELEASE_MS,
    )
    val workflowsRepo: WorkflowsRepository = WorkflowsRepository(api, appScope)
    val filesRepo: FilesRepository = FilesRepository(api)
    val lanScanner: LanScanner = DefaultLanScanner(RealNetworkInfoProvider(), HealthzServerProbe())
    val sessionUiStore: SessionUiStore = SessionUiStore(app)
    /**
     * UI handoff: a request to select (open) a session inside the single wide 3-pane Home.
     *
     * Set by [dev.agentic.ui.nav.AppNav] for the wide layout when a new request is created, a
     * session deep link / notification is tapped, or a pending deep link replays after login —
     * and consumed (reset to null) by [dev.agentic.ui.home.WideThreePaneHome] once applied.
     *
     * In wide mode the Home already shows the list AND the session, so navigating to a separate
     * `Session` route would render a *second* identical full-screen Home and stack it on the back
     * stack (the "two-layer Home" bug, where Back peels through duplicate Homes). Funnelling the
     * selection into the existing Home via this channel keeps exactly one Home on the stack. The
     * narrow layout is unaffected — it keeps using the `Session` route. null = no pending request.
     */
    val homeSelectRequest: MutableStateFlow<String?> = MutableStateFlow(null)

    fun close() {
        networkMonitor.close()
        api.close()
        appScope.cancel()
    }
}
