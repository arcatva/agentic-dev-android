package dev.agentic.data.log

import android.util.Log

/** Logging facade over [android.util.Log]. Lines land in logcat under our pid; [LogcatCollector] mirrors them to a file. [v]/[d] gated by [verbose]; [i]/[w]/[e] always fire. */
object AppLog {
    /** Diagnostics gate. OFF → [v]/[d] are no-ops (normal run is quiet, capture stays small); ON → per-frame / lifecycle seams (WS, Repo, Net, VM, Life) light up. */
    @Volatile var verbose: Boolean = false

    fun v(tag: String, msg: String) { if (verbose) safe { Log.d(tag, msg) } }
    fun d(tag: String, msg: String) { if (verbose) safe { Log.d(tag, msg) } }
    fun i(tag: String, msg: String) = safe { Log.i(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable? = null) = safe {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
    }
    fun e(tag: String, msg: String, tr: Throwable? = null) = safe {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    }
    fun e(tag: String, tr: Throwable) = safe { Log.e(tag, tr.message ?: "Unknown error", tr) }

    // Logging must NEVER crash the caller. On the JVM (unit tests) android.util.Log is a stub that
    // throws "Method … not mocked" — swallowing here keeps that local instead of a blanket
    // testOptions.returnDefaultValues that would mask every other unmocked Android call too.
    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }
}
