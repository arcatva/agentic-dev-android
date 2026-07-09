# MD3-Expressive Motion — Research & Refactor

Date: 2026-06-27
Repo: `agentic-dev-android`
Status: implemented (core); follow-ups listed

## Why

A long "回弹" (bounce/rebound) saga on session switching in the wide (foldable)
layout. Incremental guesses each fixed *a* source but the bounce kept coming back,
because there were **several independent causes** plus one global lever. This doc
captures the deep MD3-Expressive + Jetpack-Compose-animation research and the
resulting clean architecture so motion is coherent and overshoot-free.

## MD3-Expressive motion — the facts (from androidx source + m3.material.io)

- A **`MotionScheme`** is exactly **6 specs** = {**spatial**, **effects**} × {default, fast, slow}.
  - **Spatial** = size / position / shape / corners. In stock *expressive* these springs
    are **underdamped and DO overshoot** (damping 0.8 / 0.6 / 0.8, stiffness 380 / 800 / 200).
  - **Effects** = alpha / color / elevation. **Always critically damped (1.0), never overshoot**
    (stiffness 1600 / 3800 / 800) — identical in `standard()` and `expressive()`.
  - The *entire* "expressive vs standard" difference is **spatial damping** (expressive is bouncier).
- **Principle:** overshoot belongs to **geometry (spatial)**, never to **alpha/color (effects)** —
  enforced structurally by routing each animated property to the right spec family.
- **Spring vs duration-tween:** interruptible / gesture-driven motion → spring; time-deterministic
  enter/exit (nav, crossfade) → tween with an MD3 easing.
- **MD3 easings:** Emphasized `(0.2,0,0,1)`, Emphasized-Decelerate `(0.05,0.7,0.1,1)` (enters),
  Emphasized-Accelerate `(0.3,0,0.8,0.15)` (exits). Durations: Short 50–200, Medium 250–400,
  Long 450–600 ms.
- **Container transitions:** shared-axis (spatial+fade, related content), fade-through (no spatial,
  unrelated content), container-transform (element grows into a surface).

## Our deliberate deviation: NO overshoot

The user does not want **any** rebound. So we deviate from stock expressive in one place:
**we critically damp the SPATIAL springs too** (dampingRatio = 1.0). Everything else follows the
MD3 model. This is intentional and documented here so nobody "restores expressive" and reintroduces
the bounce.

## Architecture (single source of truth)

- **`ui/Theme.kt` → `AppMotionScheme`**: the one scheme every `appSpatialSpec()`/`appEffectsSpec()`
  and every M3 component reads. All six specs are **critically-damped springs** (NoBouncy):
  spatial stiffness 600 / 1400 / 280, effects 1600 / 3800 / 800. Tunable; never set damping < 1.0.
- **`ui/Motion.kt` → `AppMotion`**: duration tokens (Short4 200, Medium1 250, Nav 300) + the MD3
  `Emphasized` easing. Plus `appSpatialSpec()` / `appEffectsSpec()` helpers (read the scheme).
- **`ui/AnimateContentHeight.kt`**: height-only animate (width reported as measured = snaps), so an
  animated height can **never reflow neighbouring width**. Defaults to `appSpatialSpec()`.

## The bounce had THREE structural causes (all now removed, not just re-damped)

1. **Wide workflow rail** animated its **width** (`expandHorizontally`) → re-wrapped the chat every
   frame. **Fix:** rail is now **fade-only** (alpha; width snaps). → no reflow.
2. **Transcript auto-scroll** `animateScrollToItem(0)` slammed the bottom edge → platform
   stretch-overscroll **rebound**. **Fix:** instant `scrollToItem(0)` (no velocity → no overscroll).
3. **Underdamped spatial springs** (the scheme, and the adaptive lib's pane default) **overshoot**.
   **Fix:** critically-damped `AppMotionScheme`; `AnimatedPane.boundsAnimationSpec` overridden NoBouncy.
4. **(this change) `StatusIndicator`** glyph `scaleIn(spring(MediumBouncy))` — the last bouncy spring,
   hardcoded outside the scheme. **Fix:** `DampingRatioNoBouncy`.

## Site inventory (post-refactor)

| Area | Site | Spec | State |
|---|---|---|---|
| core | `AppMotionScheme` | NoBouncy springs | overshoot-free, load-bearing |
| core | `AppMotion` durations / `Emphasized` | tokens | source of truth |
| core | `AnimateContentHeight` | `appSpatialSpec()` | height-only, no reflow |
| nav | `AppNav` NavHost | `tween(Nav, Emphasized)` slide | OK (translationX only) |
| home | wide rail reveal | `fadeIn/Out(appEffectsSpec)` | fade-only, no width morph |
| home | wide chat swap | `Crossfade(tween Medium1)` | alpha only |
| home | `PaneBoundsSpec` (narrow) | NoBouncy spring + IntRect threshold | OK |
| session | transcript pin | instant `scrollToItem(0)` | no rebound |
| session | banners / ForkedHistoryCard / Collapsible | `appSpatialSpec` (NoBouncy) | OK |
| components | `StatusIndicator` glyph | **NoBouncy** (was MediumBouncy) | fixed here |

## Re-enable history (from the all-instant baseline)

The motion was fully disabled to a clean baseline, then re-added in verifiable steps:
1. nav slide (`DurationNav`), 2. session-switch fade (`DurationMedium1`),
3. component motion (critically-damped scheme) + 4. scrollbar alpha (`DurationShort4`).

## Follow-ups (coherence / "drift", not visible bugs)

- `LoginScreen` builds its own slide `AnimatedContent` — should reuse the shared nav spec.
- Consider adding `EmphasizedDecelerate`/`EmphasizedAccelerate` easings to `AppMotion` and using
  decelerate-on-enter / accelerate-on-exit for the nav + crossfade (currently all `Emphasized`).

(Done: `StatusIndicator`'s glyph pop now routes through hoisted `appSpatialSpec()`/`appEffectsSpec()`
instead of a hardcoded spring — one canonical source, critically damped.)

## Verification

Animations have no automated tests — compile + on-device visual check. Acceptance: switching
sessions (esp. into a session with the workflow rail) shows **no rebound** anywhere; transitions
feel smooth, never bouncy.
