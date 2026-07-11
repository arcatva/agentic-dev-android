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
        // Logging FIRST so startup failures still capture.
        val logStore = LogStore(this)
        CrashHandler.install(logStore)
        container = AppContainer(this, logStore)
        AppLog.verbose = logStore.captureEnabled
        if (logStore.captureEnabled) container.logcatCollector.start()
        AppLog.i("App", "agentic-dev started (verbose=${logStore.captureEnabled})")

        // Foreground self-heal: reconnect all live streams on every ON_START (heals half-open sockets).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppLog.v("Life", "process ON_START (foreground)")
                // Evict pooled sockets FIRST off the main thread — closing a TLS socket writes close_notify, a network op that throws NetworkOnMainThreadException on main.
                container.appScope.launch(Dispatchers.IO) {
                    container.api.evictConnections()
                    container.sessionsRepo.reconnectLiveSessions()
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                AppLog.v("Life", "process ON_STOP (background)")
            }
        })

        // Re-register FCM token on every start when logged in (covers onNewToken-before-first-login and rotation-while-backgrounded).
        if (container.settings.token.value != null) {
            container.appScope.launch {
                runCatching { container.authRepo.refreshFcm() }
                    .onFailure { AppLog.w("FCM", "refresh on start failed: ${it.message}") }
            }
        }
    }
}
