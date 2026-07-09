package dev.agentic.domain

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Parse a usage window's `resets_at` into epoch-millis, or null if absent/unparsable.
 *
 * Anthropic's OAuth usage endpoint returns an ISO-8601 instant; we don't have a confirmed sample
 * (server tests use placeholders), so also accept a numeric epoch — treated as millis when large,
 * else seconds — and fall back to null for anything else.
 */
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

/**
 * Compact "time until this usage window resets" label for [resetsAt], measured from [nowMs]:
 * `3d21h` (≥1 day), `3h29m` (≥1 hour), `29m` (≥1 minute), `<1m` (under a minute or already past),
 * or `—` when there's no parsable reset time.
 */
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
