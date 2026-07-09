package dev.agentic.ui.session

import dev.agentic.domain.Node
import dev.agentic.domain.PromptNode

/**
 * Pure math for the message fast-scroll slider (no Compose / Android types so it is unit-testable on
 * the JVM — see TranscriptScrollbarTest).
 *
 * The transcript is a `reverseLayout = true` LazyColumn, so two coordinate systems are in play:
 *  - **chronological index** — position in the `nodes` list (0 = oldest, size-1 = newest).
 *  - **reverse index** — the LazyColumn's own item index (0 = newest = bottom of the screen).
 * They convert with `revIndex = size - 1 - chronoIndex`.
 *
 * The slider's track runs **top = oldest … bottom = newest**. Everything below shares ONE position
 * axis: a "from-top fraction" in [0,1] derived from an item's chronological index
 * (`chronoIndex / (totalCount-1)`). The thumb's resting position and the anchors' snap positions both
 * use this axis, so dragging the thumb to a spot lands on the message shown there — they can't drift
 * apart the way an evenly-spaced mapping does.
 */

/** Max characters shown in the drag preview bubble for a user message. */
private const val PREVIEW_MAX = 80

private val WHITESPACE = Regex("\\s+")

/** A draggable snap target: one user-sent message ([PromptNode]) in the transcript. */
data class MsgAnchor(
    /** Index in the chronological `nodes` list (0 = oldest). */
    val chronoIndex: Int,
    /** The LazyColumn item index in reverse layout (`nodes.size - 1 - chronoIndex`). */
    val revIndex: Int,
    /** Track position from the top, 0f = oldest … 1f = newest (`chronoIndex / (size-1)`). */
    val fraction: Float,
    /** Single-lined, trimmed, length-capped text for the drag preview bubble. */
    val preview: String,
)

/**
 * Extract the fast-scroll anchors — every user-sent [PromptNode] with non-blank text, oldest first.
 *
 * The `isNotBlank()` filter mirrors the renderer (Transcript.kt only draws a PromptNode when its text
 * is non-blank), so anchors never point at an invisible item. Each anchor's [MsgAnchor.fraction] is
 * its real position among all items, so snapping reflects where messages actually sit.
 */
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

/**
 * Index of the anchor whose track position is nearest the drag [fraction] (0 = top/oldest …
 * 1 = bottom/newest). Compares against each anchor's real [MsgAnchor.fraction], so dragging to a spot
 * selects the message actually shown there — and dragging to the very top reliably hits the first
 * message. Returns 0 for an empty list (the caller guards against that anyway).
 */
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

/**
 * Resting thumb position as a from-top fraction (0 = top/oldest … 1 = bottom/newest).
 *
 * Derived from the **bottom-visible (leading-edge) item** plus how far it is scrolled —
 * `firstVisibleRevIndex + firstItemOffsetPx/firstItemSizePx` — NOT from the visible item *count*.
 * That count swings wildly as clusters of tiny inline chips vs big messages scroll past (8 visible
 * then 2), which made the old count-based position jump around — read by the user as the thumb
 * "changing size". The leading-edge index plus its intra-item offset increases **continuously and
 * monotonically** with scroll regardless of item heights, so the thumb glides.
 *
 * Endpoints: at the newest (index 0, offset 0) the thumb sits at the bottom (1f); approaching the
 * oldest it nears the top. It tracks the viewport's bottom edge, so after a snap-to-top dock the
 * thumb settles a little below that message — an acceptable trade for jitter-free scrolling.
 */
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
