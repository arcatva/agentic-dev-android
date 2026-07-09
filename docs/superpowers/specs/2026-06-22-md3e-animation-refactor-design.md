# MD3-Expressive Animation Refactor — Design

Date: 2026-06-22
Repo: `agentic-dev-android`
Status: Approved (design), pending implementation plan

## Goal

Two things, one coherent change:
1. The session-list search box (`DockedSearchBar`) expand/collapse feels too fast.
2. Bring the whole app's animations (speed, type, curve) in line with Material 3
   Expressive (MD3E) best practices, and stop the scattering of ad-hoc literal specs.

## Background (audit findings)

- 100% Jetpack Compose, `MaterialExpressiveTheme` + `MotionScheme.expressive()`
  (forced dark), `material3:1.4.0-alpha18`.
- ~35 distinct animation sites; **~75% already MD3E-conformant** because the theme is
  correctly wired. `WorkflowScreen.AgentRail` already reads `MaterialTheme.motionScheme`
  — the reference pattern.
- Real gaps: navigation uses the legacy Material-2 `FastOutSlowInEasing` curve; four
  `animateContentSize` sites use the generic default spring (not the expressive spatial
  spring); a few bare-literal tweens (220/200/400 ms) are not tokenized.
- The `DockedSearchBar` drives its expand/collapse **size** morph from the motion scheme
  (verified in bytecode: `SlowSpatial` on expand, `DefaultSpatial` on collapse). It has
  **no per-component speed parameter** and does not expose its internal durations. So the
  only clean lever to slow it is the app's `MotionScheme` itself.
- Verified expressive spring tokens (`ExpressiveMotionTokens`):

  | spatial | damping | stiffness | | effects | damping | stiffness |
  |---|---|---|---|---|---|---|
  | default | 0.8 | 380 | | default | 1.0 | 1600 |
  | fast | 0.6 | 800 | | fast | 1.0 | 3800 |
  | slow | 0.8 | 200 | | slow | 1.0 | 800 |

- `MotionScheme` exposes public `defaultSpatialSpec/fastSpatialSpec/slowSpatialSpec/
  defaultEffectsSpec/fastEffectsSpec/slowEffectsSpec`. `MotionTokens`/`ExpressiveMotionTokens`
  live in the restricted `androidx.compose.material3.tokens` package — do NOT import them;
  inline the values we need.

## Decisions

- Search-bar slowdown: **global** — customize the app's `MotionScheme` with slightly
  slower spatial springs. (Chosen over a custom search component or leaving it.)
- Scope: **full** — P0 + P1 + P2 from the audit.

## Design

### 1. Global motion: `AppMotionScheme` (in `ui/Theme.kt`)

A custom `MotionScheme` that is the expressive scheme with **softened spatial springs**
(lower stiffness = slower; damping unchanged so the bounce character is preserved).
Effects (alpha/color) are delegated to expressive unchanged.

```kotlin
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val AppMotionScheme: MotionScheme = object : MotionScheme {
    private val base = MotionScheme.expressive()
    // Spatial (position/size) — softened from expressive for a calmer feel.
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spring(0.8f, 250f) // expr 380
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spring(0.6f, 600f)    // expr 800
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spring(0.8f, 140f)    // expr 200
    // Effects (alpha/color) — unchanged from expressive.
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = base.defaultEffectsSpec()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = base.fastEffectsSpec()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = base.slowEffectsSpec()
}
```

- `spring(dampingRatio, stiffness)` is `androidx.compose.animation.core.spring`; returns
  `SpringSpec<T>` with default (null) visibility threshold — valid for IntSize/Dp/Color/Float.
- `Theme.kt` change: `motionScheme = MotionScheme.expressive()` → `motionScheme = AppMotionScheme`.
- Effect: the search bar's morph (reads the scheme) slows; all spring-driven motion app-wide
  becomes uniformly calmer.
- The stiffness numbers (250/600/140) are **starting values**; animation feel is visual and
  will be tuned on-device after the first build.

### 2. Centralization: `ui/Motion.kt` (new file)

Thin token layer — no parallel framework, no restricted-token imports.

```kotlin
package dev.agentic.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/** App motion tokens. Size/position → appSpatialSpec(); alpha/color → appEffectsSpec()
 *  or a duration-token tween with an emphasized easing. Never a bare integer or
 *  FastOutSlowInEasing (the legacy M2 curve). */
object AppMotion {
    const val DurationShort4  = 200   // micro fades (scrollbar alpha)
    const val DurationMedium1 = 250   // crossfades
    const val DurationNav     = 300   // full-screen nav slide (= MD3 Medium2)
    const val DurationMedium4 = 400   // color shifts

    // MD3 emphasized easings (inlined cubic-beziers; tokens package is restricted).
    val Emphasized           = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f) // enters
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f) // exits
}

@Composable @ReadOnlyComposable
fun <T> appSpatialSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultSpatialSpec()
@Composable @ReadOnlyComposable
fun <T> appEffectsSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultEffectsSpec()
```

(`MaterialTheme.motionScheme` requires `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`
on the helpers or file; follow the existing opt-in pattern in `Theme.kt`/`WorkflowScreen.kt`.)

### 3. Per-site changes

| File | Current | Recommended |
|---|---|---|
| `ui/nav/AppNav.kt` nav slide | `tween(300, FastOutSlowInEasing)` (M2 curve) | `tween(AppMotion.DurationNav, AppMotion.Emphasized)`; remove the local `navEasing`/`NAV_MOTION_MS` |
| `ui/session/Transcript.kt` `Collapsible` | `animateContentSize()` | `animateContentSize(appSpatialSpec())` |
| `ui/session/Transcript.kt` `SpawnCard` | `animateContentSize()` | `animateContentSize(appSpatialSpec())` |
| `ui/workflow/AgentTranscriptPane.kt` `TaskDisclosure` | `animateContentSize()` | `animateContentSize(appSpatialSpec())` |
| `ui/home/AdaptiveHome.kt` workflow rail | default enter/exit specs | `expandHorizontally(appSpatialSpec()) + fadeIn(appEffectsSpec())` / `shrinkHorizontally(appSpatialSpec()) + fadeOut(appEffectsSpec())` |
| `ui/home/AdaptiveHome.kt` session-switch Crossfade | `tween(220)` | `tween(AppMotion.DurationMedium1, AppMotion.Emphasized)` |
| `ui/newrequest/NewRequestScreen.kt` slider color | `animateColorAsState(tween(400))` | `animateColorAsState(appEffectsSpec())` |
| `ui/session/TranscriptScrollbar.kt` thumb alpha | `tween(200)` | `tween(AppMotion.DurationShort4, AppMotion.Emphasized)` |
| `ui/workflow/WorkflowScreen.kt` `AgentRail` | reads `motionScheme` directly | optional: route through `appSpatialSpec()`/`appEffectsSpec()` for one canonical call |

### 4. Explicitly NOT changed

- Implicit theme-driven M3 components (`LoadingIndicator`, `Circular/LinearWavyProgressIndicator`,
  `PullToRefreshBox`, `SwipeToDismissBox`, `Modifier.animateItem`, `ModalBottomSheet`,
  `ExtendedFloatingActionButton`, `DockedSearchBar` itself) — they inherit `AppMotionScheme`
  through the theme; changing them explicitly would be anti-YAGNI.
- `ui/session/SessionTags.kt` `UltracodeChip` infinite ripple (`tween(2000, LinearEasing)`) —
  intentional continuous effect.
- `ui/components/StatusIndicator.kt` glyph `AnimatedContent` — hand-tuned to dodge a documented
  GPU rasterization artifact (see in-code comment).

## Verification

- Animations have no automated tests; correctness is **compile + on-device visual check**.
- `:app:testDebugUnitTest` must stay green (compiles all changed UI + runs existing tests).
  Known pre-existing unrelated failure: `NewRequestViewModelTest` — out of scope.
- After the first signed APK, check on-device: search bar expand/collapse is noticeably
  calmer; nav transition uses emphasized easing; transcript cards / rail expand with the
  expressive spring; nothing feels sluggish. Tune the three spatial stiffness values if needed.

## Files touched

- New: `app/src/main/java/dev/agentic/ui/Motion.kt`
- Edit: `app/src/main/java/dev/agentic/ui/Theme.kt` (add `AppMotionScheme`, use it)
- Edit: `ui/nav/AppNav.kt`, `ui/session/Transcript.kt`, `ui/session/TranscriptScrollbar.kt`,
  `ui/workflow/AgentTranscriptPane.kt`, `ui/home/AdaptiveHome.kt`,
  `ui/newrequest/NewRequestScreen.kt`, (optional) `ui/workflow/WorkflowScreen.kt`

No new dependencies. No backend changes.

## Open questions

None blocking. The spatial spring stiffness values are starting points, tunable on-device.
