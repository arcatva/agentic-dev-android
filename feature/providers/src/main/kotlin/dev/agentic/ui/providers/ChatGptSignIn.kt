package dev.agentic.ui.providers

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.data.log.AppLog
import dev.agentic.data.net.ChatGptStatus
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.runCatchingOutcome
import dev.agentic.data.repo.ProvidersRepository
import dev.agentic.di.appContainer
import dev.agentic.ui.ModelCatalog
import dev.agentic.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ChatGPT-subscription sign-in via OAuth (Authorization Code + PKCE). The interactive browser flow
 * runs on-device; the server owns PKCE + token exchange + refresh. The fixed OAuth redirect is a
 * loopback URL (`http://localhost:1455/auth/callback`), so the app briefly binds that port to catch
 * the redirect (RFC 8252 native-app pattern), reads the authorization code, and posts it to the
 * backend to finish. Tokens never touch the device.
 */
object ChatGptOAuth {
    /** The fixed loopback redirect port registered for the OAuth client. */
    private const val LOOPBACK_PORT = 1455

    /** Run the whole flow: start → open browser → capture the loopback redirect → complete. */
    suspend fun signIn(context: Context, repo: ProvidersRepository): Unit = withContext(Dispatchers.IO) {
        val start = repo.chatgptLoginStart()
        require(start.authorizeUrl.isNotBlank()) { "server did not return an authorize URL" }
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), LOOPBACK_PORT))
            // Cap the wait so a never-completed login can't leak the port/coroutine forever.
            server.soTimeout = 5 * 60 * 1000
            // Listening now — open the consent page.
            openBrowser(context, start.authorizeUrl)
            val code = awaitCode(server, start.state)
            repo.chatgptLoginComplete(code, start.state)
        }
    }

    private fun openBrowser(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Accept loopback connections until the OAuth redirect arrives; returns the authorization code. */
    private fun awaitCode(server: ServerSocket, expectedState: String): String {
        while (true) {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                // Request line: "GET /auth/callback?code=...&state=... HTTP/1.1"
                val requestLine = reader.readLine() ?: return@use
                val path = requestLine.split(" ").getOrNull(1).orEmpty()
                val params = parseQuery(path.substringAfter('?', ""))
                val isCallback = params.containsKey("code") || params.containsKey("error")
                respond(socket, isCallback)
                if (!isCallback) return@use // ignore favicon / probe requests, keep listening

                params["error"]?.let { throw IllegalStateException("ChatGPT sign-in was denied ($it)") }
                if (params["state"] != expectedState) {
                    throw IllegalStateException("state mismatch — sign-in aborted for safety")
                }
                return params["code"] ?: throw IllegalStateException("no authorization code in redirect")
            }
        }
    }

    private fun respond(socket: java.net.Socket, isCallback: Boolean) {
        val body = if (isCallback) {
            "<html><body style=\"font-family:sans-serif;padding:2rem\">Signed in. You can close this tab and return to the app.</body></html>"
        } else {
            ""
        }
        val status = if (isCallback) "200 OK" else "404 Not Found"
        val bytes = body.toByteArray()
        val out = socket.getOutputStream().bufferedWriter()
        out.write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$body")
        out.flush()
    }

    private fun parseQuery(q: String): Map<String, String> =
        if (q.isEmpty()) emptyMap()
        else q.split("&").mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i < 0) null
            else URLDecoder.decode(pair.substring(0, i), "UTF-8") to URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }.toMap()
}

data class ChatGptUiState(
    val status: ChatGptStatus = ChatGptStatus(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Status + sign-in/disconnect for the ChatGPT subscription provider. */
class ChatGptViewModel(private val repo: ProvidersRepository) : ViewModel() {
    private val _ui = MutableStateFlow(ChatGptUiState())
    val ui = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.chatgptStatus() }) {
                is Outcome.Success -> _ui.update { it.copy(status = r.value, loading = false, error = null) }
                is Outcome.Failure -> {
                    AppLog.w("VM", "chatgpt status failed err=${r.error}")
                    _ui.update { it.copy(loading = false, error = r.error.toString()) }
                }
            }
        }
    }

    /** Run the interactive login. [onChanged] lets the caller refresh the model/provider list. */
    fun signIn(context: Context, onChanged: () -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = runCatchingOutcome { ChatGptOAuth.signIn(context, repo) }) {
                is Outcome.Success -> {
                    AppLog.d("VM", "chatgpt sign-in ok")
                    _ui.update { it.copy(busy = false) }
                    ModelCatalog.invalidate()
                    refresh()
                    onChanged()
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "chatgpt sign-in failed err=${r.error}")
                    _ui.update { it.copy(busy = false, error = r.error.toString()) }
                }
            }
        }
    }

    fun disconnect(onChanged: () -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = runCatchingOutcome { repo.chatgptDisconnect() }) {
                is Outcome.Success -> {
                    _ui.update { it.copy(busy = false) }
                    ModelCatalog.invalidate()
                    refresh()
                    onChanged()
                }
                is Outcome.Failure -> _ui.update { it.copy(busy = false, error = r.error.toString()) }
            }
        }
    }
}

private fun formatExpiry(epochSeconds: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))

/**
 * "ChatGPT subscription" card: sign in with a ChatGPT plan (OAuth) to add GPT to the model list.
 * Shows the connected account + token expiry, and a re-login prompt when the refresh token dies.
 * [onChanged] is invoked after a successful sign-in/disconnect so the caller can refresh its list.
 */
@Composable
fun ChatGptSection(onChanged: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val vm: ChatGptViewModel = viewModel(
        factory = viewModelFactory { initializer { ChatGptViewModel(container.providersRepo) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()

    SectionCard("ChatGPT subscription") {
        val s = ui.status
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        when {
            ui.loading -> Text("Loading…", color = muted, style = MaterialTheme.typography.bodyMedium)
            s.connected -> {
                Text(
                    if (s.email.isNotBlank()) "Signed in as ${s.email}" else "Signed in",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (s.expiresAt > 0) {
                    Text(
                        "Token expires ${formatExpiry(s.expiresAt)} (auto-refreshed)",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.signIn(context, onChanged) }, enabled = !ui.busy) {
                        Text("Re-authenticate")
                    }
                    TextButton(onClick = { vm.disconnect(onChanged) }, enabled = !ui.busy) {
                        Text("Disconnect", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            s.needsRelogin -> {
                Text(
                    "ChatGPT session expired — sign in again to keep GPT available.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { vm.signIn(context, onChanged) }, enabled = !ui.busy) {
                    Text("Sign in with ChatGPT")
                }
            }
            else -> {
                Text(
                    "Attach your ChatGPT plan (OAuth) to add GPT to the delegate model pool — no API key needed.",
                    color = muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { vm.signIn(context, onChanged) }, enabled = !ui.busy) {
                    Text("Sign in with ChatGPT")
                }
            }
        }
        if (ui.busy) {
            Text(
                "Waiting for browser sign-in…",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
        ui.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
    }
}
