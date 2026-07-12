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

/** Like [androidx.compose.animation.animateContentSize] but height-only — width snaps instantly so resize doesn't grow fillMaxWidth cards. */
fun Modifier.animateContentHeight(
    animationSpec: FiniteAnimationSpec<Int>? = null,
): Modifier = composed {
    val spec = animationSpec ?: appSpatialSpec<Int>()
    val scope = rememberCoroutineScope()
    // Seed lazily on first measure to the initial height (no grow-in from 0), matching animateContentSize.
    var animatable by remember { mutableStateOf<Animatable<Int, AnimationVector1D>?>(null) }
    clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val targetHeight = placeable.height
        val anim = animatable ?: Animatable(targetHeight, Int.VectorConverter).also { animatable = it }
        if (anim.targetValue != targetHeight) {
            scope.launch { anim.animateTo(targetHeight, spec) }
        }
        layout(placeable.width, anim.value) {
            placeable.place(0, 0)
        }
    }
}
