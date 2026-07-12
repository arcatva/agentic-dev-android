package dev.agentic.domain


/**
 * Case-insensitive substring filter on [SessionSnapshot.prompt]; blank query returns input
 * unchanged; blank prompt never matches a non-empty query.
 */
fun <T : SessionSnapshot> List<T>.filterByTitle(query: String): List<T> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.prompt.contains(q, ignoreCase = true) }
}
