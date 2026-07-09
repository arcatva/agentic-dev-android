package dev.agentic.ui.session

import dev.agentic.domain.PromptNode
import dev.agentic.domain.TextNode
import dev.agentic.domain.ThinkingNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [transcriptNodeKeys] is what makes the bounded window slide without jumping: a node's LazyColumn key
 * must stay STABLE when pages are evicted/prepended/appended around it (so Compose re-anchors the
 * viewport and preserves item state) while remaining UNIQUE (Compose crashes on duplicate keys).
 */
class TranscriptKeysTest {

    @Test fun `keys are unique within a list`() {
        val nodes = listOf(
            PromptNode("hello"), TextNode("world"), TextNode("world"), ThinkingNode("hmm"), PromptNode("hello"),
        )
        val keys = transcriptNodeKeys(nodes)
        assertEquals("one key per node", nodes.size, keys.size)
        assertEquals("all keys distinct", keys.toSet().size, keys.size)
    }

    @Test fun `distinct-content node keeps its key as the window slides around it`() {
        // A 5-node session; distinct content throughout.
        val full = listOf(
            PromptNode("q0"), TextNode("a1"), TextNode("a2"), TextNode("a3"), PromptNode("q4"),
        )
        // Window A: the older 4 (newest q4 evicted). Window B: slid by one (oldest q0 evicted, q4 paged in).
        val windowA = full.subList(0, 4)          // [q0, a1, a2, a3]
        val windowB = full.subList(1, 5)          // [a1, a2, a3, q4]
        val keysA = transcriptNodeKeys(windowA)
        val keysB = transcriptNodeKeys(windowB)
        // The three nodes visible in BOTH windows (a1, a2, a3) must keep identical keys — that's what lets
        // Compose pin the viewport on them across the reseed.
        assertEquals(keysA.subList(1, 4), keysB.subList(0, 3))
    }

    @Test fun `distinct-content node keeps its key when OLDER content is prepended`() {
        // The scroll-UP direction: loadEarlier prepends an older page at the top. Existing (visible) nodes
        // must keep their keys so Compose pins the viewport — the case that lets scroll-back not jump.
        val base = listOf(TextNode("a1"), TextNode("a2"), PromptNode("q3"))
        val prepended = listOf(PromptNode("q0"), TextNode("older")) + base
        val kb = transcriptNodeKeys(base)
        val kp = transcriptNodeKeys(prepended)
        assertEquals("base nodes keep identical keys after an older prepend", kb, kp.subList(2, 5))
    }

    @Test fun `identical-content twins get distinct but position-independent-ish keys`() {
        // Two identical TextNodes: they must differ (occurrence 0 vs 1). The FIRST twin's key is
        // unaffected by anything appended AFTER it — so a newer page loading in doesn't disturb it.
        val a = listOf(TextNode("dup"), PromptNode("x"), TextNode("dup"))
        val b = a + PromptNode("newer")   // append a newer node
        val ka = transcriptNodeKeys(a)
        val kb = transcriptNodeKeys(b)
        assertNotEquals("twins differ", ka[0], ka[2])
        assertEquals("first twin stable across an append after it", ka[0], kb[0])
        assertEquals("second twin stable across an append after it", ka[2], kb[2])
    }
}
