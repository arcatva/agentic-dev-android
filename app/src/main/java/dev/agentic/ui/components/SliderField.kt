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
 * MD3 Expressive discrete slider for a small ordered option set.
 * Snaps to each option; label shows current pick above the slider.
 */
// @OptIn: the accent path uses the value-based full-slot Slider overload (the one with a `track` slot)
// and SliderDefaults.Track, both @ExperimentalMaterial3Api in material3 1.4.0-alpha18. Without this the
// `track =` argument binds to the experimental overload and fails to compile.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SliderField(
    label: String,
    options: List<Pair<String, String>>,
    value: String,
    onSelect: (String) -> Unit,
    // When true, the label + slider recolor into the workflow/ultracode violet (a smooth tween on
    // entry/exit) AND the track carries the looping ultracode ripple — the same wave as the session
    // pill. Defaults off so other sliders (e.g. the Model slider) keep the plain primary look and run
    // no animation.
    accentActive: Boolean = false,
    // When true, the label + slider recolor into the theme error red to flag a dangerous selection
    // (the Permissions "Dangerous" notch). Takes precedence over accentActive, and is tweened the same
    // way so sliding onto/off the notch animates instead of snapping. Defaults off.
    dangerActive: Boolean = false,
) {
    val idx = options.indexOfFirst { it.first == value }.coerceAtLeast(0)
    // Ripple clock — composed ONLY while accentActive, so the looping animation never runs for the
    // non-accent sliders. Shared with the session pill via UltracodeRipple. It is a State<Float> read
    // (.value) inside the draw lambda below, so frames are a redraw, not a recomposition of SliderField.
    val ripplePhase = if (accentActive) rememberUltracodeRipplePhase() else null

    // Entry/exit recolor: primary (blue) ⇆ accent violet ⇆ error red, tweened so sliding on/off a notch
    // animates the color change instead of snapping. animateColorAsState only animates during the
    // transition and then settles, so it is free at rest. Precedence: danger (red) > accent (violet) >
    // default. When neither flag is set these resolve to the prior look (default primary track /
    // inherited content color for the label), leaving plain sliders — e.g. the Model slider — unchanged.
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
            // While ultracode is selected, the same ripple as the session pill travels across the
            // slider. It is drawn INSIDE A CUSTOM TRACK SLOT (not on the whole Slider) so the draw
            // surface — and clipToBounds — are the thin ~16dp track box, keeping the bloom inside the
            // visible progress bar instead of flooding the ~44dp touch target (the thumb, not the track,
            // is what makes the Slider 44dp tall). The overlay is the default track's OWN modifier, which
            // sits outside Track's internal fillMaxWidth().height(TrackHeight) chain, so it sizes/clips to
            // the track. Non-accent sliders (Model, Permissions) render the plain default track unchanged.
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
 * Continuous ("无极") slider for a raw 0..1 Float knob — capability / priority / cost in the
 * provider form. Unlike [SliderField] (which snaps to a discrete option set), this passes
 * `steps = 0`, so the thumb moves freely and ANY value in [valueRange] is selectable — no notch
 * snapping. The label shows the live value to two decimals.
 *
 * [value] is a lambda, not a raw Float, so the state read is DEFERRED into this composable: the
 * caller passes `{ form.capability }` and the `form.capability` read happens inside [value]`()`
 * here — subscribing only THIS scope. A raw-Float arg would read the state in the caller's scope
 * instead, recomposing the whole parent form on every drag frame; the lambda keeps per-frame
 * recomposition to just this small slider.
 *
 * Uses the classic value-based [Slider] overload WITHOUT a custom thumb/track slot, which is the
 * stable (non-experimental) one — so, unlike [SliderField], this needs no @OptIn.
 */
@Composable
internal fun FloatSliderField(
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
