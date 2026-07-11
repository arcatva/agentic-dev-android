package dev.agentic.domain

import java.time.Instant
import java.time.format.DateTimeParseException

/** Parse `resets_at` to epoch-millis (ISO-8601, or numeric: ≥1e12=ms else seconds); null if absent/unparsable. */
fun parseResetAt(resetsAt: String?): Long? {
    val s = resetsAt?.trim()
    if (s.isNullOrEmpty()) return null
    return try {
        Instant.parse(s).toEpochMilli()
    } catch (e: DateTimeParseException) {
        val n = s.toLongOrNull() ?: return null
        // A value past ~Nov 2001 in *seconds* is still 9-10 digits; in millis it's 12-13. Treat
        // anything ≥ 1e12 as already-millis, otherwise seconds.
        if (n >= 1_000_000_000_000L) n else n * 1000L
    }
}

/** Compact "until reset" label from [nowMs]: `3d21h` / `3h29m` / `29m` / `<1m` / `—` (no reset). */
fun resetIn(resetsAt: String?, nowMs: Long = System.currentTimeMillis()): String {
    val at = parseResetAt(resetsAt) ?: return "—"
    val totalMin = ((at - nowMs).coerceAtLeast(0L)) / 60_000L
    val d = totalMin / 1440
    val h = (totalMin % 1440) / 60
    val m = totalMin % 60
    return when {
        d > 0 -> "${d}d${h}h"
        h > 0 -> "${h}h${m}m"
        m > 0 -> "${m}m"
        else -> "<1m"
    }
}
