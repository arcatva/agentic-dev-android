package dev.agentic.domain

/**
 * A readable, single-unit relative age for an epoch-ms timestamp — e.g. "just now",
 * "34 minutes ago", "2 hours ago", "8 days ago", "3 weeks ago", "5 months ago", "2 years ago".
 *
 * Deliberately avoids the terse compact ("34m") and compound ("8d7h") styles. Pure + unit-testable.
 * [nowMs] is injectable so a caller's periodic tick can refresh the label and tests can pin "now".
 */
fun relativeAge(ts: Long, nowMs: Long = System.currentTimeMillis()): String {
    val s = ((nowMs - ts) / 1000).coerceAtLeast(0)
    fun ago(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
    return when {
        s < 60 -> "just now"
        s < 3_600 -> ago(s / 60, "minute")
        s < 86_400 -> ago(s / 3_600, "hour")
        s < 604_800 -> ago(s / 86_400, "day")
        s < 2_629_800 -> ago(s / 604_800, "week")     // ~30.4-day month
        s < 31_557_600 -> ago(s / 2_629_800, "month") // ~365.25-day year
        else -> ago(s / 31_557_600, "year")
    }
}
