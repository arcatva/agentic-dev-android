package dev.agentic.data.log

import android.util.Log

/**
 * Thin logging facade over [android.util.Log]. Everything written here lands in logcat under our
 * pid, which [LogcatCollector] mirrors into a file — so sprinkling [AppLog] calls at key seams
 * (lifecycle, auth, network, push) makes the captured log actually useful when diagnosing a crash,
 * instead of an empty file. Use a short, stable [tag] per subsystem (e.g. "App", "Auth", "FCM").
 *
 * ## Level strategy
 * | Level | When to use                                          | Gated by [verbose]? |
 * |-------|------------------------------------------------------|---------------------|
 * | [v]   | Per-frame stream events, poll ticks, flow churn      | YES                 |
 * | [d]   | User actions + outcomes, network call success,       | YES                 |
 * |       | state transitions, expected catch blocks             |                     |
 * | [i]   | Major milestones: app start, login, logout,          | NO                  |
 * |       | session create                                       |                     |
 * | [w]   | Network failures, unexpected handled errors,         | NO                  |
 * |       | silent-catch rehabilitation                          |                     |
 * | [e]   | Fatal-but-caught: CrashHandler fallback,             | NO                  |
 * |       | dictation init-failed, unrecoverable scope errors    |                     |
 *
 * ## Tag convention
 * Short (3-5 char), stable, one per subsystem. Current tags: "App", "Life", "Activity", "FCM",
 * "Auth", "WS", "API", "Net", "Repo", "VM", "File", "WFlow", "Poll", "Voice", "Scan", "Nav",
 * "Store", "Diag", "Read".
 */
object AppLog {
    /**
     * Verbose diagnostics gate. OFF by default → [v]/[d] are no-ops, so the normal run is quiet and
     * the captured log stays small. The user flips it ON from the Diagnostics screen (the single
     * "Verbose logging" switch, persisted in [dev.agentic.data.log.LogStore]); [dev.agentic.AgenticApp]
     * restores it at startup. While ON, the per-frame / lifecycle / flow-lifecycle seams (WS, Repo,
     * Net, VM, Life) light up so a reproduced bug's whole run-up is in the shared log — this is what
     * pinned the foldable freeze (`Repo release` + `reconnectLiveSessions 0/0`). [i]/[w]/[e] always
     * fire regardless: important milestones and errors must be in even a non-verbose capture.
     */
    @Volatile var verbose: Boolean = false

    /** Verbose seam (per-frame, lifecycle, flow churn). Only emitted when [verbose] is on. */
    fun v(tag: String, msg: String) { if (verbose) safe { Log.d(tag, msg) } }
    /** Debug detail — also gated by [verbose] so toggling logging off truly quiets the app. */
    fun d(tag: String, msg: String) { if (verbose) safe { Log.d(tag, msg) } }
    fun i(tag: String, msg: String) = safe { Log.i(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable? = null) = safe {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
    }
    fun e(tag: String, msg: String, tr: Throwable? = null) = safe {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    }
    /** Convenience: log a bare [Throwable], using its message as the log message. */
    fun e(tag: String, tr: Throwable) = safe { Log.e(tag, tr.message ?: "Unknown error", tr) }

    /**
     * Run a [android.util.Log] call, swallowing any error. Logging must NEVER crash the caller — and
     * on the JVM (plain unit tests) `android.util.Log` is a stub that throws "Method … not mocked",
     * which would otherwise propagate out of whatever logged (e.g. AuthRepository.login) and fail the
     * test. Swallowing here keeps that local instead of forcing a blanket `testOptions
     * returnDefaultValues` that would mask every other unmocked Android call too.
     */
    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }
}
