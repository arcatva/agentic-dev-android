package dev.agentic.ui.session

import dev.agentic.domain.Node
import dev.agentic.domain.PromptNode

/** Pure math for the message fast-scroll slider (no Compose/Android types — JVM unit-testable). Track axis: top=oldest, bottom=newest, single from-top fraction `chronoIndex/(size-1)`. */

/** Max chars shown in the drag preview bubble. */
private const val PREVIEW_MAX = 80

private val WHITESPACE = Regex("\\s+")

/** A draggable snap target: one user-sent message ([PromptNode]) in the transcript. */
data class MsgAnchor(
    /** Chronological index in `nodes` (0 = oldest). */
    val chronoIndex: Int,
    /** LazyColumn item index in reverse layout (`nodes.size - 1 - chronoIndex`). */
    val revIndex: Int,
    /** Track position from top, 0f = oldest … 1f = newest. */
    val fraction: Float,
    /** Single-lined, trimmed, length-capped text for the preview bubble. */
    val preview: String,
)

/** Fast-scroll anchors: every non-blank [PromptNode] oldest-first. The `isNotBlank()` filter mirrors the renderer (no invisible anchors). */
fun userMessageAnchors(nodes: List<Node>): List<MsgAnchor> {
    val size = nodes.size
    val span = (size - 1).coerceAtLeast(1) // avoid /0 for a 1-node transcript
    val out = ArrayList<MsgAnchor>()
    nodes.forEachIndexed { i, node ->
        if (node is PromptNode && node.text.isNotBlank()) {
            out += MsgAnchor(
                chronoIndex = i,
                revIndex = size - 1 - i,
                fraction = if (size <= 1) 0f else i.toFloat() / span,
                preview = node.text.trim().replace(WHITESPACE, " ").take(PREVIEW_MAX),
            )
        }
    }
    return out
}

/** Nearest anchor to [fraction] on the from-top axis; dragging to the top reliably hits the first message. Returns 0 for empty list. */
fun nearestAnchorIndex(fraction: Float, anchors: List<MsgAnchor>): Int {
    if (anchors.isEmpty()) return 0
    var best = 0
    var bestDist = Float.MAX_VALUE
    anchors.forEachIndexed { i, a ->
        val d = kotlin.math.abs(fraction - a.fraction)
        if (d < bestDist) {
            bestDist = d
            best = i
        }
    }
    return best
}

/** Resting thumb position as a from-top fraction.
 * Uses leading-edge index + intra-item offset (continuous, monotonic) rather than visible item COUNT (which swings wildly as mixed-size cards scroll past).
 * Tracks the viewport's bottom edge — after a snap-to-top dock the thumb settles a little below that message. */
fun restingThumbFraction(
    firstVisibleRevIndex: Int,
    firstItemOffsetPx: Int,
    firstItemSizePx: Int,
    totalCount: Int,
): Float {
    if (totalCount <= 1) return 1f
    val within = if (firstItemSizePx > 0) (firstItemOffsetPx.toFloat() / firstItemSizePx).coerceIn(0f, 1f) else 0f
    val scrolled = firstVisibleRevIndex + within // items scrolled up from the newest (bottom)
    val towardTop = (scrolled / (totalCount - 1)).coerceIn(0f, 1f) // 0 = newest/bottom, 1 = oldest/top
    return 1f - towardTop // from-top: 0 = oldest/top, 1 = newest/bottom
}
