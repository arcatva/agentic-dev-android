package dev.agentic.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.agentic.ui.fadingEdgeVertical

/**
 * A column capped at [maxHeight]: taller content scrolls vertically INSIDE the cap with dynamic
 * fading edges signalling the off-screen rest (bottom fades while more remains below, top fades
 * once scrolled down). Content that fits is unaffected (no scroll, no fade).
 *
 * Scroll containment: when the content IS scrollable, the leftover delta of the inner scroll is
 * consumed so hitting the inner top/bottom never chains into scrolling the enclosing page.
 * When it fits, nothing is consumed — drags pass through to the page as plain content.
 */
@Composable
fun CappedScrollColumn(
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val blockChaining = remember(scroll) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (scroll.maxValue > 0) available else Offset.Zero
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if (scroll.maxValue > 0) available else Velocity.Zero
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            // The fade masks the scroll VIEWPORT (this node's size), not the full content —
            // it must sit above verticalScroll's clip in the modifier chain.
            .fadingEdgeVertical(
                height = 24.dp,
                fadeTop = { scroll.value > 0 },
                fadeBottom = { scroll.value < scroll.maxValue },
            )
            // Ancestor of the inner verticalScroll so it intercepts the leftover deltas the
            // inner scroll dispatches up towards the page scroll.
            .nestedScroll(blockChaining)
            .verticalScroll(scroll),
    ) {
        content()
    }
}
