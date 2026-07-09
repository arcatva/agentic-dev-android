package dev.agentic

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.agentic.data.log.AppLog
import dev.agentic.data.log.CrashHandler
import dev.agentic.data.log.LogStore
import dev.agentic.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Process-level entry point. Owns the single [AppContainer] (manual DI). */
class AgenticApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Logging FIRST: install the crash handler before anything else is wired up so a failure
        // during start-up is still captured to a file, then start mirroring logcat to disk.
        val logStore = LogStore(this)
        CrashHandler.install(logStore)
        container = AppContainer(this, logStore)
        // The single "Verbose logging" switch drives BOTH the verbose seams (AppLog.verbose) and the
        // logcat→file capture. Restore its persisted state at startup.
        AppLog.verbose = logStore.captureEnabled
        if (logStore.captureEnabled) container.logcatCollector.start()
        AppLog.i("App", "agentic-dev started (verbose=${logStore.captureEnabled})")

        // App-foreground self-heal: on every return to the foreground, force every live session's stream
        // to reconnect. A process-level backstop to the per-screen ON_RESUME refresh — centralized (can't
        // be lost to a UI refactor) and it heals any live session whose socket went half-open while
        // backgrounded. ON_START fires once on cold start (no sessions yet → no-op) and then on each
        // background→foreground transition.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppLog.v("Life", "process ON_START (foreground)")
                // Drop idle pooled sockets FIRST: a connection that went half-open while backgrounded
                // would otherwise be reused by the reconnect below and hang. Then reconnect live streams.
                //
                // OFF the main thread: evictAll() closes pooled sockets, and closing a TLS
                // (Conscrypt) socket writes the close_notify record — a network op that throws
                // NetworkOnMainThreadException on main (crashed on every foreground return once the
                // server went HTTPS; a plain-HTTP close never touched the network, which masked
                // this). One coroutine keeps the evict→reconnect ordering; reconnectLiveSessions
                // is thread-safe (lock + repo-scope restart()).
                container.appScope.launch(Dispatchers.IO) {
                    container.api.evictConnections()
                    container.sessionsRepo.reconnectLiveSessions()
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                // The transition that starts the idle-reaper clock — log it so a "frozen until restart"
                // report can be aligned against when the app went to background.
                AppLog.v("Life", "process ON_STOP (background)")
            }
        })

        // Re-register the FCM token with the backend on every app start when the user is already
        // logged in. This handles the case where onNewToken fired before the first-ever login
        // (backend rejected it) or the token rotated while the app was backgrounded.
        if (container.settings.token.value != null) {
            container.appScope.launch {
                runCatching { container.authRepo.refreshFcm() }
                    .onFailure { AppLog.w("FCM", "refresh on start failed: ${it.message}") }
            }
        }
    }
}
