package dev.agentic.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {

    // Fixed "now" so the duration math is deterministic.
    private val now = 1_700_000_000_000L

    /** relativeAge for a timestamp [ms] before `now`. */
    private fun ago(ms: Long) = relativeAge(now - ms, now)

    @Test fun under_a_minute_is_just_now() = assertEquals("just now", ago(30_000L))

    @Test fun exactly_one_minute_is_singular() = assertEquals("1 minute ago", ago(60_000L))

    @Test fun minutes_are_plural() = assertEquals("34 minutes ago", ago(34 * 60_000L))

    @Test fun one_hour_is_singular() = assertEquals("1 hour ago", ago(60 * 60_000L))

    // The key fix: never compound — 2h40m collapses to the largest single unit.
    @Test fun hours_drop_the_remainder() =
        assertEquals("2 hours ago", ago(2 * 3_600_000L + 40 * 60_000L))

    @Test fun days_are_plural() = assertEquals("3 days ago", ago(3 * 86_400_000L))

    @Test fun seven_days_rolls_to_one_week() = assertEquals("1 week ago", ago(7 * 86_400_000L))

    @Test fun weeks_are_plural() = assertEquals("3 weeks ago", ago(21 * 86_400_000L))

    @Test fun a_month() = assertEquals("1 month ago", ago(40L * 86_400_000L))

    @Test fun a_year() = assertEquals("1 year ago", ago(400L * 86_400_000L))

    // A clock-skewed future timestamp clamps to "just now" rather than a negative age.
    @Test fun future_clamps_to_just_now() = assertEquals("just now", relativeAge(now + 5_000L, now))
}
