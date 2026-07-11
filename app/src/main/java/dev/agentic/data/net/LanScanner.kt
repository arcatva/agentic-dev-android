package dev.agentic.data.net

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import dev.agentic.data.log.AppLog

/** A discovered agentic-dev server on the LAN. */
data class DiscoveredServer(
    val ip: String,
    val port: Int = 7420,
    val latencyMs: Long,
    /** Server answered over TLS (default) vs plain HTTP (AGENTIC_TLS=off). */
    val https: Boolean = true,
) {
    val baseUrl: String get() = "${if (https) "https" else "http"}://$ip:$port"
}

/** Progressive updates emitted while scanning the LAN. */
sealed interface ScanUpdate {
    data class Progress(val scanned: Int, val total: Int) : ScanUpdate
    data class Found(val server: DiscoveredServer) : ScanUpdate
    data object Done : ScanUpdate
    data object NotOnLan : ScanUpdate
}

data class LocalNet(val ip: String, val prefixLen: Int)

interface NetworkInfoProvider {
    fun localNet(): LocalNet?
}

interface ServerProbe {
    suspend fun probe(ip: String): DiscoveredServer?
}

interface LanScanner {
    fun scan(): Flow<ScanUpdate>
}

/** Probe candidates for the /24 containing [localNet].ip (excluding .0, .255, and own address). Capped at /24 regardless of the real prefix so a wide subnet never blows up into thousands of probes. */
fun candidateIps(localNet: LocalNet): List<String> {
    val octets = localNet.ip.split(".").mapNotNull { it.toIntOrNull() }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return emptyList()
    val base = "${octets[0]}.${octets[1]}.${octets[2]}"
    val own = octets[3]
    return (1..254).filter { it != own }.map { "$base.$it" }
}

/**
 * Default [LanScanner]: enumerates the local /24, probes each host on a bounded pool, and emits each
 * [ScanUpdate.Found] the instant it is found (UI fills in live) + a [ScanUpdate.Progress] per
 * completed probe, then [ScanUpdate.Done]. Emits [ScanUpdate.NotOnLan] (alone) when no usable
 * local network is found. channelFlow's send is concurrency-safe; coroutineScope ensures the
 * collector cancels all in-flight probes on dispose (structured concurrency).
 */
class DefaultLanScanner(
    private val networkInfo: NetworkInfoProvider,
    private val probe: ServerProbe,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val concurrency: Int = 48,
) : LanScanner {
    override fun scan(): Flow<ScanUpdate> = channelFlow {
        val net = networkInfo.localNet()
        AppLog.d("Scan", "scan start: subnet=${net?.ip}/${net?.prefixLen}")
        if (net == null) {
            AppLog.d("Scan", "NotOnLan: no site-local IPv4 interface found")
            send(ScanUpdate.NotOnLan)
            return@channelFlow
        }
        val candidates = candidateIps(net)
        val total = candidates.size
        if (total == 0) {
            send(ScanUpdate.Done)
            return@channelFlow
        }
        val scanned = AtomicInteger(0)
        val found = AtomicInteger(0)
        val gate = Semaphore(concurrency)
        coroutineScope {
            candidates.forEach { ip ->
                launch(dispatcher) {
                    gate.withPermit {
                        probe.probe(ip)?.let {
                            send(ScanUpdate.Found(it))
                            found.incrementAndGet()
                        }
                    }
                    send(ScanUpdate.Progress(scanned.incrementAndGet(), total))
                }
            }
        }
        AppLog.d("Scan", "scan end: scanned=$total, found=${found.get()}")
        send(ScanUpdate.Done)
    }
}
