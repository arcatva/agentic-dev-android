package dev.agentic.data

import android.content.Context
import dev.agentic.data.log.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Per-session UI state for the wide-layout workflow rail. Defaults match a fresh session: the rail is
 *  shown, 280dp wide, and every workflow run starts collapsed ([expandedRuns] empty). Ported from the
 *  pre-MVVM layout (the old 3-pane). */
@Serializable
data class SessionUi(
    val railHidden: Boolean = false,
    val railWidth: Float = 280f,
    val expandedRuns: Set<String> = emptySet(),
)

/** Remembers the workflow-rail UI state per session id so switching away and back — or killing and
 *  reopening the app — restores each session's own state. Backed by the "agentic" SharedPreferences
 *  file under "ui:$id" keys (one small JSON blob per session). */
class SessionUiStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("agentic", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** This session's saved state, or defaults if none/corrupt. */
    fun get(id: String): SessionUi =
        p.getString("ui:$id", null)?.let {
            try { json.decodeFromString<SessionUi>(it) } catch (e: Exception) { AppLog.w("Store", "Failed to decode SessionUi: ${e.message}", e); null }
        } ?: SessionUi()

    fun put(id: String, ui: SessionUi) {
        p.edit().putString("ui:$id", json.encodeToString(ui)).apply()
    }

    /** Drop a session's state — call when the session is deleted so storage doesn't grow forever. */
    fun remove(id: String) {
        p.edit().remove("ui:$id").apply()
    }
}
