package dev.agentic.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext

/**
 * The shared "ultracode" ripple — one place so the session pill ([dev.agentic.ui.session.UltracodeChip])
 * and the New request Effort slider animate identically and never drift apart.
 *
 * Two pieces:
 *  - [rememberUltracodeRipplePhase] gives the 0f→1f spawn clock (honouring OS reduce-motion).
 *  - [DrawScope.drawUltracodeRipple] paints the wave from that clock onto whatever surface you call it
 *    on (the pill's violet container, or the slider's violet track).
 */

/**
 * The looping ripple clock, as a [State]<Float> that ticks 0f→1f every 2s (linear, restart).
 *
 * Returns the STATE (not the Float) on purpose: callers must read `.value` INSIDE their draw lambda, so
 * the snapshot read lands in the draw phase and each animation frame triggers a redraw only — NOT a
 * recomposition of the caller (the pill / the SliderField). Reading the value out here instead would
 * recompose the caller ~60×/s. Compose infinite transitions ignore the OS "Remove animations" setting on
 * their own, so when motion is off this is a static mid-phase glow (0.5f), no animation.
 */
@Composable
fun rememberUltracodeRipplePhase(): State<Float> {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    if (reduceMotion) return remember { mutableStateOf(0.5f) }
    val transition = rememberInfiniteTransition(label = "ultracode")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "ripple",
    )
}

/**
 * Paint the ultracode ripple at clock [phase] (from [rememberUltracodeRipplePhase]).
 *
 * A highlight in [color] blooms from the centre out to the edges; a second wave trails the first by a
 * cycle so the two briefly overlap near the edge (the wave travels ~2.5s end to end: 0.8 of the way in
 * one 2s cycle, the last 0.2 during the next). Drawn with [BlendMode.Plus] so it BRIGHTENS whatever is
 * underneath rather than painting over it — the violet container on the pill, the violet active track on
 * the slider. [maxAlpha] is the peak highlight opacity; [reach] scales how far the wave spreads relative
 * to the surface's longest side.
 *
 * Caller is responsible for clipping (the pill clips to its shape; the slider draws this inside a custom
 * Slider track slot and clips to it with clipToBounds, so the ~16dp track bounds the bloom rather than the
 * taller ~44dp touch target) — the bloom can otherwise extend past the surface bounds.
 */
fun DrawScope.drawUltracodeRipple(
    phase: Float,
    color: Color,
    maxAlpha: Float = 0.5f,
    reach: Float = 0.62f,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val maxR = size.maxDimension * reach
    fun ripple(p: Float) {
        val r = (p * maxR).coerceAtLeast(0.01f)
        // Brighten quickly after the wave appears (so the next one shows up sooner), hold, then fade
        // only as it nears the edge.
        val fadeIn = (p / 0.1f).coerceIn(0f, 1f)
        val fadeOut = ((1f - p) / 0.2f).coerceIn(0f, 1f)
        val alpha = fadeIn * fadeOut * maxAlpha
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                center = center,
                radius = r,
            ),
            radius = r,
            center = center,
            blendMode = BlendMode.Plus,
        )
    }
    // Current wave travels phase 0→0.8 this cycle; the previous wave finishes its 0.8→1.0 stretch,
    // overlapping near the edge as the new one appears at the centre.
    ripple(phase * 0.8f)
    val prev = phase * 0.8f + 0.8f
    if (prev <= 1f) ripple(prev)
}
