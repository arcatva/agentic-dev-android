package dev.agentic.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Emits [Unit] when the device gains a default network so long-lived stream reconnect loops can
 * wake from backoff and retry immediately instead of waiting out the timer. App-lifetime.
 * Requires ACCESS_NETWORK_STATE.
 */
class NetworkMonitor(context: Context) {
    // replay = 0, buffer = 1 + DROP_OLDEST: tryEmit never suspends, and a regain landing just before the
    // loop starts collecting is still picked up on the next collect (no missed-edge stall).
    private val _available = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Hot signal of "a network just became available". Never completes. */
    val available: SharedFlow<Unit> = _available

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            dev.agentic.data.log.AppLog.v("Net", "network available — waking reconnect loops")
            _available.tryEmit(Unit)
        }
        override fun onLost(network: Network) {
            dev.agentic.data.log.AppLog.v("Net", "network lost")
        }
    }

    init { cm.registerDefaultNetworkCallback(callback) }

    fun close() { runCatching { cm.unregisterNetworkCallback(callback) } }
}
