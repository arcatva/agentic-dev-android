package dev.agentic.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.agentic.domain.DONE_STATES
import dev.agentic.domain.FAILED_STATES
import dev.agentic.domain.KILLED_STATES
import dev.agentic.domain.RUNNING_STATES
import dev.agentic.domain.StatusVisual
import dev.agentic.domain.statusVisual
import dev.agentic.ui.appEffectsSpec
import dev.agentic.ui.appSpatialSpec
import kotlinx.coroutines.delay

// Footprint fixed at [size] across all states (AnimatedContent size) so surrounding row never
// reflows on transition, including blank done/idle. Glyphs sized by fill ratio so they look
// the same size: pending dot 100%, status icon ~84%, M3 Expressive circular wavy spinner SPINNER_FILL.
private const val TARGET_FILL = 0.70f
private const val ICON_FILL = 0.84f
private const val SPINNER_FILL = 0.7f
private const val CHECK_HOLD_MS = 2000L
private enum class Glyph { SPINNER, CHECK, DOT, FAILED, KILLED, NONE }

private fun StatusVisual.steadyGlyph(unread: Boolean = false): Glyph = when (this) {
    StatusVisual.RUNNING -> Glyph.SPINNER
    StatusVisual.PENDING -> Glyph.DOT
    StatusVisual.FAILED -> Glyph.FAILED
    StatusVisual.KILLED -> Glyph.KILLED
    StatusVisual.DONE, StatusVisual.IDLE -> if (unread) Glyph.DOT else Glyph.NONE
}

/** Accent for raw backend [status] — fully from MD3E ColorScheme (no dynamic color, no hex):
 *  tertiary for running (accent override violet in workflows), primary for done, error, secondary for killed,
 *  outline otherwise. */
@Composable
fun statusColor(status: String, accent: Color? = null): Color = when (status.trim().lowercase()) {
    in RUNNING_STATES -> accent ?: MaterialTheme.colorScheme.tertiary
    in DONE_STATES    -> accent ?: MaterialTheme.colorScheme.primary
    in FAILED_STATES  -> MaterialTheme.colorScheme.error
    in KILLED_STATES  -> MaterialTheme.colorScheme.secondary
    else              -> MaterialTheme.colorScheme.outline
}

/**
 * MD3 Expressive status indicator. State → visual driven by [statusVisual] (single source of truth).
 *   running              → CircularWavyProgressIndicator
 *   running → idle/done  → spinner gives way to a check that pops in, holds ~2s, then shrinks away
 *   idle / done at rest  → nothing
 *   failed/killed        → filled state icon
 *   else (pending/…)     → static dot
 * Footprint fixed at [size] so no reflow on transition.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusIndicator(
    status: String,
    awaitingInput: Boolean? = null,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    accent: Color? = null,
    unread: Boolean = false,
    flashOnComplete: Boolean = true,
) {
    val visual = statusVisual(status, awaitingInput)
    val color = statusColor(status, accent)
    val density = LocalDensity.current

    // Transient completion check: flash a check for CHECK_HOLD_MS on running→idle/done, then clear.
    // flashOnComplete gates the flash for home-list call sites whose data stream can CATCH UP on old
    // transitions while backgrounded (Discord-style unread: genuinely new completion flashes; ack'd
    // stays quiet). Live-view sites keep the default.
    var checking by remember { mutableStateOf(false) }
    var prev by remember { mutableStateOf(visual) }
    LaunchedEffect(visual) {
        val was = prev
        prev = visual
        when {
            was == StatusVisual.RUNNING && (visual == StatusVisual.IDLE || visual == StatusVisual.DONE) -> {
                if (flashOnComplete) {
                    checking = true
                    delay(CHECK_HOLD_MS)
                    checking = false
                }
            }
            visual == StatusVisual.RUNNING -> checking = false
        }
    }

    val glyph = if (checking) Glyph.CHECK else visual.steadyGlyph(unread)

    // Route through central motion scheme — critically damped (no-overshoot policy); spatial drives
    // the scale, effects the fade. Hoisted because transitionSpec isn't a @Composable scope.
    val popScaleSpec = appSpatialSpec<Float>()
    val popFadeSpec = appEffectsSpec<Float>()
    AnimatedContent(
        targetState = glyph,
        transitionSpec = {
            (fadeIn(popFadeSpec) +
                scaleIn(initialScale = 0.6f, animationSpec = popScaleSpec)) togetherWith (
                // Exit = fade-led. CheckCircle is a FILLED disc; scaling it to 0 passes through
                // sub-pixel sizes that the GPU rasterizes as a tiny square blob ("square layer"
                // artifact on disappear). Fade fully out early (~200ms) while only shrinking to 0.6.
                fadeOut(tween(durationMillis = 200)) +
                    scaleOut(targetScale = 0.6f, animationSpec = tween(durationMillis = 320))
                )
        },
        contentAlignment = Alignment.Center,
        label = "status",
        modifier = modifier.size(size),
    ) { g ->
        // requiredSize so AnimatedContent doesn't coerce glyphs back up to fixed footprint.
        val dotBox = size * TARGET_FILL
        val iconBox = size * (TARGET_FILL / ICON_FILL)
        val spinnerBox = size * SPINNER_FILL
        when (g) {
            Glyph.SPINNER -> {
                // One stroke for BOTH arc + track — track's default is the library's thicker circular
                // weight, so without this the wave thinned but the track stayed chunky.
                val ringStroke = Stroke(width = with(density) { 2.dp.toPx() }, cap = StrokeCap.Round)
                CircularWavyProgressIndicator(
                    modifier = Modifier.requiredSize(spinnerBox),
                    color = color,
                    stroke = ringStroke,
                    trackStroke = ringStroke,
                )
            }
            Glyph.CHECK -> Icon(Icons.Rounded.CheckCircle, "done", tint = color, modifier = Modifier.requiredSize(iconBox))
            Glyph.DOT -> Box(Modifier.requiredSize(dotBox).clip(CircleShape).background(color))
            Glyph.FAILED -> Icon(Icons.Rounded.Error, "failed", tint = color, modifier = Modifier.requiredSize(iconBox))
            Glyph.KILLED -> Icon(Icons.Rounded.StopCircle, "killed", tint = color, modifier = Modifier.requiredSize(iconBox))
            Glyph.NONE -> Unit
        }
    }
}

@Preview
@Composable
private fun StatusIndicatorUnreadPreview() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusIndicator(status = "done", unread = true)
        StatusIndicator(status = "done", unread = false)
        StatusIndicator(status = "running")
        StatusIndicator(status = "failed")
    }
}
