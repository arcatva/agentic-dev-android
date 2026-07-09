package dev.agentic.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/**
 * App motion tokens (see docs/superpowers/specs/2026-06-22-md3e-animation-refactor-design.md).
 * Rule of thumb: size/position -> appSpatialSpec(); alpha/color -> appEffectsSpec(). Use a literal-
 * duration tween with AppMotion.Emphasized only when a fixed duration is needed (crossfades that
 * mask I/O, the scrollbar alpha). Never a bare integer duration or the legacy FastOutSlowInEasing.
 */
object AppMotion {
    // Motion fully re-enabled (all instant durations restored to MD3 values).
    const val DurationShort4 = 200   // MD3 Short4  — scrollbar alpha                   [step 4]
    const val DurationMedium1 = 250  // MD3 Medium1 — session-switch content fade       [step 2]
    const val DurationNav = 300      // MD3 Medium2 — full-screen nav slide             [step 1]

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
