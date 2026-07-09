package dev.agentic.domain

import dev.agentic.data.net.CommitNode

/**
 * Pure-Kotlin commit-graph layout engine (no Compose). Turns a newest-first list of [CommitNode]
 * into a list of [GraphRow] that the Compose renderer can draw row-by-row without seeing any other
 * row — every cross-row geometry detail is precomputed here.
 *
 * It implements the standard "active lanes" sweep used by JetBrains' VCS Log and `git log --graph`:
 * scan top→bottom (newest→oldest); each lane is a column waiting for a specific ancestor SHA; the
 * first parent keeps a commit's lane (so the mainline stays a straight vertical), extra parents open
 * new lanes to the right (merges fan out), and lanes collapse back when a shared ancestor is reached.
 * Colors are keyed by a stable [GraphEdge.laneId] (per-branch), not by column, so a branch keeps its
 * color even when it shifts columns.
 */

enum class GraphNodeKind { Uncommitted, Commit }

/**
 * One drawable edge crossing a single row's vertical band.
 *  - [fromTop] true  → the edge starts at the row's top boundary (y = 0); false → at the node centre.
 *  - [toBottom] true → the edge ends at the row's bottom boundary (y = h); false → at the node centre.
 * A pass-through lane is fromTop && toBottom. A lane closing into the node ends at the centre; a lane
 * born at the node starts at the centre. [fromColumn] != [toColumn] means the lane shifts column and
 * is drawn as a curve. [fadeBottom] marks an edge that runs off the bottom of the 30-commit window
 * (an ancestor we don't have) so the renderer can fade it out instead of ending it hard.
 */
data class GraphEdge(
    val fromColumn: Int,
    val toColumn: Int,
    val laneId: Int,
    val fromTop: Boolean,
    val toBottom: Boolean,
    val fadeBottom: Boolean = false,
)

/** Everything one row needs to draw its gutter. [commit] is null for the synthetic uncommitted node. */
data class GraphRow(
    val commit: CommitNode?,
    val nodeColumn: Int,
    val nodeLaneId: Int,
    val isMerge: Boolean,
    val isOctopus: Boolean,
    val edges: List<GraphEdge>,
    val maxColumn: Int,
    val kind: GraphNodeKind,
)

private class ActiveLane(val expectedSha: String, val laneId: Int)

private class InputNode(
    val commit: CommitNode?,
    val sha: String?,
    val parents: List<String>,
    val kind: GraphNodeKind,
)

/**
 * Build the renderable graph. [commits] must be newest-first (as the backend returns it). When
 * [hasUncommitted] is true a synthetic working-tree node is placed above HEAD, connected down into
 * HEAD's lane.
 */
fun buildCommitGraph(commits: List<CommitNode>, hasUncommitted: Boolean): List<GraphRow> {
    if (commits.isEmpty() && !hasUncommitted) return emptyList()

    val inputs = ArrayList<InputNode>(commits.size + 1)
    if (hasUncommitted) {
        val headParents = if (commits.isNotEmpty()) listOf(commits[0].sha) else emptyList()
        inputs.add(InputNode(null, null, headParents, GraphNodeKind.Uncommitted))
    }
    commits.forEach { inputs.add(InputNode(it, it.sha, it.parents, GraphNodeKind.Commit)) }

    // sha → index, used to tell whether a parent is reachable below the current row. git log is
    // date-ordered, not strictly topological, so a parent can occasionally appear at or above its
    // child; we must not open a lane waiting for such a parent (it would never resolve).
    val shaToIndex = HashMap<String, Int>(inputs.size)
    inputs.forEachIndexed { i, n -> n.sha?.let { shaToIndex[it] = i } }
    fun presentAbove(sha: String, currentIndex: Int): Boolean {
        val idx = shaToIndex[sha] ?: return false   // absent = below the window → keep waiting (fades)
        return idx <= currentIndex
    }

    val active = ArrayList<ActiveLane?>()   // index = column; null = a reusable hole
    var nextLaneId = 0
    var maxColumn = 0

    fun allocate(): Int {
        val hole = active.indexOfFirst { it == null }
        return if (hole >= 0) hole else { active.add(null); active.size - 1 }
    }

    val rows = ArrayList<GraphRow>(inputs.size)

    for (i in inputs.indices) {
        val node = inputs[i]

        // Lanes entering this row from above (keyed by laneId → column).
        val topCols = HashMap<Int, Int>()
        active.forEachIndexed { col, lane -> if (lane != null) topCols[lane.laneId] = col }

        // 1) The column this commit sits in: the leftmost active lane waiting for its SHA. All other
        //    lanes waiting for the same SHA (multiple children) collapse onto it.
        var myCol = -1
        val collapsing = ArrayList<Int>()
        if (node.sha != null) {
            active.forEachIndexed { col, lane ->
                if (lane != null && lane.expectedSha == node.sha) {
                    collapsing.add(col)
                    if (myCol == -1) myCol = col
                }
            }
        }
        val myLaneId: Int
        if (myCol == -1) {
            myCol = allocate()
            myLaneId = nextLaneId++
        } else {
            myLaneId = active[myCol]!!.laneId
        }

        // 2) Close the other collapsing lanes.
        for (col in collapsing) if (col != myCol) active[col] = null

        // 3) Route parents. First parent keeps myCol/myLaneId (mainline stays straight); extra
        //    parents reuse a lane already waiting for them, else open a new lane to the right.
        val mergeIntoExisting = ArrayList<Pair<Int, Int>>()   // (column, laneId) of pre-existing lanes
        if (node.parents.isEmpty()) {
            active[myCol] = null   // root commit: the lane ends here
        } else {
            val first = node.parents[0]
            active[myCol] = if (presentAbove(first, i)) null else ActiveLane(first, myLaneId)
            for (pi in 1 until node.parents.size) {
                val p = node.parents[pi]
                if (presentAbove(p, i)) continue
                val existing = active.indexOfFirst { it != null && it.expectedSha == p }
                if (existing >= 0) {
                    mergeIntoExisting.add(existing to active[existing]!!.laneId)
                } else {
                    val nc = allocate()
                    active[nc] = ActiveLane(p, nextLaneId++)
                }
            }
        }

        // Keep the lane list tight: drop trailing holes so the gutter doesn't grow forever.
        while (active.isNotEmpty() && active.last() == null) active.removeAt(active.size - 1)

        // Lanes leaving this row downward.
        val botCols = HashMap<Int, Int>()
        active.forEachIndexed { col, lane -> if (lane != null) botCols[lane.laneId] = col }

        val isLast = i == inputs.lastIndex
        val edges = ArrayList<GraphEdge>()
        // Pass-through: present above and below.
        for (id in topCols.keys.intersect(botCols.keys)) {
            edges.add(GraphEdge(topCols.getValue(id), botCols.getValue(id), id, fromTop = true, toBottom = true, fadeBottom = isLast))
        }
        // Closing into the node: present above only (a child lane merging in / a branch ending).
        for (id in topCols.keys - botCols.keys) {
            edges.add(GraphEdge(topCols.getValue(id), myCol, id, fromTop = true, toBottom = false))
        }
        // Born at the node: present below only (the first-parent continuation of a tip, or a new
        // merge-parent lane).
        for (id in botCols.keys - topCols.keys) {
            edges.add(GraphEdge(myCol, botCols.getValue(id), id, fromTop = false, toBottom = true, fadeBottom = isLast))
        }
        // Merge connectors into a pre-existing lane (the side branch already had a column).
        for ((col, id) in mergeIntoExisting) {
            edges.add(GraphEdge(myCol, col, id, fromTop = false, toBottom = true, fadeBottom = isLast))
        }

        edges.forEach { maxColumn = maxOf(maxColumn, it.fromColumn, it.toColumn) }
        maxColumn = maxOf(maxColumn, myCol)

        rows.add(
            GraphRow(
                commit = node.commit,
                nodeColumn = myCol,
                nodeLaneId = myLaneId,
                isMerge = node.parents.size >= 2,
                isOctopus = node.parents.size > 2,
                edges = edges,
                maxColumn = 0,   // filled in below once the global max is known
                kind = node.kind,
            ),
        )
    }

    return rows.map { it.copy(maxColumn = maxColumn) }
}
