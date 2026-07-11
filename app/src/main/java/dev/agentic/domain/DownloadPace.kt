package dev.agentic.domain

/** One sampled reading: smoothed speed (null until measurable) + stall flag. */
data class Pace(val bytesPerSec: Long?, val stalled: Boolean)

/** UI render state for an in-flight download: fraction (null while size unknown) + speed + stalled. */
data class DownloadUi(
    val fraction: Float? = null,
    val bytesPerSec: Long? = null,
    val stalled: Boolean = false,
)

/** Sliding-window speed + stall detection from (cumulativeBytes, timestamp) events. Caller supplies timestamps → unit-tests without clocks. @Synchronized: IO thread onProgress races VM-Main ticker sampler; calls are few/sec (uncontended noise). */
class DownloadPace(
    private val stallAfterMs: Long = 3_000,
    private val windowMs: Long = 4_000,
) {
    private val points = ArrayDeque<Pair<Long, Long>>() // (atMs, cumulativeBytes)
    private var startAt = 0L
    private var lastAdvanceAt = -1L
    private var lastBytes = -1L

    /** Started at [nowMs]; hang before first byte counts as a stall. */
    @Synchronized
    fun start(nowMs: Long) {
        startAt = nowMs
        lastAdvanceAt = -1L
        lastBytes = -1L
        points.clear()
    }

    /** Record [totalBytes] arrived by [nowMs]. */
    @Synchronized
    fun onProgress(totalBytes: Long, nowMs: Long) {
        if (totalBytes != lastBytes) {
            lastAdvanceAt = nowMs
            lastBytes = totalBytes
        }
        points.addLast(nowMs to totalBytes)
        while (points.size > 1 && points.first().first < nowMs - windowMs) points.removeFirst()
    }

    /** Pace as of [nowMs]; stalled → speed reads 0 (bar frozen, say so). */
    @Synchronized
    fun sample(nowMs: Long): Pace {
        val quietSince = if (lastAdvanceAt >= 0) lastAdvanceAt else startAt
        if (nowMs - quietSince > stallAfterMs) return Pace(0L, true)
        val first = points.firstOrNull()
        val last = points.lastOrNull()
        val speed = if (first != null && last != null && last.first > first.first) {
            ((last.second - first.second) * 1000.0 / (last.first - first.first)).toLong()
        } else null
        return Pace(speed, false)
    }
}

/** "3.2 MB/s" / "870 KB/s" / "512 B/s" — 1024-based, one decimal only at MB scale. */
fun formatBytesPerSec(bps: Long): String = when {
    bps >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB/s", bps / (1024.0 * 1024.0))
    bps >= 1024 -> "${bps / 1024} KB/s"
    else -> "$bps B/s"
}
