package dev.agentic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.agentic.ui.FadingText

/**
 * Slash-command palette shared by every composer (session input AND new-request input).
 *
 * A command is only valid at the START of a message (the server expands `/cmd` only when it leads),
 * so [activeCommandQuery] anchors detection to the message start. UI-layer type [CommandItem] keeps
 * this component free of a network dependency; callers map their DTO onto it.
 */
data class CommandItem(
    val name: String,
    val description: String = "",
    val argumentHint: String = "",
)

/** Active command being typed: [start] = index of the leading `/`, [query] = chars between `/` and caret. */
data class CommandQuery(val start: Int, val query: String)

/**
 * A command query is active when the message STARTS with `/` (leading whitespace allowed) and the
 * caret is still inside the command token (no space typed yet). Returns null otherwise — so ordinary
 * prose, mid-text slashes, and paths never trigger the palette.
 */
fun activeCommandQuery(text: String, caret: Int): CommandQuery? {
    if (caret < 0 || caret > text.length) return null
    val lead = text.indexOfFirst { !it.isWhitespace() }
    if (lead == -1 || text[lead] != '/') return null // message doesn't start with a command
    val tokenStart = lead + 1
    // token ends at the first whitespace at/after it (or end of string)
    var tokenEnd = tokenStart
    while (tokenEnd < text.length && !text[tokenEnd].isWhitespace()) tokenEnd++
    if (caret < tokenStart || caret > tokenEnd) return null // caret must sit within the command token
    return CommandQuery(start = lead, query = text.substring(tokenStart, caret))
}

/**
 * Does [text] already begin with a slash command (`/lfg`, `/compound-engineering:lfg`, …)? Mirrors
 * the server's `is_slash_command`. Used by the `/lfg` toggle to avoid double-prefixing a prompt the
 * user already turned into a command.
 */
fun isSlashCommand(text: String): Boolean {
    val rest = text.trimStart().removePrefix("/")
    if (rest.length == text.trimStart().length) return false // no leading slash
    val name = rest.takeWhile { !it.isWhitespace() }
    return name.isNotEmpty() &&
        name.first().isLowerCase() &&
        name.all { it.isLowerCase() || it.isDigit() || it == '-' || it == '_' || it == ':' }
}

/** Candidates whose name starts with [query] (case-insensitive); empty query keeps the full list. */
fun filterCommands(commands: List<CommandItem>, query: String): List<CommandItem> {
    if (query.isEmpty()) return commands
    val q = query.lowercase()
    return commands.filter { it.name.lowercase().startsWith(q) }
}

/**
 * Apply [name] onto [text]: replace the typed `/<query>` token with `/<name> ` and return the new
 * text plus the caret position (just past the inserted trailing space). Any text after the token
 * (rare while typing a command) is preserved.
 */
fun applyCommand(text: String, q: CommandQuery, name: String): Pair<String, Int> {
    var tokenEnd = q.start + 1
    while (tokenEnd < text.length && !text[tokenEnd].isWhitespace()) tokenEnd++
    val inserted = "/$name "
    val before = text.substring(0, q.start)
    val after = text.substring(tokenEnd).trimStart()
    val newText = before + inserted + after
    val caret = before.length + inserted.length
    return newText to caret
}

/** Dropdown: `/name  <argHint>` on top, description below. [FadingText] fades right-edge overflow. */
@Composable
fun CommandPalette(
    candidates: List<CommandItem>,
    onPick: (CommandItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (candidates.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        LazyColumn(Modifier.heightIn(max = 280.dp).padding(vertical = 6.dp)) {
            items(candidates, key = { it.name }) { c ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(c) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "/${c.name}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (c.argumentHint.isNotBlank()) {
                            Text(
                                "  ${c.argumentHint}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (c.description.isNotBlank()) {
                        FadingText(
                            c.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
