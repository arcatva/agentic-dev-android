package dev.agentic.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// No baked-in default host: first-run primary path is the LAN scan; the manual Host field shows
// a placeholder example and the Log-in button stays disabled until a host is typed.
private const val DEFAULT_HOST = ""

/** Holds persistent user settings: auth token and backend host. */
interface SettingsStore {
    /** Current auth token, exposed as a cold-read StateFlow so observers are notified on change. */
    val token: StateFlow<String?>
    /** Current backend host URL. */
    val host: String
    fun setToken(t: String?)
    fun setHost(h: String)
    /** Per-session composer draft, persisted so an un-sent message survives process death
     *  (the OS killing a backgrounded app). null/empty means no draft. */
    fun draft(id: String): String?
    fun setDraft(id: String, text: String?)
    /** The pinned server-cert fingerprint (colon-free lowercase SHA-256) for [hostKey], if the user
     *  has trusted a self-signed cert for that host (trust-on-first-use). null = not pinned. */
    fun pinnedCert(hostKey: String): String?
    fun setPinnedCert(hostKey: String, fingerprint: String?)
}

/** SharedPreferences-backed implementation. Persists token and host across app restarts.
 *  Ported from [dev.agentic.data.Store]; adds a [MutableStateFlow] so consumers can
 *  react to token changes without polling. */
class SharedPrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("agentic", Context.MODE_PRIVATE)

    private val _token = MutableStateFlow<String?>(prefs.getString("token", null))
    override val token: StateFlow<String?> = _token.asStateFlow()

    override val host: String
        get() = prefs.getString("host", DEFAULT_HOST) ?: DEFAULT_HOST

    override fun setToken(t: String?) {
        prefs.edit().putString("token", t).apply()
        _token.value = t
    }

    override fun setHost(h: String) {
        prefs.edit().putString("host", h).apply()
    }

    override fun draft(id: String): String? = prefs.getString("draft:$id", null)

    override fun setDraft(id: String, text: String?) {
        // apply() writes async off the main thread, so a per-keystroke call is cheap.
        prefs.edit().apply {
            if (text.isNullOrEmpty()) remove("draft:$id") else putString("draft:$id", text)
        }.apply()
    }

    override fun pinnedCert(hostKey: String): String? = prefs.getString("pin:$hostKey", null)

    override fun setPinnedCert(hostKey: String, fingerprint: String?) {
        prefs.edit().apply {
            if (fingerprint.isNullOrEmpty()) remove("pin:$hostKey") else putString("pin:$hostKey", fingerprint)
        }.apply()
    }
}
