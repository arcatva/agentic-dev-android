package dev.agentic.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// No baked-in default host: first-run path is LAN scan; manual Host field shows placeholder and Log-in stays disabled until typed.
private const val DEFAULT_HOST = ""

/** Holds persistent user settings: auth token and backend host. */
interface SettingsStore {
    val token: StateFlow<String?>
    val host: String
    fun setToken(t: String?)
    fun setHost(h: String)
    /** Per-session composer draft — persists across process death so an un-sent message survives. */
    fun draft(id: String): String?
    fun setDraft(id: String, text: String?)
    /** Pinned server-cert fingerprint (colon-free lowercase SHA-256) for [hostKey] under TOFU. null = not pinned. */
    fun pinnedCert(hostKey: String): String?
    fun setPinnedCert(hostKey: String, fingerprint: String?)
}

/** SharedPreferences-backed settings. StateFlow on token so consumers react to changes without polling. */
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
