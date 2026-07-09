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

// The footprint is fixed at [size] for every state (the AnimatedContent is size(size)), so the
// surrounding row never reflows on a transition — including the blank done/idle states. The painted
// glyphs are sized by their fill ratio so they look the same size:
//   pending dot → fills 100% of its box → box = dotBox
//   status icon → check/failed/killed filled icons paint ~0.84 of their box → box = dotBox / 0.84
//   running     → the M3 Expressive circular wavy spinner, sized to SPINNER_FILL of the footprint
//   idle / done → nothing at rest (a transient completion check is the only thing that ever shows)
/** Visible glyph size as a fraction of the footprint. */
private const val TARGET_FILL = 0.70f
private const val ICON_FILL = 0.84f
/** The running wavy spinner's diameter as a fraction of the footprint — smaller than the full box so
 *  the ring isn't oversized next to the dot/icon glyphs. */
private const val SPINNER_FILL = 0.7f

/** How long the transient completion check holds before it shrinks away. */
private const val CHECK_HOLD_MS = 2000L

/** What the indicator actually draws — decouples the transient completion check from [StatusVisual]. */
private enum class Glyph { SPINNER, CHECK, DOT, FAILED, KILLED, NONE }

/** The glyph shown at rest for a visual. Idle and done are blank — completion is shown transiently. */
private fun StatusVisual.steadyGlyph(unread: Boolean = false): Glyph = when (this) {
    StatusVisual.RUNNING -> Glyph.SPINNER
    StatusVisual.PENDING -> Glyph.DOT
    StatusVisual.FAILED -> Glyph.FAILED
    StatusVisual.KILLED -> Glyph.KILLED
    StatusVisual.DONE, StatusVisual.IDLE -> if (unread) Glyph.DOT else Glyph.NONE
}

/**
 * Accent color for a raw backend [status] string — sourced entirely from the app's MD3E
 * ColorScheme (no device dynamic color, no hardcoded hex) so status dots stay on-brand: the teal
 * accent for live work, brand blue for done, semantic red for failures, muted tones otherwise.
 */
@Composable
fun statusColor(status: String, accent: Color? = null): Color = when (status.trim().lowercase()) {
    in RUNNING_STATES -> accent ?: MaterialTheme.colorScheme.tertiary  // live spinner — accent override (violet in workflows)
    in DONE_STATES    -> accent ?: MaterialTheme.colorScheme.primary   // completed OK — accent (violet) in workflows, else brand blue
    in FAILED_STATES  -> MaterialTheme.colorScheme.error      // semantic red
    in KILLED_STATES  -> MaterialTheme.colorScheme.secondary  // user-stopped / cancelled — muted
    else              -> MaterialTheme.colorScheme.outline    // pending / unknown
}

/**
 * MD3 Expressive status indicator. Driven by [dev.agentic.domain.statusVisual] so the domain
 * enum is the single source of truth for status → visual mapping.
 *
 * - running              → CircularWavyProgressIndicator (Expressive circular spinner)
 * - running → idle/done  → the spinner gives way to a check that pops in, holds ~2s, then shrinks
 *                          away — a transient "turn finished" confirmation
 * - idle / done (at rest)→ nothing
 * - failed/killed        → filled state icon
 * - else (pending/…)     → static dot
 *
 * Footprint is fixed at [size] across all states so no reflow on transition.
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

    // Transient completion check: the moment a running turn finishes (running → idle or done) flash a
    // check for CHECK_HOLD_MS, then clear it so it shrinks away. At rest, idle/done show nothing.
    //
    // flashOnComplete gates the flash for call sites whose data stream can CATCH UP on old
    // transitions: the home list's uiState freezes while the app is backgrounded (WhileSubscribed),
    // and the composition is retained — so on resume the first fresh poll replays a running→idle
    // flip that actually happened minutes ago, flashing a completion the user already opened and
    // read. The list passes flashOnComplete = unread (Discord-style server read state): a genuinely
    // new completion still flashes (the same poll tick that flips the visual also sets unread=true),
    // while an already-acked one stays quiet. Live-view call sites keep the default (always flash).
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
            visual == StatusVisual.RUNNING -> checking = false // a fresh turn cancels a lingering check
        }
    }

    val glyph = if (checking) Glyph.CHECK else visual.steadyGlyph(unread)

    // Route the glyph pop through the central motion scheme — no hardcoded spring, one source of
    // truth. Both specs are critically damped (the app-wide no-overshoot policy): spatial drives the
    // scale, effects the fade. This scaleIn used to be the LAST bouncy spring in the app (the residual
    // "回弹" on the status glyph); the scheme's NoBouncy spatial spec keeps a lively pop without overshoot.
    // Hoisted out of transitionSpec because that lambda is not a @Composable scope.
    val popScaleSpec = appSpatialSpec<Float>()
    val popFadeSpec = appEffectsSpec<Float>()
    AnimatedContent(
        targetState = glyph,
        transitionSpec = {
            // Pop in (scale + fade); shrink + fade on exit — so the spinner gives way to a check
            // that then shrinks out.
            (fadeIn(popFadeSpec) +
                scaleIn(initialScale = 0.6f, animationSpec = popScaleSpec)) togetherWith (
                // Exit = fade-led, gentle shrink. The check is a FILLED disc (CheckCircle); scaling it
                // all the way to 0 makes it pass through sub-pixel sizes that the GPU rasterizes as a
                // tiny square blob — the "square layer" artifact on disappear. So we fade it fully out
                // early (~200ms) while only shrinking to 0.6f: it is already invisible long before it
                // reaches the size where the square shows, and it still reads as a soft "shrink away".
                fadeOut(tween(durationMillis = 200)) +
                    scaleOut(targetScale = 0.6f, animationSpec = tween(durationMillis = 320))
                )
        },
        contentAlignment = Alignment.Center,
        label = "status",
        modifier = modifier.size(size),
    ) { g ->
        // Every painted glyph uses requiredSize so AnimatedContent doesn't coerce it back up to the
        // fixed footprint constraints (modifier.size(size) above) — the spinner included, or its
        // spinnerBox is ignored and it fills the full footprint. idle/done (NONE) paint nothing.
        val dotBox = size * TARGET_FILL
        val iconBox = size * (TARGET_FILL / ICON_FILL)
        val spinnerBox = size * SPINNER_FILL
        when (g) {
            Glyph.SPINNER -> {
                // One stroke for BOTH the wavy active arc and the flat background track. The track
                // (trackStroke) defaults to the library's thicker circular weight, so without this it
                // stays chunky even after the wave is thinned — the "non-wave circle looks thick" case.
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
        StatusIndicator(status = "done", unread = true)    // unread dot
        StatusIndicator(status = "done", unread = false)   // blank (read)
        StatusIndicator(status = "running")                // spinner
        StatusIndicator(status = "failed")                 // error icon
    }
}
