package dev.agentic.domain


/**
 * Pure-Kotlin commit-graph layout engine (no Compose): newest-first [CommitLike]s → precomputed
 * [GraphRow]s the renderer can draw row-by-row without cross-row state. Standard "active lanes"
 * sweep (VCS Log / `git log --graph`): first parent keeps its lane (mainline straight), extra
 * parents open lanes to the right, collapse at shared ancestors. Colors keyed by stable
 * [GraphEdge.laneId] (per-branch) so a branch keeps its color when shifting columns.
 */

enum class GraphNodeKind { Uncommitted, Commit }

/**
 * One drawable edge crossing a single row's vertical band. [fromTop]/[toBottom] = start/end at row's top/bottom boundary vs. node centre; pass-through = both true. [fromColumn] != [toColumn] → drawn as curve (lane shift). [fadeBottom] = edge runs off the bottom of the window (ancestor we don't have), fade instead of hard end.
 */
data class GraphEdge(
    val fromColumn: Int,
    val toColumn: Int,
    val laneId: Int,
    val fromTop: Boolean,
    val toBottom: Boolean,
    val fadeBottom: Boolean = false,
)

/** Everything one row needs to draw its gutter. [commit] null for the synthetic uncommitted node. */
data class GraphRow<C : CommitLike>(
    val commit: C?,
    val nodeColumn: Int,
    val nodeLaneId: Int,
    val isMerge: Boolean,
    val isOctopus: Boolean,
    val edges: List<GraphEdge>,
    val maxColumn: Int,
    val kind: GraphNodeKind,
)

private class ActiveLane(val expectedSha: String, val laneId: Int)

private class InputNode<C : CommitLike>(
    val commit: C?,
    val sha: String?,
    val parents: List<String>,
    val kind: GraphNodeKind,
)

/** Build renderable graph. [commits] must be newest-first (as backend returns); [hasUncommitted] places a synthetic working-tree node above HEAD. */
fun <C : CommitLike> buildCommitGraph(commits: List<C>, hasUncommitted: Boolean): List<GraphRow<C>> {
    if (commits.isEmpty() && !hasUncommitted) return emptyList()

    val inputs = ArrayList<InputNode<C>>(commits.size + 1)
    if (hasUncommitted) {
        val headParents = if (commits.isNotEmpty()) listOf(commits[0].sha) else emptyList()
        inputs.add(InputNode(null, null, headParents, GraphNodeKind.Uncommitted))
    }
    commits.forEach { inputs.add(InputNode(it, it.sha, it.parents, GraphNodeKind.Commit)) }

    // git log is date-ordered not strictly topological — a parent can appear at/above its child; never open a lane waiting for one that won't resolve below.
    val shaToIndex = HashMap<String, Int>(inputs.size)
    inputs.forEachIndexed { i, n -> n.sha?.let { shaToIndex[it] = i } }
    fun presentAbove(sha: String, currentIndex: Int): Boolean {
        val idx = shaToIndex[sha] ?: return false   // absent = below window → keep waiting (fades)
        return idx <= currentIndex
    }

    val active = ArrayList<ActiveLane?>()   // index = column; null = reusable hole
    var nextLaneId = 0
    var maxColumn = 0

    fun allocate(): Int {
        val hole = active.indexOfFirst { it == null }
        return if (hole >= 0) hole else { active.add(null); active.size - 1 }
    }

    val rows = ArrayList<GraphRow<C>>(inputs.size)

    for (i in inputs.indices) {
        val node = inputs[i]

        // Lanes entering this row from above (laneId → column).
        val topCols = HashMap<Int, Int>()
        active.forEachIndexed { col, lane -> if (lane != null) topCols[lane.laneId] = col }

        // 1) My column = leftmost active lane waiting for my SHA; other lanes waiting for same SHA (multiple children) collapse onto it.
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

        // 3) Route parents: first parent keeps myCol/myLaneId (mainline straight); extra parents reuse a waiting lane or open one to the right.
        val mergeIntoExisting = ArrayList<Pair<Int, Int>>()   // (column, laneId) of pre-existing lanes
        if (node.parents.isEmpty()) {
            active[myCol] = null   // root commit: lane ends here
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

        // Drop trailing holes so the gutter doesn't grow forever.
        while (active.isNotEmpty() && active.last() == null) active.removeAt(active.size - 1)

        // Lanes leaving this row downward (laneId → column).
        val botCols = HashMap<Int, Int>()
        active.forEachIndexed { col, lane -> if (lane != null) botCols[lane.laneId] = col }

        val isLast = i == inputs.lastIndex
        val edges = ArrayList<GraphEdge>()
        // Pass-through: present above and below.
        for (id in topCols.keys.intersect(botCols.keys)) {
            edges.add(GraphEdge(topCols.getValue(id), botCols.getValue(id), id, fromTop = true, toBottom = true, fadeBottom = isLast))
        }
        // Closing into the node: present above only (child lane merging in / branch ending).
        for (id in topCols.keys - botCols.keys) {
            edges.add(GraphEdge(topCols.getValue(id), myCol, id, fromTop = true, toBottom = false))
        }
        // Born at the node: present below only (first-parent continuation of a tip, or a new merge-parent lane).
        for (id in botCols.keys - topCols.keys) {
            edges.add(GraphEdge(myCol, botCols.getValue(id), id, fromTop = false, toBottom = true, fadeBottom = isLast))
        }
        // Merge connectors into a pre-existing lane (side branch already had a column).
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
                maxColumn = 0,   // filled in below once global max is known
                kind = node.kind,
            ),
        )
    }

    return rows.map { it.copy(maxColumn = maxColumn) }
}
