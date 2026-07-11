package dev.agentic.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.agentic.data.net.Session
import dev.agentic.ui.FadingText

/** Composer @-mention: `@` opens a candidates panel; pick inserts `@session:<8-char-id>` which the server expands. */

/** Id chars shown/inserted — matches `sessionId.take(8)` on the session page (unique in practice). */
const val MENTION_ID_CHARS = 8

/** Active @-mention: [start] = index of `@`, [query] = chars between `@` and the caret. */
data class MentionQuery(val start: Int, val query: String)

/** Active mention at [caret]: scans back from caret, stops at `@` (only when `@` starts a word so `name@host` never triggers). */
fun activeMentionQuery(text: String, caret: Int): MentionQuery? {
    if (caret < 1 || caret > text.length) return null
    var i = caret - 1
    while (i >= 0) {
        val c = text[i]
        if (c == '@') {
            if (i > 0 && !text[i - 1].isWhitespace()) return null
            return MentionQuery(i, text.substring(i + 1, caret))
        }
        if (c.isWhitespace()) return null
        i--
    }
    return null
}

/** Candidates matching [query]: id prefix OR name substring, case-insensitive. Empty query keeps the full list. */
fun filterMentionCandidates(sessions: List<Session>, query: String): List<Session> {
    if (query.isEmpty()) return sessions
    val q = query.lowercase()
    return sessions.filter { s ->
        s.id.lowercase().startsWith(q) || s.prompt.lowercase().contains(q)
    }
}

/** Literal token inserted; trailing space deactivates the mention query. */
fun mentionToken(id: String): String = "@session:${id.take(MENTION_ID_CHARS)} "

/** Mention dropdown panel: name + short id per row, [FadingText] to fade right-edge overflow. */
@Composable
internal fun MentionCandidatesPanel(
    candidates: List<Session>,
    onPick: (Session) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        // Bounded height + lazy so a long candidate list scrolls instead of growing past the composer.
        LazyColumn(Modifier.heightIn(max = 240.dp).padding(vertical = 6.dp)) {
            items(candidates, key = { it.id }) { s ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(s) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    FadingText(
                        s.prompt.ifBlank { "(no prompt)" },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    FadingText(
                        s.id.take(MENTION_ID_CHARS),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
