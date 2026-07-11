package dev.agentic.data

import android.content.Context
import dev.agentic.data.log.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Per-session workflow-rail UI state. Backed by SharedPreferences under "ui:$id" keys (one small JSON blob per session). */
@Serializable
data class SessionUi(
    val railHidden: Boolean = false,
    val railWidth: Float = 280f,
    val expandedRuns: Set<String> = emptySet(),
)

/** Remembers workflow-rail state per session so kill/reopen restores each session's own layout. */
class SessionUiStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("agentic", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun get(id: String): SessionUi =
        p.getString("ui:$id", null)?.let {
            try { json.decodeFromString<SessionUi>(it) } catch (e: Exception) { AppLog.w("Store", "Failed to decode SessionUi: ${e.message}", e); null }
        } ?: SessionUi()

    fun put(id: String, ui: SessionUi) {
        p.edit().putString("ui:$id", json.encodeToString(ui)).apply()
    }

    /** Drop a session's state — call on session delete so storage doesn't grow forever. */
    fun remove(id: String) {
        p.edit().remove("ui:$id").apply()
    }
}
