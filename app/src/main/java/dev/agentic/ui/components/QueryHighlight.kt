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
 * Pure Kotlin — testable on plain JVM. Mirrors backend `engine::search`
 * (`text.to_lowercase().contains(query.to_lowercase())`): case-insensitive literal, NOT regex.
 *
 * Match against ORIGINAL [text] via case-insensitive regex on the escaped query: [Regex.escape]
 * makes `.`/`(`/`[` literal; matching the un-lowercased text keeps indices valid against [text]
 * (lowercasing can change length for some Unicode, e.g. 'İ' → "i̇", which desyncs index slices).
 *
 * Blank/whitespace query → no ranges. Ranges inclusive on both ends ([MatchResult.range]).
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
 * Re-windows a backend [snippet] so the first match of [query] sits near the front.
 *
 * Backend `extract_snippet` centres its ≤200-char window ON the match — right for a wide view but
 * for a row snippet clamped to a line or two, the match (and its highlight) is pushed off-screen.
 * Keep [lead] chars of context before the match and prefix '…' when we trimmed so the highlighted
 * term is always within the first visible line. Pure (no Compose) so windowing is testable.
 */
fun snippetAroundMatch(snippet: String, query: String, lead: Int = 10): String {
    val first = matchRanges(snippet, query).firstOrNull() ?: return snippet
    if (first.first <= lead) return snippet
    var cut = first.first - lead
    // Don't start the slice mid-surrogate-pair (would render a broken glyph).
    if (cut in 1 until snippet.length && snippet[cut].isLowSurrogate() && snippet[cut - 1].isHighSurrogate()) cut--
    return "…" + snippet.substring(cut)
}

/**
 * Builds an [AnnotatedString] from [text] with every [query] match painted in [color] and bumped to
 * [weight]. Search surfaces pass the theme primary so the match pops against the muted body.
 *
 * Base text is unstyled (caller's `Text(color = …)` supplies body color); only matched spans carry
 * [SpanStyle]. No match → original string verbatim, no spans.
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
