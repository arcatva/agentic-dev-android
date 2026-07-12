package dev.agentic.ui.providers

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * Loopback capture of the ChatGPT OAuth redirect.
 *
 * The OAuth client (`app_EMoamEEZ73f0CkXaXp7hrann`) is registered with the fixed redirect
 * `http://localhost:1455/auth/callback` — the same one Codex CLI uses. After the user approves in
 * the browser, OpenAI redirects there, which on the device is this app's own loopback. We bind a
 * one-shot `ServerSocket` on 1455, read the `code`/`state` off the redirect request line, reply with
 * a tiny "you can close this" page, and hand the code back for the token exchange.
 *
 * Blocking (java.net) — call it on a background dispatcher. Returns `null` on timeout, a port bind
 * failure, or an OAuth error redirect.
 */
fun awaitChatGptOAuthCode(
    expectedState: String,
    port: Int = 1455,
    timeoutMs: Long = 180_000,
    shouldContinue: () -> Boolean = { true },
): String? {
    var server: ServerSocket? = null
    try {
        server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        // Short accept() timeout so we periodically re-check the deadline AND coroutine cancellation
        // (java.net accept() ignores Thread.interrupt), instead of parking on the port for minutes.
        server.soTimeout = 2_000
        val deadline = System.currentTimeMillis() + timeoutMs
        while (shouldContinue() && System.currentTimeMillis() < deadline) {
            val sock = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                return null
            }
            val code = try {
                // Bound the read too: a client that connects and sends nothing must not wedge us.
                sock.soTimeout = 5_000
                val requestLine = BufferedReader(InputStreamReader(sock.getInputStream())).readLine()
                val params = parseCallbackParams(requestLine)
                val c = params["code"]
                val stateOk = expectedState.isEmpty() || params["state"] == expectedState
                val ok = c != null && stateOk
                writeHttpResponse(sock, ok)
                when {
                    ok -> c
                    // Explicit error redirect (user denied) ends the flow.
                    params.containsKey("error") -> return null
                    // Stray request (favicon, partial read, state mismatch) → keep listening.
                    else -> null
                }
            } catch (_: Exception) {
                null // read timeout / malformed request → keep listening until the deadline
            } finally {
                try {
                    sock.close()
                } catch (_: Exception) {
                }
            }
            if (code != null) return code
        }
        return null
    } catch (_: Exception) {
        return null
    } finally {
        try {
            server?.close()
        } catch (_: Exception) {
        }
    }
}

/** Parse `GET /auth/callback?code=..&state=.. HTTP/1.1` into a decoded query-param map. */
private fun parseCallbackParams(requestLine: String?): Map<String, String> {
    val line = requestLine ?: return emptyMap()
    val path = line.split(' ').getOrNull(1) ?: return emptyMap()
    val query = path.substringAfter('?', "")
    if (query.isEmpty()) return emptyMap()
    return query.split('&').mapNotNull { pair ->
        val i = pair.indexOf('=')
        if (i <= 0) return@mapNotNull null
        val k = urlDecode(pair.substring(0, i))
        val v = urlDecode(pair.substring(i + 1))
        k to v
    }.toMap()
}

private fun urlDecode(s: String): String = try {
    URLDecoder.decode(s, "UTF-8")
} catch (_: Exception) {
    s
}

private fun writeHttpResponse(sock: java.net.Socket, ok: Boolean) {
    val body = if (ok) {
        "<html><body><h3>ChatGPT connected.</h3><p>You can close this tab and return to the app.</p></body></html>"
    } else {
        "<html><body><h3>Login failed or was cancelled.</h3><p>Return to the app and try again.</p></body></html>"
    }
    val bytes = body.toByteArray(Charsets.UTF_8)
    val header = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/html; charset=utf-8\r\n" +
        "Connection: close\r\n" +
        "Content-Length: ${bytes.size}\r\n\r\n"
    try {
        sock.getOutputStream().apply {
            write(header.toByteArray(Charsets.UTF_8))
            write(bytes)
            flush()
        }
    } catch (_: Exception) {
    }
}
