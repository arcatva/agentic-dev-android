package dev.agentic.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Case-insensitive, literal (non-overlapping) match ranges of [query] inside [text].
 *
 * Pure Kotlin — no Compose types — so it can be unit-tested on the plain JVM, and so the matching
 * rule (the part that can actually be wrong) is verifiable without rendering. Mirrors the backend's
 * matching in `engine::search` (`text.to_lowercase().contains(query.to_lowercase())`): the search is
 * case-insensitive and treats the query as a literal string, NOT a regex.
 *
 * We match against the ORIGINAL [text] via a case-insensitive regex over the *escaped* query. Two
 * reasons the escape + match-on-original matters:
 *  - [Regex.escape] makes characters like `.` `(` `[` literal, so a query of "a.b" matches the four
 *    characters "a.b" and not "axb".
 *  - Matching the un-lowercased text keeps the returned indices valid against [text]. Lowercasing a
 *    string can change its length for some Unicode (e.g. 'İ' → "i̇"), which would desync an
 *    index-based slice built from a lowercased copy.
 *
 * A blank/whitespace-only query yields no ranges (the search box never queries on `< 2` chars, but
 * we stay defensive). Ranges are inclusive on both ends, matching [MatchResult.range].
 */
fun matchRanges(text: String, query: String): List<IntRange> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return Regex(Regex.escape(needle), RegexOption.IGNORE_CASE)
        .findAll(text)
        .map { it.range }
        .toList()
}

/**
 * Re-windows a backend [snippet] so the first occurrence of [query] sits near the front.
 *
 * Why this exists: the backend's `extract_snippet` centres its (≤200-char) window ON the match, which
 * is right for a wide view but puts the matched term ~100 chars into the snippet. A search-result row
 * clamps the snippet to a line or two, so a centred match — and its highlight — is pushed off-screen
 * and the user sees only the grey lead-up text. Here we keep [lead] characters of context before the
 * match and prefix a single-character ellipsis ('…') when we trimmed, so the highlighted term is
 * always within the first visible line.
 *
 * Returns [snippet] unchanged when [query] is blank/absent or the match already sits within [lead]
 * characters of the start. Pure (no Compose) so the windowing is unit-testable.
 */
fun snippetAroundMatch(snippet: String, query: String, lead: Int = 10): String {
    val first = matchRanges(snippet, query).firstOrNull() ?: return snippet
    if (first.first <= lead) return snippet
    var cut = first.first - lead
    // Don't start the slice in the middle of a surrogate pair (would render a broken glyph).
    if (cut in 1 until snippet.length && snippet[cut].isLowSurrogate() && snippet[cut - 1].isHighSurrogate()) cut--
    return "…" + snippet.substring(cut)
}

/**
 * Builds an [AnnotatedString] from [text] with every occurrence of [query] (see [matchRanges])
 * painted in [color] and bumped to [weight]. Search result surfaces pass the theme's primary colour
 * so the matched substring inside a snippet stands out in the brand colour against the muted snippet
 * body.
 *
 * The base text is left unstyled (the caller's `Text(color = …)` supplies the body colour); only the
 * matched spans carry [SpanStyle]. When there is no match the original string is returned verbatim
 * with no spans, so highlighting never adds or drops a character.
 */
fun highlightQuery(
    text: String,
    query: String,
    color: Color,
    weight: FontWeight = FontWeight.SemiBold,
): AnnotatedString {
    val ranges = matchRanges(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    val style = SpanStyle(color = color, fontWeight = weight)
    return buildAnnotatedString {
        var cursor = 0
        for (r in ranges) {
            if (r.first > cursor) append(text.substring(cursor, r.first))
            withStyle(style) { append(text.substring(r.first, r.last + 1)) }
            cursor = r.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}
