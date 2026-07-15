package dev.agentic.ui.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * Catches the ChatGPT OAuth redirect. The `redirect_uri` registered for the Codex client is fixed
 * to `http://localhost:1455/auth/callback`, and the browser that performs the login runs ON this
 * device — so the app itself listens on `127.0.0.1:1455` for one request, exactly like the Codex
 * CLI does on desktop. No new dependency (raw [ServerSocket]).
 */
object ChatGptOAuthLoopback {
    const val PORT = 1455
    private const val ACCEPT_TIMEOUT_MS = 5 * 60 * 1000

    /**
     * Parse `code` and `state` out of an HTTP request line such as
     * `GET /auth/callback?code=abc&state=xyz HTTP/1.1`. Returns null when either is absent.
     * Pure + JVM-testable (no socket).
     */
    fun parseCallback(requestLine: String): Pair<String, String>? {
        val target = requestLine.split(' ').getOrNull(1) ?: return null
        val query = target.substringAfter('?', "")
        if (query.isEmpty()) return null
        val params = query.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else urlDecode(pair.substring(0, i)) to urlDecode(pair.substring(i + 1))
        }.toMap()
        val code = params["code"]?.takeIf { it.isNotEmpty() } ?: return null
        val state = params["state"]?.takeIf { it.isNotEmpty() } ?: return null
        return code to state
    }

    private fun urlDecode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /**
     * Bind `127.0.0.1:1455`, wait (up to 5 min) for the OAuth redirect, and return the `code`
     * after verifying `state` matches. Distinct failures (port busy, timeout, mismatch) come back
     * as a [Result.failure] with a user-facing message.
     */
    suspend fun awaitCode(expectedState: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT))
                server.soTimeout = ACCEPT_TIMEOUT_MS
                server.accept().use { sock ->
                    val line = sock.getInputStream().bufferedReader().readLine()
                        ?: return@withContext Result.failure(IllegalStateException("empty callback request"))
                    val body = "You can close this tab and return to the app."
                    val resp = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\n" +
                        "Connection: close\r\n\r\n" + body
                    sock.getOutputStream().apply { write(resp.toByteArray()); flush() }
                    val parsed = parseCallback(line)
                        ?: return@withContext Result.failure(IllegalStateException("callback missing code/state"))
                    val (code, state) = parsed
                    if (state != expectedState) {
                        return@withContext Result.failure(IllegalStateException("login state mismatch"))
                    }
                    Result.success(code)
                }
            }
        } catch (e: java.net.BindException) {
            Result.failure(IllegalStateException("port $PORT is in use — close the other app and retry", e))
        } catch (e: SocketTimeoutException) {
            Result.failure(IllegalStateException("login timed out — please retry", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
