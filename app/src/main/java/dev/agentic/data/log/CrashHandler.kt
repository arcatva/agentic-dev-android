package dev.agentic.data.log

import android.os.Process
import dev.agentic.data.log.AppLog
import kotlin.system.exitProcess

/**
 * Catches any uncaught exception on any thread, writes a standalone crash report via [LogStore],
 * then delegates to the previously-installed handler so the OS still does its normal thing (shows
 * the "app stopped" dialog, lets the process die, restarts if configured).
 *
 * Install once, as early as possible, from [dev.agentic.AgenticApp.onCreate] — before the rest of
 * the app is wired up — so a crash during start-up is still captured.
 */
class CrashHandler private constructor(
    private val store: LogStore,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try { store.writeCrash(thread, error) } catch (e: Throwable) { AppLog.e("App", "CrashHandler.writeCrash failed", e) }
        val prev = previous
        if (prev != null) {
            // Chain to the platform handler so the system crash dialog / RuntimeInit still runs.
            prev.uncaughtException(thread, error)
        } else {
            // No prior handler (unexpected): end the process ourselves so we don't hang.
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    companion object {
        /** Installs the handler unless one is already installed (idempotent). */
        fun install(store: LogStore) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(store, current))
        }
    }
}
