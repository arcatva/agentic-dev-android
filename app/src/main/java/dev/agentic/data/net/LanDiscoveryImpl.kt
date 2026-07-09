package dev.agentic.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import dev.agentic.data.log.AppLog

/**
 * Resolves the device's local private-IPv4 network from the up, non-loopback interfaces,
 * preferring Wi-Fi (`wlan*`). Returns null when only loopback / mobile / VPN-CGNAT interfaces
 * are present. [Inet4Address.isSiteLocalAddress] is true for 10/8, 172.16/12, 192.168/16 and
 * FALSE for the Tailscale CGNAT range 100.64/10 — so a Tailscale interface is never picked.
 */
class RealNetworkInfoProvider : NetworkInfoProvider {
    override fun localNet(): LocalNet? {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .onFailure { AppLog.w("Scan", "localNet failed to enumerate interfaces: ${it.message}", it) }
            .getOrNull().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            // Probe wlan* first so a phone with both Wi-Fi and another up interface picks Wi-Fi.
            .sortedByDescending { (it.name ?: "").startsWith("wlan") }
        for (iface in ifaces) {
            for (addr in iface.interfaceAddresses) {
                val ip = addr.address
                if (ip is Inet4Address && ip.isSiteLocalAddress) {
                    val host = ip.hostAddress ?: continue
                    return LocalNet(host, addr.networkPrefixLength.toInt())
                }
            }
        }
        return null
    }
}

/**
 * Confirms a host is an agentic-dev server by opening a raw TCP socket to ip:7420 (short connect
 * timeout) and issuing a minimal HTTP/1.0 `GET /healthz`. Accepts the host only when the response
 * is `200` with a body that trims to `ok` — the backend's healthz contract — so an unrelated
 * service occupying 7420 is rejected. Records round-trip latency. Returns null on any failure.
 *
 * Uses a raw socket rather than the shared KtorAgenticApi: KtorAgenticApi has a single mutable
 * baseUrl and cannot be pointed at 250 hosts in parallel. HTTP/1.0 + `Connection: close` makes the
 * server close the socket so readBytes() terminates; soTimeout guards a stalled read.
 */
class HealthzServerProbe(
    private val connectTimeoutMs: Int = 400,
    private val readTimeoutMs: Int = 600,
    private val port: Int = 7420,
) : ServerProbe {
    override suspend fun probe(ip: String): DiscoveredServer? = withContext(Dispatchers.IO) {
        // Servers are HTTPS by default; try TLS first, then plain HTTP (for AGENTIC_TLS=off hosts).
        val found = probeScheme(ip, https = true) ?: probeScheme(ip, https = false)
        if (found != null) {
            AppLog.v("Scan", "discovered server at $ip (${if (found.https) "https" else "http"}), latency=${found.latencyMs}ms")
        } else {
            AppLog.v("Scan", "probe miss: $ip (not an agentic-dev server)")
        }
        found
    }

    /**
     * Open a (TLS or plain) socket to ip:port, issue a minimal `GET /healthz`, and return a
     * [DiscoveredServer] iff the response is `200` with body `ok`. Discovery only checks liveness
     * (no credentials are sent), so TLS certs are intentionally NOT validated here (trust-all) — the
     * real login still pins the cert via [TofuTls]. HTTP/1.0 + `Connection: close` terminates the read.
     */
    private fun probeScheme(ip: String, https: Boolean): DiscoveredServer? {
        val startNanos = System.nanoTime()
        return runCatching {
            val socket = if (https) trustAllSocketFactory.createSocket() as SSLSocket else Socket()
            socket.use { s ->
                s.connect(InetSocketAddress(ip, port), connectTimeoutMs)
                s.soTimeout = readTimeoutMs
                if (s is SSLSocket) s.startHandshake()
                s.getOutputStream().apply {
                    write("GET /healthz HTTP/1.0\r\nHost: $ip\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                val resp = s.getInputStream().readBytes().decodeToString()
                val statusLine = resp.substringBefore("\r\n")
                val body = resp.substringAfter("\r\n\r\n", "").trim()
                // Status code is the 2nd space-delimited field ("HTTP/1.x 200 OK"); a field-position
                // check avoids matching a reason phrase or a longer code like "1200" that contains " 200".
                val statusCode = statusLine.split(" ").getOrNull(1)
                val ok = statusLine.startsWith("HTTP/1.") && statusCode == "200" && body == "ok"
                if (ok) {
                    DiscoveredServer(ip, port, (System.nanoTime() - startNanos) / 1_000_000, https = https)
                } else {
                    null
                }
            }
        }.onFailure { AppLog.v("Scan", "probe $ip (${if (https) "https" else "http"}) failed: ${it.message}") }
            .getOrNull()
    }
}

/** Trust-all TLS factory used ONLY for LAN discovery liveness probes (never for credentialed
 *  traffic — login pins the cert via [TofuTls]). */
private val trustAllSocketFactory: SSLSocketFactory by lazy {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), null) }.socketFactory
}
