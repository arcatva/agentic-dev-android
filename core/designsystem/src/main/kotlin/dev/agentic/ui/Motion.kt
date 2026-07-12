package dev.agentic.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/**
 * App motion tokens. size/position → appSpatialSpec(); alpha/color → appEffectsSpec(). Never a
 * bare integer duration or the legacy FastOutSlowInEasing.
 */
object AppMotion {
    // Motion fully re-enabled (all instant durations restored to MD3 values).
    const val DurationShort4 = 200   // MD3 Short4  — scrollbar alpha
    const val DurationMedium1 = 250  // MD3 Medium1 — session-switch content fade
    const val DurationNav = 300      // MD3 Medium2 — full-screen nav slide

    /** MD3 emphasized easing (0.2, 0, 0, 1) — replaces the legacy M2 FastOutSlowInEasing. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/** Spatial (size/position) spec from the app motion scheme. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@ReadOnlyComposable
fun <T> appSpatialSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultSpatialSpec()

/** Effects (alpha/color) spec from the app motion scheme. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@ReadOnlyComposable
fun <T> appEffectsSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultEffectsSpec()
