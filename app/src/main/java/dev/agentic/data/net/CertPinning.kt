package dev.agentic.data.net

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust-on-first-use (TOFU) certificate pinning for the self-signed cert the backend serves by
 * default. A server cert is trusted when EITHER the platform trust store already accepts it (a real
 * CA / operator "bring-your-own" cert — no prompt) OR its leaf SHA-256 matches the fingerprint the
 * user previously pinned for that host. Otherwise [TofuTrustManager] throws [NeedsTrustException],
 * which surfaces to the UI so the user can verify the fingerprint and pin it.
 *
 * Pinning is by cert identity, not hostname, so it keeps working when the app connects by a bare IP
 * (or the IP changes) — exactly the "connect to https://<ip>:7420" case.
 */

/** Normalise a base URL to a stable "host:port" pin key (lowercased; scheme/path stripped).
 *  Tolerates a scheme-less input (e.g. a manually-typed "10.0.0.5:7420") by assuming https, so the
 *  explicit port is preserved rather than collapsing to the default. */
fun certHostKey(baseUrl: String): String {
    val trimmed = baseUrl.trim()
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val uri = runCatching { java.net.URI(withScheme) }.getOrNull()
    val host = uri?.host
        ?: withScheme.substringAfter("://").substringBefore("/").substringBefore(":")
    val port = when {
        uri != null && uri.port != -1 -> uri.port
        withScheme.startsWith("https", ignoreCase = true) -> 443
        else -> 80
    }
    return "${host.lowercase()}:$port"
}

/** Lowercase, colon-free SHA-256 hex of the given bytes (a certificate's DER encoding). */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Group a colon-free hex fingerprint into `AA:BB:CC…` (uppercase) for display. */
fun formatFingerprint(hex: String): String = hex.uppercase().chunked(2).joinToString(":")

/** Walk the cause chain (cycle-safe) for the first throwable of type [T]. */
inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var cur: Throwable? = this
    val seen = HashSet<Throwable>()
    while (cur != null && seen.add(cur)) {
        if (cur is T) return cur
        cur = cur.cause
    }
    return null
}

/**
 * Thrown by [TofuTrustManager] when the server's cert is not yet trusted (or changed). Carries what
 * the UI needs to prompt the user. Subclasses [CertificateException] so it propagates out of the TLS
 * handshake (OkHttp wraps it in an SSLHandshakeException, i.e. an IOException).
 */
class NeedsTrustException(
    val hostKey: String,
    /** Colon-free lowercase SHA-256 hex of the leaf cert DER. */
    val fingerprint: String,
) : CertificateException("untrusted certificate for $hostKey (sha256=$fingerprint)")

/** The platform's default X509 trust manager (validates against the system CA store). */
private fun systemTrustManager(): X509TrustManager {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(null as KeyStore?)
    return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
}

class TofuTrustManager(
    private val currentHostKey: () -> String,
    private val pinnedFingerprintFor: (hostKey: String) -> String?,
    private val system: X509TrustManager = systemTrustManager(),
) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certs = chain ?: throw CertificateException("empty certificate chain")
        // 1) A cert the platform already trusts (real CA / BYO public cert) → accept, no prompt.
        try {
            system.checkServerTrusted(certs.toList().toTypedArray(), authType ?: "RSA")
            return
        } catch (_: CertificateException) {
            // Not chain-trusted → fall through to TOFU pinning below.
        }
        // 2) Self-signed → trust only if the leaf matches the pinned fingerprint for this host.
        val leaf = certs.firstOrNull() ?: throw CertificateException("empty certificate chain")
        val fp = sha256Hex(leaf.encoded)
        val pinned = pinnedFingerprintFor(currentHostKey())
        if (pinned != null && pinned.equals(fp, ignoreCase = true)) return
        throw NeedsTrustException(currentHostKey(), fp)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    /**
     * A HostnameVerifier that accepts when the presented leaf is the pinned cert — so a pinned
     * self-signed cert works even if the URL host isn't in the cert's SANs (e.g. a changed IP).
     * Falls back to [default] (normal SAN/hostname matching) otherwise.
     */
    fun hostnameVerifier(default: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()): HostnameVerifier =
        HostnameVerifier { hostname, session ->
            val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
            val fp = leaf?.let { sha256Hex(it.encoded) }
            val pinned = pinnedFingerprintFor(currentHostKey())
            if (fp != null && pinned != null && pinned.equals(fp, ignoreCase = true)) true
            else default.verify(hostname, session)
        }
}

/** Bundles the OkHttp-facing TLS pieces for TOFU: an SSLSocketFactory + its X509TrustManager + a
 *  pin-aware HostnameVerifier. Reused by both the REST and WebSocket OkHttp clients. */
class TofuTls(
    currentHostKey: () -> String,
    pinnedFingerprintFor: (hostKey: String) -> String?,
) {
    val trustManager: TofuTrustManager = TofuTrustManager(currentHostKey, pinnedFingerprintFor)
    val socketFactory: SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    val hostnameVerifier: HostnameVerifier = trustManager.hostnameVerifier()
}
