package dev.agentic.data.util

import dev.agentic.data.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Returns a cold [Flow] that:
 * 1. Immediately emits the result of [block] (no leading delay).
 * 2. Waits [intervalMs] milliseconds.
 * 3. Emits again and repeats indefinitely.
 *
 * Intended for polling list/usage/workflow endpoints so callers share one
 * implementation rather than duplicating the while-true/delay pattern.
 *
 * Usage:
 * ```kotlin
 * pollFlow(5_000L) { api.sessions() }
 *     .onEach { sessions -> _uiState.update { it.copy(sessions = sessions) } }
 *     .launchIn(viewModelScope)
 * ```
 */
fun <T> pollFlow(intervalMs: Long, block: suspend () -> T): Flow<T> = flow {
    var failures = 0
    while (true) {
        try {
            emit(block())
            failures = 0
            delay(intervalMs)
        } catch (e: CancellationException) {
            throw e   // cancellation must propagate, not be treated as a poll failure
        } catch (e: Throwable) {
            // A failing/unreachable server must NOT tear the flow down (a single throw would stop polling
            // forever) and must NOT be hammered every interval. Back off (capped exponential), then resume
            // the normal cadence once a tick succeeds. Callers that already swallow errors inside [block]
            // never reach here; this guards the ones that don't and bounds load on a down server.
            failures++
            AppLog.v("Poll", "pollFlow backoff #$failures: ${e.message}")
            val backoff = minOf(intervalMs shl minOf(failures, 5), 60_000L)
            delay(backoff)
        }
    }
}
