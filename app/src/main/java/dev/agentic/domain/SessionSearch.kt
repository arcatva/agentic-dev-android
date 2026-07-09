package dev.agentic.domain

import dev.agentic.data.net.Session

/**
 * Case-insensitive substring filter on the session title ([Session.prompt]).
 * A blank/whitespace query returns the list unchanged. Matches the raw prompt, so a
 * blank-prompt session is never matched by a non-empty query.
 */
fun List<Session>.filterByTitle(query: String): List<Session> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.prompt.contains(q, ignoreCase = true) }
}
