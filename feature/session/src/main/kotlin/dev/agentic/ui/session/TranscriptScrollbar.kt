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

// Lane matches the right content gutter (~16dp) so the thumb never overlaps cards; narrow width is deliberate.
// 48dp touch target is met vertically (thumb length).
private val LaneWidth = 20.dp
private val ThumbThickness = 8.dp    // visible pill thickness
private val ThumbLength = 48.dp      // FIXED — never varies, so it can't jitter long/short
private val LaneEndPadding = 4.dp    // gap between the pill and the screen edge
private val BubbleGap = 8.dp         // gap between the lane and the preview bubble
private val BubbleWidth = 220.dp

/** Grab-and-drag fast-scroll slider that snaps to [dev.agentic.domain.PromptNode]s. Fixed-length thumb, single from-top axis shared by resting + snap positions. */
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
    val liveAnchors = rememberUpdatedState(anchors)

    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    // Seed one-line label height so the bubble's first frame is near-centred (self-corrects via onSizeChanged).
    var bubbleHeightPx by remember { mutableFloatStateOf(with(density) { 44.dp.toPx() }) }

    var dragging by remember { mutableStateOf(false) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var activeOrdinal by remember { mutableIntStateOf(0) }

    // Resting fraction uses leading-edge index + intra-item offset (continuous), NOT visible item count (which swings wildly as mixed-size cards scroll past).
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

    val scrollable = state.canScrollForward || state.canScrollBackward
    val activeNow = state.isScrollInProgress || dragging
    val alpha by animateFloatAsState(
        targetValue = if (!scrollable) 0f else if (activeNow) 1f else 0.5f,
        animationSpec = tween(durationMillis = AppMotion.DurationShort4, easing = AppMotion.Emphasized),
        label = "fastScrollAlpha",
    )

    // remembered array (not State) so the hot path doesn't recompose on slot writes.
    val dockJob = remember { arrayOfNulls<Job>(1) }
    // Cached so a grab starts the puck exactly under the finger.
    val restingThumbTop = remember { floatArrayOf(0f) }

    // DraggableState remembered unconditionally (never inside a branch).
    val draggableState = rememberDraggableState { delta ->
        val live = liveAnchors.value
        if (trackHeightPx <= 0f || live.isEmpty()) return@rememberDraggableState
        dragOffsetPx = (dragOffsetPx + delta).coerceIn(0f, trackHeightPx)
        val ordinal = nearestAnchorIndex(dragOffsetPx / trackHeightPx, live)
        if (ordinal != activeOrdinal) {
            activeOrdinal = ordinal
            // Captured synchronously; immune to later list changes.
            val target = live[ordinal]
            dockJob[0]?.cancel()
            dockJob[0] = scope.launch { state.dockAnchorToTop(target.revIndex) }
        }
    }

    Box(
        modifier = modifier.onSizeChanged {
            trackWidthPx = it.width.toFloat()
            trackHeightPx = it.height.toFloat()
        },
    ) {
        // Conditional RENDERING only (no remember in here) — safe to branch.
        if (alpha > 0.02f && trackHeightPx > 0f) {
            val safeOrdinal = activeOrdinal.coerceIn(0, anchors.lastIndex)
            val thumbLenPx = with(density) { ThumbLength.toPx() }.coerceAtMost(trackHeightPx)
            val maxTop = (trackHeightPx - thumbLenPx).coerceAtLeast(0f)

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
                            // Centre on the exact grab point.
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

            // Preview bubble while dragging.
            if (dragging) {
                val anchor = anchors[safeOrdinal]
                val thumbCenterPx = thumbTopPx + thumbLenPx / 2f
                val bubbleYpx = (thumbCenterPx - bubbleHeightPx / 2f)
                    .coerceIn(0f, (trackHeightPx - bubbleHeightPx).coerceAtLeast(0f))
                // Mirror the offset with layout direction so RTL pushes the bubble the other way; cap width to what fits beside the lane (foldables/split-screen).
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
 * Dock the item at [revIndex] to the TOP of a reverse-layout viewport.
 *
 * In `reverseLayout = true`, item index 0 sits at the bottom and `scrollToItem(index)` lands it at the **bottom** (autoscroll relies on this). One pass leaves the message at the bottom with its reply below the fold; a second pass lifts to top via a NEGATIVE `scrollBy` (positive scrolls toward older/higher indices).
 *
 * When `scrollToItem` can't reach the leading edge because the item is already near the top (e.g. the oldest message), it clamps. Detect via `firstVisibleItemIndex != revIndex` and skip the lift so the first message isn't shoved back down.
 */
private suspend fun LazyListState.dockAnchorToTop(revIndex: Int) {
    scrollToItem(revIndex)
    if (firstVisibleItemIndex != revIndex) return
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == revIndex } ?: return
    val push = (info.viewportEndOffset - info.viewportStartOffset - item.size).toFloat()
    if (push > 0f) scrollBy(-push)
}
