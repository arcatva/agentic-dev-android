package dev.agentic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPaceTest {

    @Test
    fun `no samples yet reports null speed and no stall`() {
        val pace = DownloadPace()
        pace.start(0)
        val p = pace.sample(500)
        assertNull(p.bytesPerSec)
        assertFalse(p.stalled)
    }

    @Test
    fun `steady progress reports the transfer rate`() {
        val pace = DownloadPace()
        pace.start(0)
        pace.onProgress(0, 0)
        pace.onProgress(1_000_000, 1_000)
        pace.onProgress(2_000_000, 2_000)
        val p = pace.sample(2_000)
        val bps = p.bytesPerSec!!
        assertTrue("expected ~1MB/s, got $bps", bps in 800_000..1_200_000)
        assertFalse(p.stalled)
    }

    @Test
    fun `quiet gap beyond threshold is stalled with zero speed`() {
        val pace = DownloadPace(stallAfterMs = 3_000)
        pace.start(0)
        pace.onProgress(100_000, 500)
        assertFalse(pace.sample(3_400).stalled) // 2.9s quiet — not yet
        val p = pace.sample(3_600)              // 3.1s quiet — stalled
        assertTrue(p.stalled)
        assertEquals(0L, p.bytesPerSec)
    }

    @Test
    fun `hang before the first byte also counts as stalled`() {
        val pace = DownloadPace(stallAfterMs = 3_000)
        pace.start(1_000)
        assertFalse(pace.sample(3_900).stalled)
        assertTrue(pace.sample(4_100).stalled)
    }

    @Test
    fun `progress after a stall recovers`() {
        val pace = DownloadPace(stallAfterMs = 3_000)
        pace.start(0)
        pace.onProgress(100_000, 500)
        assertTrue(pace.sample(4_000).stalled)
        pace.onProgress(200_000, 4_500)
        assertFalse(pace.sample(4_600).stalled)
    }

    @Test
    fun `speed formatting picks a readable unit`() {
        assertEquals("3.2 MB/s", formatBytesPerSec((3.2 * 1024 * 1024).toLong()))
        assertEquals("870 KB/s", formatBytesPerSec(870L * 1024))
        assertEquals("512 B/s", formatBytesPerSec(512))
        // Unit boundaries: exactly 1 KB/s and exactly 1 MB/s land in the larger unit.
        assertEquals("1 KB/s", formatBytesPerSec(1024))
        assertEquals("1.0 MB/s", formatBytesPerSec(1024L * 1024))
        assertEquals("1023 B/s", formatBytesPerSec(1023))
    }
}
