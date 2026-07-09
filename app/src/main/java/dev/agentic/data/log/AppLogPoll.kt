package dev.agentic.data.log

/**
 * Accumulator for polling log state: logs ONCE on the first failure, then ONCE on recovery.
 * Quiet during steady state (success after success) and silent during sustained outage
 * (failure after failure) — avoids flooding the log with per-tick noise while still making
 * the failure visible and the recovery timestamped.
 *
 * Example:
 * ```kotlin
 * private val pollLog = PollLogState()
 * // On each tick:
 * pollLog.onTick("WFlow", "runs(id=${id.take(8)})", ok = result != null)
 * ```
 */
class PollLogState {
    private var failed: Boolean = false

    /**
     * Call on every poll tick. Logs at [AppLog.v] level only on state transitions:
     * - first failure after a run of successes → "… failed (backing off)"
     * - first success after a run of failures → "… recovered"
     * - steady state (success→success, failure→failure) → silent
     */
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
