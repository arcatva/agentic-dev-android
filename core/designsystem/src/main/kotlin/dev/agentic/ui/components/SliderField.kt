package dev.agentic.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import dev.agentic.ui.AccentViolet
import dev.agentic.ui.OnAccentVioletContainer
import dev.agentic.ui.appEffectsSpec
import dev.agentic.ui.drawUltracodeRipple
import dev.agentic.ui.rememberUltracodeRipplePhase
import kotlin.math.roundToInt

/**
 * MD3 Expressive discrete slider for a small ordered option set — snaps to each option; label shows
 * current pick above.
 */
// @OptIn: value-based full-slot Slider overload + SliderDefaults.Track are @ExperimentalMaterial3Api on 1.4.0-alpha18.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SliderField(
    label: String,
    options: List<Pair<String, String>>,
    value: String,
    onSelect: (String) -> Unit,
    // When true, label + slider recolor into workflow/ultracode violet (tween) AND the track
    // carries the looping ultracode ripple. Defaults off so non-accent sliders keep plain primary.
    accentActive: Boolean = false,
    // When true, label + slider recolor into theme error red (Permissions "Dangerous" notch).
    // Takes precedence over accentActive; tweened so sliding on/off animates instead of snapping.
    dangerActive: Boolean = false,
) {
    val idx = options.indexOfFirst { it.first == value }.coerceAtLeast(0)
    // Composed ONLY while accentActive so the looping animation never runs for non-accent sliders.
    // Shared with the session pill via UltracodeRipple; read (.value) INSIDE draw lambda = redraw-only.
    val ripplePhase = if (accentActive) rememberUltracodeRipplePhase() else null

    // Entry/exit recolor primary ⇆ accent violet ⇆ error red, tweened. Precedence: danger > accent > default.
    val trackColor by animateColorAsState(
        targetValue = when {
            dangerActive -> MaterialTheme.colorScheme.error
            accentActive -> AccentViolet
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = appEffectsSpec(),
        label = "sliderTrack",
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            dangerActive -> MaterialTheme.colorScheme.error
            accentActive -> AccentViolet
            else -> LocalContentColor.current
        },
        animationSpec = appEffectsSpec(),
        label = "sliderLabel",
    )
    Column {
        Text(
            "$label: ${options[idx].second}",
            style = MaterialTheme.typography.titleSmall,
            color = labelColor,
        )
        val sliderColors = SliderDefaults.colors(
            thumbColor = trackColor,
            activeTrackColor = trackColor,
        )
        Slider(
            value = idx.toFloat(),
            onValueChange = {
                onSelect(options[it.roundToInt().coerceIn(0, options.lastIndex)].first)
            },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = (options.size - 2).coerceAtLeast(0),
            colors = sliderColors,
            // Ripple drawn INSIDE A CUSTOM TRACK SLOT (not the whole Slider) so the draw surface
            // + clipToBounds = the thin ~16dp track box; bloom stays inside the visible progress
            // bar instead of flooding the ~44dp touch target. Non-accent sliders render the plain
            // default track unchanged.
            track = { state ->
                if (accentActive) {
                    SliderDefaults.Track(
                        sliderState = state,
                        modifier = Modifier
                            .clipToBounds()
                            .drawWithContent {
                                drawContent()
                                drawUltracodeRipple(
                                    ripplePhase?.value ?: 0f,
                                    color = OnAccentVioletContainer,
                                )
                            },
                        enabled = true,
                        colors = sliderColors,
                    )
                } else {
                    SliderDefaults.Track(
                        sliderState = state,
                        enabled = true,
                        colors = sliderColors,
                    )
                }
            },
        )
    }
}

/**
 * Continuous ("无极") slider for a raw 0..1 Float knob — capability/priority/cost in provider form.
 * Unlike [SliderField] (snaps), this passes `steps = 0` so the thumb moves freely; ANY value in
 * [valueRange] is selectable. Label shows live value to two decimals.
 *
 * [value] is a lambda so the state read is DEFERRED: caller passes `{ form.capability }` and the
 * read happens INSIDE [value]`()` here — subscribing only THIS scope. Raw-Float arg would read in
 * the caller's scope and recompose the whole parent form on every drag frame; the lambda limits
 * per-frame recomposition to this slider.
 *
 * Uses the classic value-based [Slider] overload WITHOUT a custom thumb/track slot — the stable
 * (non-experimental) one — so unlike [SliderField], this needs no @OptIn.
 */
@Composable
fun FloatSliderField(
    label: String,
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    val current = value()
    Column {
        Text(
            "$label: ${"%.2f".format(current)}",
            style = MaterialTheme.typography.titleSmall,
        )
        Slider(
            value = current.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = 0, // continuous — no notch snapping (无极调节)
        )
    }
}
