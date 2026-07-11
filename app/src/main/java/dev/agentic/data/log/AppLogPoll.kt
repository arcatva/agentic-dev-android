package dev.agentic.data.log

/** Polling accumulator: logs ONCE on first failure, then ONCE on recovery. Silent in steady state — avoids per-tick noise. */
class PollLogState {
    private var failed: Boolean = false

    /** Logs only on transitions: success→failure ("failed (backing off)") and failure→success ("recovered"). */
    fun onTick(tag: String, subject: String, ok: Boolean) {
        if (ok) {
            if (failed) {
                AppLog.v(tag, "$subject recovered")
                failed = false
            }
        } else {
            if (!failed) {
                AppLog.v(tag, "$subject failed (backing off)")
                failed = true
            }
        }
    }
}
