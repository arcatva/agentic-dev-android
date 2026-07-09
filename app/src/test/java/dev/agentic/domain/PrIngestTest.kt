package dev.agentic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Locks PR-card ingestion: a backend `kind:pr` WS frame (live) and the persisted `{"type":"pr",…}` log
 * marker (reseed) both decode to the same [PrNode], so a PR card shows live and survives a reconnect.
 * The two share the same field names by design (see prNodeFrom in Transcript.kt).
 */
class PrIngestTest {
    private val frame =
        """{"kind":"pr","url":"https://github.com/arcatva/agentic-dev/pull/26","number":26,"repo":"arcatva/agentic-dev","title":"Add PR cards","body":"Backend-driven.\n\n- detail","state":"OPEN"}"""
    private val marker =
        """{"type":"pr","url":"https://github.com/arcatva/agentic-dev/pull/26","number":26,"repo":"arcatva/agentic-dev","title":"Add PR cards","body":"Backend-driven.\n\n- detail","state":"OPEN"}"""

    @Test
    fun liveFrameBecomesPrNode() {
        val (nodes, ended, _) = applyEvent(emptyList(), frame)
        val pr = nodes.single() as PrNode
        assertEquals("https://github.com/arcatva/agentic-dev/pull/26", pr.url)
        assertEquals(26, pr.number)
        assertEquals("arcatva/agentic-dev", pr.repo)
        assertEquals("Add PR cards", pr.title)
        assertEquals("Backend-driven.\n\n- detail", pr.body)
        assertEquals("OPEN", pr.state)
        assertFalse("a pr frame does not end the turn", ended)
    }

    @Test
    fun logMarkerRebuildsPrNode() {
        val pr = buildFromLog(listOf(marker)).single() as PrNode
        assertEquals(26, pr.number)
        assertEquals("arcatva/agentic-dev", pr.repo)
        assertEquals("Add PR cards", pr.title)
        assertEquals("Backend-driven.\n\n- detail", pr.body)
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val pr = buildFromLog(listOf("""{"type":"pr","url":"https://github.com/o/r/pull/1"}""")).single() as PrNode
        assertEquals(0, pr.number)      // number absent → 0
        assertEquals("", pr.title)      // title absent → ""
        assertEquals("OPEN", pr.state)  // state absent → default "OPEN"
    }
}
