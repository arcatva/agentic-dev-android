package dev.agentic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class UsageTest {

    // Fixed "now" so the duration math is deterministic.
    private val now = Instant.parse("2026-06-20T00:00:00Z").toEpochMilli()

    private fun isoAfter(ms: Long) = Instant.ofEpochMilli(now + ms).toString()

    // ── resetIn: duration buckets ─────────────────────────────────────────────

    @Test fun days_and_hours() =
        assertEquals("3d21h", resetIn(isoAfter((3 * 24 + 21) * 3_600_000L), now))

    @Test fun hours_and_minutes() =
        assertEquals("3h29m", resetIn(isoAfter(3 * 3_600_000L + 29 * 60_000L), now))

    @Test fun minutes_only() =
        assertEquals("29m", resetIn(isoAfter(29 * 60_000L), now))

    @Test fun under_a_minute_is_lt1m() =
        assertEquals("<1m", resetIn(isoAfter(30_000L), now))

    @Test fun already_past_is_lt1m() =
        assertEquals("<1m", resetIn(isoAfter(-5_000L), now))

    @Test fun exact_day_drops_zero_hours() =
        assertEquals("2d0h", resetIn(isoAfter(2 * 24 * 3_600_000L), now))

    // ── missing / unparsable ──────────────────────────────────────────────────

    @Test fun null_is_dash() = assertEquals("—", resetIn(null, now))
    @Test fun blank_is_dash() = assertEquals("—", resetIn("   ", now))
    @Test fun garbage_is_dash() = assertEquals("—", resetIn("not-a-date", now))

    // ── parseResetAt: format tolerance ────────────────────────────────────────

    @Test fun parses_iso_instant() =
        assertEquals(now, parseResetAt("2026-06-20T00:00:00Z"))

    @Test fun parses_epoch_seconds() =
        assertEquals(now, parseResetAt((now / 1000).toString()))

    @Test fun parses_epoch_millis() =
        assertEquals(now, parseResetAt(now.toString()))

    @Test fun null_parses_to_null() = assertNull(parseResetAt(null))
}
