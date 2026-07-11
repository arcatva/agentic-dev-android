package dev.agentic.data.log

import android.os.Process
import dev.agentic.data.log.AppLog
import kotlin.system.exitProcess

/** Catches uncaught exceptions on any thread, writes a crash report via [LogStore], then chains to the previous handler so the OS still shows its dialog / kills the process. Install once, as early as possible (from [dev.agentic.AgenticApp.onCreate]). */
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
        /** Idempotent install. */
        fun install(store: LogStore) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(store, current))
        }
    }
}
