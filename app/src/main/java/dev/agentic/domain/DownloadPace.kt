package dev.agentic.domain

/** One sampled reading of download pace: smoothed speed (null until measurable) + stall flag. */
data class Pace(val bytesPerSec: Long?, val stalled: Boolean)

/** What the UI needs to render one in-flight download: completed fraction (null while the size is
 *  unknown), current transfer speed, and whether the transfer has visibly stalled. */
data class DownloadUi(
    val fraction: Float? = null,
    val bytesPerSec: Long? = null,
    val stalled: Boolean = false,
)

/**
 * Tracks a download's pacing from (cumulativeBytes, timestamp) progress events: sliding-window
 * average speed plus "no bytes for a while" stall detection. The caller supplies all timestamps,
 * so it unit-tests without clocks or coroutines.
 *
 * Methods are @Synchronized because production drives it from two threads at once: onProgress
 * fires on the downloader's IO thread while the VM's ticker samples on Main — an unsynchronized
 * ArrayDeque would race (corruption / ConcurrentModificationException). Calls are a few per
 * second, so the lock is uncontended noise.
 */
class DownloadPace(
    private val stallAfterMs: Long = 3_000,
    private val windowMs: Long = 4_000,
) {
    private val points = ArrayDeque<Pair<Long, Long>>() // (atMs, cumulativeBytes)
    private var startAt = 0L
    private var lastAdvanceAt = -1L
    private var lastBytes = -1L

    /** Marks the download as started at [nowMs]; a hang before the first byte counts as a stall. */
    @Synchronized
    fun start(nowMs: Long) {
        startAt = nowMs
        lastAdvanceAt = -1L
        lastBytes = -1L
        points.clear()
    }

    /** Records that [totalBytes] have arrived by [nowMs]. */
    @Synchronized
    fun onProgress(totalBytes: Long, nowMs: Long) {
        if (totalBytes != lastBytes) {
            lastAdvanceAt = nowMs
            lastBytes = totalBytes
        }
        points.addLast(nowMs to totalBytes)
        while (points.size > 1 && points.first().first < nowMs - windowMs) points.removeFirst()
    }

    /** Current pace as of [nowMs]. Stalled → speed reads 0 (the bar is frozen, say so). */
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
