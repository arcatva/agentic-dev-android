package dev.agentic.ui.session

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import dev.agentic.ui.AppMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ── Tunables ────────────────────────────────────────────────────────────────────────────────────
// Grab lane width. Kept to ~the list's right content padding (16dp) so the always-present thumb sits
// in that empty gutter and never overlaps card content (expand icons, buttons, selectable text) — a
// wider lane was found to swallow taps/selection near the right edge. The 48dp MD3 touch target is met
// vertically (thumb length); the narrow width is a deliberate trade to keep the edge non-blocking.
private val LaneWidth = 20.dp
private val ThumbThickness = 8.dp    // visible pill thickness
private val ThumbLength = 48.dp      // FIXED thumb length — never varies, so it can't jitter long/short
private val LaneEndPadding = 4.dp    // gap between the pill and the screen edge
private val BubbleGap = 8.dp         // gap between the lane and the preview bubble
private val BubbleWidth = 220.dp

/**
 * A grab-and-drag fast-scroll slider for the transcript, specialised to **snap to the user's own
 * messages** ([dev.agentic.domain.PromptNode]s). Fills the transcript area and overlays a thumb on the
 * right edge of the reverse-layout list.
 *
 * - **Persistent & grabbable**: shown (dim) whenever the list can scroll, brightening while
 *   scrolling/dragging — an only-visible-while-scrolling thumb is unreachable, since when you scroll
 *   your finger is on the content, not the edge.
 * - **Fixed length**: the thumb is a constant-size puck, so it never jitters long/short as
 *   wildly-different item heights scroll past (item-count-proportional thumbs do).
 * - **Consistent snapping**: the thumb's resting position and the anchors' snap positions share one
 *   from-top axis (by chronological index), so dragging to a spot lands on the message shown there,
 *   and dragging to the very top reliably reaches the first message. The dragging puck tracks the
 *   finger; the LIST is what snaps, docking the chosen message to the top with its reply below.
 * - **Preview bubble**: while dragging, an MD3 bubble shows the target message's opening text + `n/N`.
 *
 * Pure index/fraction math (and the reverse-layout inversion) lives in TranscriptScrollbarMath.kt and
 * is unit-tested; this composable is the gesture + rendering shell. Renders nothing when there are no
 * user messages to jump between. Pass `Modifier.matchParentSize()` so the wide bubble isn't clamped to
 * the lane; the root has no pointer handler, so touches off the thumb fall through to the list.
 *
 * MD3 has no official mobile scrollbar component, so this follows MD3 shape/color/motion tokens plus
 * the established Compose draggable-scrollbar pattern (a Box overlay reading [LazyListState.layoutInfo]
 * and driving [LazyListState.scrollToItem]).
 */
@Composable
fun MessageFastScrollbar(
    state: LazyListState,
    anchors: List<MsgAnchor>,
    modifier: Modifier = Modifier,
) {
    if (anchors.isEmpty()) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // Latest anchors for the long-lived drag callback (the DraggableState outlives a single
    // composition), so a drag always targets the current message list, never a stale one.
    val liveAnchors = rememberUpdatedState(anchors)

    // Pixel geometry captured from layout.
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    // Seed with a rough one-line-label height so the bubble's first frame is already near-centred
    // (it self-corrects via onSizeChanged), avoiding a one-frame pop on drag start.
    var bubbleHeightPx by remember { mutableFloatStateOf(with(density) { 44.dp.toPx() }) }

    // Drag state.
    var dragging by remember { mutableStateOf(false) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var activeOrdinal by remember { mutableIntStateOf(0) }

    // Resting thumb position (from-top fraction), driven by the leading-edge item + its intra-item
    // scroll (continuous), NOT the visible item count (which swings as mixed-size cards scroll past
    // and made the thumb jump — read as "changing size").
    val restFrac by remember {
        derivedStateOf {
            val info = state.layoutInfo
            restingThumbFraction(
                firstVisibleRevIndex = state.firstVisibleItemIndex,
                firstItemOffsetPx = state.firstVisibleItemScrollOffset,
                firstItemSizePx = info.visibleItemsInfo.firstOrNull()?.size ?: 0,
                totalCount = info.totalItemsCount,
            )
        }
    }

    // Persistent but quiet: dim at rest, bright while scrolling/dragging; hidden only when the list
    // can't scroll at all.
    val scrollable = state.canScrollForward || state.canScrollBackward
    val activeNow = state.isScrollInProgress || dragging
    val alpha by animateFloatAsState(
        targetValue = if (!scrollable) 0f else if (activeNow) 1f else 0.5f,
        animationSpec = tween(durationMillis = AppMotion.DurationShort4, easing = AppMotion.Emphasized),
        label = "fastScrollAlpha",
    )

    // Cancel-and-relaunch holder so a fast scrub across notches can't interleave two-step dock scrolls
    // (a remembered array avoids the recomposition a State write would cause on this hot path).
    val dockJob = remember { arrayOfNulls<Job>(1) }
    // Last resting thumb top (px), cached for the drag handler so a grab starts the puck exactly under
    // the finger (no teleport).
    val restingThumbTop = remember { floatArrayOf(0f) }

    // DraggableState is remembered unconditionally (never inside a branch).
    val draggableState = rememberDraggableState { delta ->
        val live = liveAnchors.value
        if (trackHeightPx <= 0f || live.isEmpty()) return@rememberDraggableState
        dragOffsetPx = (dragOffsetPx + delta).coerceIn(0f, trackHeightPx)
        val ordinal = nearestAnchorIndex(dragOffsetPx / trackHeightPx, live)
        if (ordinal != activeOrdinal) {
            activeOrdinal = ordinal
            val target = live[ordinal] // captured synchronously; immune to later list changes
            dockJob[0]?.cancel()
            dockJob[0] = scope.launch { state.dockAnchorToTop(target.revIndex) }
        }
    }

    // Root fills the parent; only the thumb has a pointer handler, so the list keeps its touches.
    Box(
        modifier = modifier.onSizeChanged {
            trackWidthPx = it.width.toFloat()
            trackHeightPx = it.height.toFloat()
        },
    ) {
        // Conditional RENDERING only (no remember in here) — safe to branch. Hidden when faded out so
        // the thumb never intercepts touches meant for the list.
        if (alpha > 0.02f && trackHeightPx > 0f) {
            val safeOrdinal = activeOrdinal.coerceIn(0, anchors.lastIndex)
            val thumbLenPx = with(density) { ThumbLength.toPx() }.coerceAtMost(trackHeightPx)
            val maxTop = (trackHeightPx - thumbLenPx).coerceAtLeast(0f)

            // Fixed-length puck: tracks the finger while dragging, sits at the resting fraction at rest.
            val thumbTopPx: Float
            if (dragging) {
                thumbTopPx = (dragOffsetPx - thumbLenPx / 2f).coerceIn(0f, maxTop)
            } else {
                thumbTopPx = (restFrac * maxTop).coerceIn(0f, maxTop)
                restingThumbTop[0] = thumbTopPx
            }

            val thumbColor =
                if (dragging) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant

            // The draggable thumb, pinned to the right edge; grab area = lane width × thumb length.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbTopPx.roundToInt()) }
                    .width(LaneWidth)
                    .height(with(density) { thumbLenPx.toDp() })
                    .alpha(alpha)
                    .semantics { contentDescription = "Drag to jump between your messages" }
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = draggableState,
                        onDragStarted = { started ->
                            dragging = true
                            // Start the puck centred on the exact grab point so it doesn't jump.
                            dragOffsetPx = (restingThumbTop[0] + started.y).coerceIn(0f, trackHeightPx)
                            activeOrdinal = nearestAnchorIndex(dragOffsetPx / trackHeightPx, liveAnchors.value)
                        },
                        onDragStopped = { dragging = false },
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .padding(end = LaneEndPadding)
                        .width(ThumbThickness)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(thumbColor),
                )
            }

            // Preview bubble while dragging — to the left of the lane, vertically centred on the thumb.
            if (dragging) {
                val anchor = anchors[safeOrdinal]
                val thumbCenterPx = thumbTopPx + thumbLenPx / 2f
                val bubbleYpx = (thumbCenterPx - bubbleHeightPx / 2f)
                    .coerceIn(0f, (trackHeightPx - bubbleHeightPx).coerceAtLeast(0f))
                // The lane mirrors with layout direction (Alignment.TopEnd), so the offset that pushes
                // the bubble toward the content must mirror too. Cap the width to what fits beside the
                // lane so it never runs off-screen on narrow devices (foldables, split-screen).
                val isRtl = layoutDirection == LayoutDirection.Rtl
                val bubbleMaxWidth = with(density) {
                    (trackWidthPx - (LaneWidth + BubbleGap).toPx() - 16.dp.toPx())
                        .coerceAtLeast(120.dp.toPx()).toDp()
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.large,
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            val dx = LaneWidth.roundToPx() + BubbleGap.roundToPx()
                            IntOffset(x = if (isRtl) dx else -dx, y = bubbleYpx.roundToInt())
                        }
                        .alpha(alpha)
                        .width(BubbleWidth.coerceAtMost(bubbleMaxWidth))
                        .onSizeChanged { bubbleHeightPx = it.height.toFloat() },
                ) {
                    Text(
                        text = "${anchor.preview}  ·  ${safeOrdinal + 1}/${anchors.size}",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Dock the item at [revIndex] to the **top** of a reverse-layout viewport.
 *
 * In `reverseLayout = true`, item index 0 sits at the bottom and `scrollToItem(index)` lands the item
 * at the layout's leading edge — the **bottom** (the existing transcript autoscroll relies on this:
 * `scrollToItem(0)` glues the newest item to the bottom). So a single `scrollToItem` would leave the
 * message at the bottom with its reply hidden below the fold; a second pass lifts it to the top by
 * scrolling toward newer content (`scrollBy` negative — positive scrolls toward older/higher indices).
 *
 * Crucially, when `scrollToItem` can't reach the leading edge because the item is already near the top
 * (e.g. the oldest message — nothing newer-below to push it down), it clamps and the item is *already*
 * as high as it can be. Detect that via `firstVisibleItemIndex != revIndex` and skip the lift, so the
 * first message isn't shoved back down (the bug behind "snapping to the first message is off").
 */
private suspend fun LazyListState.dockAnchorToTop(revIndex: Int) {
    scrollToItem(revIndex)
    if (firstVisibleItemIndex != revIndex) return
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == revIndex } ?: return
    val push = (info.viewportEndOffset - info.viewportStartOffset - item.size).toFloat()
    if (push > 0f) scrollBy(-push)
}
