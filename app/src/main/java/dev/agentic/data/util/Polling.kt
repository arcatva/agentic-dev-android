package dev.agentic.data.util

import dev.agentic.data.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Cold Flow: immediately emit [block], then every [intervalMs]. Cancellation propagates; other errors trigger capped-exponential backoff (max 60s) and resume the normal cadence on the next success. */
fun <T> pollFlow(intervalMs: Long, block: suspend () -> T): Flow<T> = flow {
    var failures = 0
    while (true) {
        try {
            emit(block())
            failures = 0
            delay(intervalMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failures++
            AppLog.v("Poll", "pollFlow backoff #$failures: ${e.message}")
            val backoff = minOf(intervalMs shl minOf(failures, 5), 60_000L)
            delay(backoff)
        }
    }
}
