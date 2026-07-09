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

/**
 * Composer @-mention of another session. Typing `@` in the input box opens a candidates panel
 * (name + id per row); picking one inserts a literal `@session:<8-char-id>` token — the server
 * expands that token on the text delivered to claude with the mentioned session's identity and
 * on-disk paths, so this session can inspect the other one or act on it.
 *
 * The parsing/filtering below is pure (no Compose deps) and unit-tested in SessionMentionTest.
 */

/** Number of id chars shown in the dropdown and inserted into the token — matches the id the
 *  session page shows under its title (`sessionId.take(8)`; a uuid prefix, unique in practice). */
const val MENTION_ID_CHARS = 8

/** An @-mention being typed: [start] is the index of the `@` in the text, [query] the chars
 *  between the `@` and the caret. */
data class MentionQuery(val start: Int, val query: String)

/**
 * The active @-mention at [caret], or null. Active = the caret sits inside an `@`-word: scanning
 * back from the caret reaches an `@` before any whitespace, and that `@` starts a word (text
 * start or preceded by whitespace — so `name@host` never triggers). The caret must sit at or
 * after the `@`+1, i.e. deleting back past the `@` deactivates it.
 */
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

/** Candidates matching [query]: id prefix match or name (title) substring match, case-insensitive.
 *  An empty query keeps the full list (the panel opens on a bare `@`). */
fun filterMentionCandidates(sessions: List<Session>, query: String): List<Session> {
    if (query.isEmpty()) return sessions
    val q = query.lowercase()
    return sessions.filter { s ->
        s.id.lowercase().startsWith(q) || s.prompt.lowercase().contains(q)
    }
}

/** The literal token inserted for a picked session, with a trailing space so typing continues
 *  naturally (and the trailing space deactivates the mention query). */
fun mentionToken(id: String): String = "@session:${id.take(MENTION_ID_CHARS)} "

/**
 * The candidates dropdown rendered above the composer while an @-mention is active. Each row:
 * session name (title) + short id, styled like the session page's title/id pair; both lines are
 * [FadingText], so overflowing text dissolves at the right edge instead of hard-clipping.
 */
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
        // Bounded height + lazy: the sessions list can be long; the panel shows a scrollable
        // window instead of growing past the composer.
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
