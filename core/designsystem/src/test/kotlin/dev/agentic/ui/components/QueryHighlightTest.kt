package dev.agentic.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [matchRanges] — the Compose-free matching core behind [highlightQuery]. The
 * AnnotatedString assembly in [highlightQuery] is a trivial walk over these ranges, so exercising
 * the ranges exercises the part that can actually be wrong (case-folding, literal-vs-regex, indices).
 */
class QueryHighlightTest {

    @Test fun `matches at the start, case-insensitively`() {
        assertEquals(listOf(0..4), matchRanges("Build failed here", "build"))
    }

    @Test fun `finds every non-overlapping occurrence`() {
        assertEquals(listOf(0..2, 3..5, 6..8), matchRanges("abcABCabc", "abc"))
    }

    @Test fun `leading and trailing whitespace in the query is trimmed`() {
        // "the login bug": 'l' is index 4, 'n' is index 8.
        assertEquals(listOf(4..8), matchRanges("the login bug", "  login "))
    }

    @Test fun `no occurrence yields no ranges`() {
        assertEquals(emptyList<IntRange>(), matchRanges("hello world", "zzz"))
    }

    @Test fun `blank or whitespace-only query yields no ranges`() {
        assertEquals(emptyList<IntRange>(), matchRanges("hello", ""))
        assertEquals(emptyList<IntRange>(), matchRanges("hello", "   "))
    }

    @Test fun `the query is matched literally, not as a regex`() {
        // A '.' in the query must match a real dot, not "any character".
        assertEquals(listOf(1..1, 3..3), matchRanges("a.b.c", "."))
        assertEquals(emptyList<IntRange>(), matchRanges("axbxc", "."))
    }

    @Test fun `regex metacharacters in the query are escaped`() {
        // "(a)" appears verbatim; without escaping, "(a)" would be a capture group matching "a".
        assertEquals(listOf(4..6), matchRanges("foo (a) bar", "(a)"))
    }

    @Test fun `match can sit at the very end of the text`() {
        assertEquals(listOf(6..9), matchRanges("error code", "code"))
    }

    // ── snippetAroundMatch: keep the matched term visible despite the row's 2-line clamp ──

    @Test fun `a match already near the front leaves the snippet unchanged`() {
        assertEquals("如果是内容", snippetAroundMatch("如果是内容", "如果"))
    }

    @Test fun `a match exactly at the lead boundary is left unchanged`() {
        // default lead = 10; "如果" starts at index 10.
        val s = "0123456789如果xy"
        assertEquals(s, snippetAroundMatch(s, "如果"))
    }

    @Test fun `a centred match is pulled to the front with a leading ellipsis`() {
        val s = "a".repeat(50) + "如果" + "bbbbb"
        // keep 10 chars of context before the match, prefix '…'.
        assertEquals("…" + "a".repeat(10) + "如果bbbbb", snippetAroundMatch(s, "如果"))
    }

    @Test fun `the re-windowed snippet still contains and highlights the query`() {
        val s = "x".repeat(80) + "login failure" + "y".repeat(80)
        val windowed = snippetAroundMatch(s, "login")
        // The match must now be findable near the start of what the row will render.
        val ranges = matchRanges(windowed, "login")
        assertEquals(1, ranges.size)
        assert(ranges[0].first <= 11) { "match should be within the first visible chars, was ${ranges[0].first}" }
    }

    @Test fun `an absent or blank query leaves the snippet unchanged`() {
        assertEquals("hello world", snippetAroundMatch("hello world", "zzz"))
        assertEquals("hello world", snippetAroundMatch("hello world", "   "))
    }
}
