package dev.agentic.domain

import dev.agentic.data.net.CommitNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitGraphTest {

    private fun c(sha: String, vararg parents: String, session: Boolean = false) =
        CommitNode(
            sha = sha,
            shortSha = sha.take(7),
            parents = parents.toList(),
            subject = sha,
            author = "t",
            at = 0L,
            isSession = session,
        )

    /** Linear history stays a single straight lane (column 0, lane 0); the oldest row fades off-window. */
    @Test
    fun linear_history_is_one_lane() {
        // newest-first; the oldest commit's parent "d" is outside the window.
        val rows = buildCommitGraph(listOf(c("a", "b"), c("b", "c"), c("c", "d")), hasUncommitted = false)
        assertEquals(3, rows.size)
        assertTrue("all on column 0 / lane 0", rows.all { it.nodeColumn == 0 && it.nodeLaneId == 0 })
        assertTrue("no extra lanes", rows.all { it.maxColumn == 0 })
        assertFalse("no merges", rows.any { it.isMerge })
        assertTrue("oldest row fades off-window", rows.last().edges.any { it.fadeBottom && it.toBottom })
    }

    /** A 2-parent merge opens a second lane that stays one stable colour and collapses at the shared ancestor. */
    @Test
    fun two_parent_merge_opens_then_closes_a_lane() {
        //   m (merge of a,b) → a → base ← b      base is the shared ancestor (root here)
        val rows = buildCommitGraph(
            listOf(c("m", "a", "b"), c("a", "base"), c("b", "base"), c("base")),
            hasUncommitted = false,
        )
        assertEquals(4, rows.size)
        assertTrue("merge flagged", rows[0].isMerge && !rows[0].isOctopus)
        assertEquals("exactly one extra lane", 1, rows[0].maxColumn)

        // The side branch (commit "b") sits on the second lane and keeps a non-trunk lane id.
        val b = rows[2]
        assertEquals(1, b.nodeColumn)
        assertTrue("side branch is not the trunk lane", b.nodeLaneId != 0)

        // At "base" the side lane collapses into the trunk node (a diagonal closing edge into column 0).
        val base = rows[3]
        assertEquals(0, base.nodeColumn)
        assertEquals(0, base.nodeLaneId)
        assertTrue(
            "side lane closes into the trunk node",
            base.edges.any { it.fromColumn == 1 && it.toColumn == 0 && it.fromTop && !it.toBottom },
        )
        // The side branch's lane id is the same at the merge (born) and where it later sits.
        val bornSideLaneId = rows[0].edges.first { it.toColumn == 1 && !it.fromTop }.laneId
        assertEquals(bornSideLaneId, b.nodeLaneId)
    }

    /** A parent beyond the 30-commit window leaves the lane open → a faded stub on the last row, no crash. */
    @Test
    fun parent_off_window_fades() {
        val rows = buildCommitGraph(listOf(c("a", "b"), c("b", "z")), hasUncommitted = false) // "z" absent
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.maxColumn == 0 })
        assertTrue("last row fades downward", rows.last().edges.any { it.fadeBottom && it.toBottom })
    }

    /** An octopus (>2 parents) merge: hollow node, three distinct lanes, all collapsing at the shared ancestor. */
    @Test
    fun octopus_merge() {
        val rows = buildCommitGraph(
            listOf(c("o", "p1", "p2", "p3"), c("p1", "r"), c("p2", "r"), c("p3", "r"), c("r")),
            hasUncommitted = false,
        )
        assertEquals(5, rows.size)
        assertTrue("octopus flagged", rows[0].isOctopus)
        assertEquals(2, rows[0].maxColumn)
        val bornLaneIds = rows[0].edges.filter { !it.fromTop && it.toBottom }.map { it.laneId }.toSet()
        assertEquals("three distinct lanes leave the octopus node", 3, bornLaneIds.size)
        // At "r" the two extra lanes collapse back onto the trunk node.
        val r = rows[4]
        assertTrue("extra lanes close into the trunk", r.edges.count { !it.toBottom && it.toColumn == 0 } >= 2)
    }

    /** Date-order (non-topological) guard: a parent listed above its child must not open a never-closing lane. */
    @Test
    fun non_topological_parent_does_not_open_forever_lane() {
        // "p" (the parent) appears ABOVE its child "c" — the pathological reversed-date case.
        val rows = buildCommitGraph(listOf(c("p", "q"), c("c", "p")), hasUncommitted = false)
        assertEquals(2, rows.size)
        val child = rows[1]
        assertFalse(
            "child's own lane is not carried downward forever",
            child.edges.any { it.laneId == child.nodeLaneId && it.toBottom },
        )
    }

    /** The synthetic uncommitted node connects down into HEAD's lane. */
    @Test
    fun uncommitted_node_sits_above_head() {
        val rows = buildCommitGraph(listOf(c("head", "p")), hasUncommitted = true)
        assertEquals(2, rows.size)
        assertEquals(GraphNodeKind.Uncommitted, rows[0].kind)
        assertEquals(GraphNodeKind.Commit, rows[1].kind)
        assertTrue("working-tree node has a line running down to HEAD", rows[0].edges.any { it.toBottom })
        assertEquals("HEAD on the trunk", 0, rows[1].nodeLaneId)
    }
}
