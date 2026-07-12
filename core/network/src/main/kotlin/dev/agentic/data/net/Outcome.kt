package dev.agentic.data.net

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking

/** Thrown when the backend rejects our token (401). */
class UnauthorizedException : Exception("unauthorized")

sealed interface AppError {
    data class Network(val cause: Throwable) : AppError
    object Unauthorized : AppError
    data class Http(val code: Int, val body: String? = null) : AppError
    /** Server presented a TLS cert we don't trust yet (self-signed, not pinned). UI shows [fingerprint] and lets the user pin it for [hostKey] (TOFU), then retry. */
    data class CertUntrusted(val hostKey: String, val fingerprint: String) : AppError
    data class Unknown(val cause: Throwable) : AppError
}

sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

/** Short, user-facing message; `when` is exhaustive so a new AppError case fails to compile until mapped here. */
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
 * Success / Unauthorized / Http (non-2xx, non-401) / Network (IOException) / Unknown.
 * TLS-handshake SSLHandshakeException whose cause chain holds NeedsTrustException is surfaced as
 * CertUntrusted so the UI can offer to pin (TOFU) rather than a generic "network error".
 */
inline fun <T> runCatchingOutcome(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    // NEVER swallow cancellation — rethrow so structured concurrency works. Treating it as a Failure
    // makes a normally-cancelled coroutine run its failure tail (queueError, optimistic rollback) as if the request had really failed.
    throw e
} catch (e: UnauthorizedException) {
    Outcome.Failure(AppError.Unauthorized)
} catch (e: io.ktor.client.plugins.ResponseException) {
    val body: String? = try { runBlocking { e.response.bodyAsText() } } catch (_: Exception) { null }
    Outcome.Failure(AppError.Http(e.response.status.value, body?.take(200)))
} catch (e: java.io.IOException) {
    val needsTrust = e.findCause<NeedsTrustException>()
    if (needsTrust != null) Outcome.Failure(AppError.CertUntrusted(needsTrust.hostKey, needsTrust.fingerprint))
    else Outcome.Failure(AppError.Network(e))
} catch (e: Throwable) {
    val needsTrust = e.findCause<NeedsTrustException>()
    if (needsTrust != null) Outcome.Failure(AppError.CertUntrusted(needsTrust.hostKey, needsTrust.fingerprint))
    else Outcome.Failure(AppError.Unknown(e))
}
