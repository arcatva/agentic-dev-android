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
 * TOFU TLS pinning for the self-signed cert. Trust = platform CA-accepted OR leaf SHA-256 matches a
 * user-pinned fingerprint for this hostKey; otherwise [TofuTrustManager] throws [NeedsTrustException]
 * for the UI to confirm and pin. Pins by cert identity (not hostname) so a bare-IP URL keeps working.
 */

/** Normalise to "host:port" pin key; scheme-less input ("10.0.0.5:7420") is assumed https so the explicit port is preserved over the default. */
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

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun formatFingerprint(hex: String): String = hex.uppercase().chunked(2).joinToString(":")

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
 * Thrown when the server's cert is untrusted (or changed). Subclasses [CertificateException] so OkHttp
 * wraps it as an IOException out of the handshake; carries what the UI needs to prompt the user.
 */
class NeedsTrustException(
    val hostKey: String,
    /** Colon-free lowercase SHA-256 hex of the leaf cert DER. */
    val fingerprint: String,
) : CertificateException("untrusted certificate for $hostKey (sha256=$fingerprint)")

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
        // 1) Platform-trusted (real CA / BYO cert) → accept, no prompt.
        try {
            system.checkServerTrusted(certs.toList().toTypedArray(), authType ?: "RSA")
            return
        } catch (_: CertificateException) {
            // 2) Fall through: self-signed → trust only if leaf matches pinned fingerprint.
        }
        val leaf = certs.firstOrNull() ?: throw CertificateException("empty certificate chain")
        val fp = sha256Hex(leaf.encoded)
        val pinned = pinnedFingerprintFor(currentHostKey())
        if (pinned != null && pinned.equals(fp, ignoreCase = true)) return
        throw NeedsTrustException(currentHostKey(), fp)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    /** Accepts when the presented leaf matches the pin — so a pinned self-signed cert works even if the URL host isn't in the cert's SANs (e.g. a changed IP). Falls back to SAN matching otherwise. */
    fun hostnameVerifier(default: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()): HostnameVerifier =
        HostnameVerifier { hostname, session ->
            val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
            val fp = leaf?.let { sha256Hex(it.encoded) }
            val pinned = pinnedFingerprintFor(currentHostKey())
            if (fp != null && pinned != null && pinned.equals(fp, ignoreCase = true)) true
            else default.verify(hostname, session)
        }
}

/** OkHttp-facing TLS bundle (SSLSocketFactory + X509TrustManager + pin-aware HostnameVerifier), shared by REST and WS clients. */
class TofuTls(
    currentHostKey: () -> String,
    pinnedFingerprintFor: (hostKey: String) -> String?,
) {
    val trustManager: TofuTrustManager = TofuTrustManager(currentHostKey, pinnedFingerprintFor)
    val socketFactory: SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    val hostnameVerifier: HostnameVerifier = trustManager.hostnameVerifier()
}
