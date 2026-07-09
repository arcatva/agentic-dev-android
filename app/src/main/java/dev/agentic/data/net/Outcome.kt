package dev.agentic.data.net

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking

/** Thrown when the backend rejects our token (401). Moved/copied here from net.Api so the data
 *  layer owns it; the old net.UnauthorizedException alias is kept in net/Api.kt for the old screens. */
class UnauthorizedException : Exception("unauthorized")

sealed interface AppError {
    /** A network-level failure (e.g. IOException, connection reset). */
    data class Network(val cause: Throwable) : AppError
    /** The backend returned 401 — token has expired or was revoked. */
    object Unauthorized : AppError
    /** The backend returned a non-2xx HTTP status (other than 401).
     *  [body] is the server's error message, if readable; null when the body could not be decoded. */
    data class Http(val code: Int, val body: String? = null) : AppError
    /** The server presented a TLS cert we don't trust yet (self-signed, not pinned). The UI shows
     *  [fingerprint] and lets the user pin it for [hostKey] (trust-on-first-use), then retry. */
    data class CertUntrusted(val hostKey: String, val fingerprint: String) : AppError
    /** Any other unexpected throwable. */
    data class Unknown(val cause: Throwable) : AppError
}

sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

/** A short, user-facing message for an [AppError] — never the raw toString()/stacktrace, which leaks
 *  internal types into the UI. `when` is exhaustive (no else), so a new AppError case fails to compile
 *  until it's given a message here. */
fun AppError.userMessage(): String = when (this) {
    is AppError.Network -> "Network error — check your connection."
    AppError.Unauthorized -> "Session expired — please sign in again."
    is AppError.Http -> {
        val detail = body?.let { ": $it" } ?: ""
        "Server error (HTTP $code$detail)."
    }
    is AppError.CertUntrusted -> "The server's certificate isn't trusted yet."
    is AppError.Unknown -> "Something went wrong."
}

/**
 * Executes [block] and wraps the result in [Outcome]:
 * - Success on normal return.
 * - Failure(Unauthorized) when the block throws [UnauthorizedException].
 * - Failure(Http) when the block throws a Ktor [io.ktor.client.plugins.ResponseException] (non-2xx, non-401).
 * - Failure(Network) when the block throws a [java.io.IOException].
 * - Failure(Unknown) for any other [Throwable].
 */
inline fun <T> runCatchingOutcome(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    // NEVER swallow cancellation: rethrow it so structured concurrency works. Catching it as a Failure
    // makes a coroutine cancelled by ordinary navigation run its failure tail (set queueError, roll back
    // an optimistic prompt) as if the request had really failed.
    throw e
} catch (e: UnauthorizedException) {
    Outcome.Failure(AppError.Unauthorized)
} catch (e: io.ktor.client.plugins.ResponseException) {
    val body: String? = try { runBlocking { e.response.bodyAsText() } } catch (_: Exception) { null }
    Outcome.Failure(AppError.Http(e.response.status.value, body?.take(200)))
} catch (e: java.io.IOException) {
    // A TLS handshake against an untrusted self-signed cert throws SSLHandshakeException (an
    // IOException) whose cause chain holds our NeedsTrustException — surface it distinctly so the
    // UI can offer to pin (trust-on-first-use) rather than showing a generic "network error".
    val needsTrust = e.findCause<NeedsTrustException>()
    if (needsTrust != null) Outcome.Failure(AppError.CertUntrusted(needsTrust.hostKey, needsTrust.fingerprint))
    else Outcome.Failure(AppError.Network(e))
} catch (e: Throwable) {
    // Some engines surface the handshake failure as a non-IOException wrapper; still catch it.
    val needsTrust = e.findCause<NeedsTrustException>()
    if (needsTrust != null) Outcome.Failure(AppError.CertUntrusted(needsTrust.hostKey, needsTrust.fingerprint))
    else Outcome.Failure(AppError.Unknown(e))
}
