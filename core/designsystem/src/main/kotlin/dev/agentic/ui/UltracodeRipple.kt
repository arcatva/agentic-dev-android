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
 * The shared "ultracode" ripple — one place so the session pill and the New request Effort slider
 * animate identically. Two pieces: [rememberUltracodeRipplePhase] gives the 0f→1f spawn clock
 * (honouring OS reduce-motion); [DrawScope.drawUltracodeRipple] paints the wave from that clock.
 */

/**
 * Looping ripple clock, a [State]<Float> ticking 0f→1f every 2s (linear, restart). Returns the STATE
 * on purpose so callers read `.value` INSIDE their draw lambda → draw-phase snapshot, redraw-only
 * (no recompose of the pill / slider). Compose infinite transitions ignore OS "Remove animations",
 * so when motion is off this is a static mid-phase glow (0.5f), no animation.
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
 * Paint the ultracode ripple at clock [phase] (from [rememberUltracodeRipplePhase]). A highlight
 * in [color] blooms from centre out, a second wave trails by a cycle so they briefly overlap near
 * the edge. Drawn with [BlendMode.Plus] to BRIGHTEN whatever sits underneath. Caller is responsible
 * for clipping (the pill clips to its shape; the slider clips to the ~16dp track with clipToBounds).
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
    // Current wave travels phase 0→0.8 this cycle; previous finishes 0.8→1.0, overlapping at the edge.
    ripple(phase * 0.8f)
    val prev = phase * 0.8f + 0.8f
    if (prev <= 1f) ripple(prev)
}
