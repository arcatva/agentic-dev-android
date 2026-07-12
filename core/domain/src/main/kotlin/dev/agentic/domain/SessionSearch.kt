package dev.agentic.domain

import dev.agentic.data.net.Session

/** Case-insensitive substring filter on [Session.prompt]; blank query returns input unchanged; blank prompt never matches a non-empty query. */
fun List<Session>.filterByTitle(query: String): List<Session> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.prompt.contains(q, ignoreCase = true) }
}
