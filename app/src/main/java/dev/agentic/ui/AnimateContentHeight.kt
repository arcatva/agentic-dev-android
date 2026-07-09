package dev.agentic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.launch

/**
 * Like [androidx.compose.animation.animateContentSize] but animates ONLY the height. The width is
 * reported as the child's measured width on every frame, so a full-width card snaps to the new width
 * instantly when the window is resized — it never visibly grows from short to long. Height changes
 * (expand/collapse, text streaming in) still animate smoothly.
 *
 * Why not `animateContentSize`: it animates the whole `IntSize`, so on a live width change (a window
 * resize / unfold / split-screen drag) a `fillMaxWidth` card animates its WIDTH too, which reads as
 * inconsistent next to the cards that snap. This keeps the smooth height feel without the width grow.
 *
 * The height animation defaults to the app motion layer's spatial spec ([appSpatialSpec]) so it
 * matches the rest of the app's spring feel; pass [animationSpec] to override.
 */
fun Modifier.animateContentHeight(
    animationSpec: FiniteAnimationSpec<Int>? = null,
): Modifier = composed {
    val spec = animationSpec ?: appSpatialSpec<Int>()
    val scope = rememberCoroutineScope()
    // Created lazily on the first measure so it seeds to the initial height — no grow-in from 0 when
    // the card first appears (matches animateContentSize, which snaps on first composition).
    var animatable by remember { mutableStateOf<Animatable<Int, AnimationVector1D>?>(null) }
    clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val targetHeight = placeable.height
        val anim = animatable ?: Animatable(targetHeight, Int.VectorConverter).also { animatable = it }
        if (anim.targetValue != targetHeight) {
            scope.launch { anim.animateTo(targetHeight, spec) }
        }
        // Report the child's real width (snaps instantly) but the animated height.
        layout(placeable.width, anim.value) {
            placeable.place(0, 0)
        }
    }
}
