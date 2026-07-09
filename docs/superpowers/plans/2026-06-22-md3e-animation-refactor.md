# MD3E Animation Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Slow the session-list search bar and bring all app animations in line with MD3-Expressive by softening the global motion scheme and tokenizing the scattered ad-hoc specs.

**Architecture:** A custom `MotionScheme` (`AppMotionScheme`) with softened spatial springs replaces `MotionScheme.expressive()` in the theme — this alone slows the `DockedSearchBar` (its morph reads the scheme) and calms all spring motion. A thin `Motion.kt` token layer (`AppMotion` durations/easing + `appSpatialSpec()`/`appEffectsSpec()` helpers that read `MaterialTheme.motionScheme`) replaces literal `tween()`/legacy-easing/default-spring usages at the call sites.

**Tech Stack:** Kotlin, Jetpack Compose, `androidx.compose.material3:material3:1.4.0-alpha18` (Material 3 Expressive). No new dependencies.

## Global Constraints

- Compose Material3 is pinned to `1.4.0-alpha18`; do NOT bump it. `MotionScheme` and `MaterialTheme.motionScheme` require `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- Do NOT import anything from `androidx.compose.material3.tokens.*` (restricted package) — inline the emphasized cubic-bezier value instead.
- `tween(...)`'s second positional parameter is `delayMillis`, NOT easing. Always pass `easing = ...` by name.
- Animations have no automated tests; the gate is **`:app:compileDebugKotlin` BUILD SUCCESSFUL** + on-device visual check. Run Gradle via `~/.local/share/gradle-8.10.2/bin/gradle -p .` from the repo root (the worktree has a gitignored `local.properties` pointing at the SDK; create it if missing: `echo 'sdk.dir=/home/arcatva/Android/Sdk' > local.properties`).
- Do NOT build the APK in this worktree (no keystore). APK is built in the main checkout in Task 3.
- Spatial spring stiffness values (250/600/140) are starting points; do not change them — they're tuned on-device after Task 3.
- Leave untouched: `SessionTags.UltracodeChip` infinite ripple, `StatusIndicator` glyph `AnimatedContent`, and all implicit theme-driven M3 components (they inherit the new scheme).

---

### Task 1: Motion foundation — `Motion.kt` + custom scheme in `Theme.kt`

**Files:**
- Create: `app/src/main/java/dev/agentic/ui/Motion.kt`
- Modify: `app/src/main/java/dev/agentic/ui/Theme.kt`

**Interfaces:**
- Produces (used by Task 2):
  - `object AppMotion { const val DurationShort4=200; const val DurationMedium1=250; const val DurationNav=300; val Emphasized: CubicBezierEasing }`
  - `@Composable fun <T> appSpatialSpec(): FiniteAnimationSpec<T>`
  - `@Composable fun <T> appEffectsSpec(): FiniteAnimationSpec<T>`
- Consumes: nothing (foundation).

- [ ] **Step 1: Create `Motion.kt`**

Create `app/src/main/java/dev/agentic/ui/Motion.kt`:

```kotlin
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
    const val DurationShort4 = 200   // MD3 Short4  — micro fades (scrollbar alpha)
    const val DurationMedium1 = 250  // MD3 Medium1 — crossfades
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
```

- [ ] **Step 2: Add `AppMotionScheme` and its imports to `Theme.kt`**

In `app/src/main/java/dev/agentic/ui/Theme.kt`, add these two imports next to the existing `androidx.compose.animation`/`material3` imports (anywhere in the import block):

```kotlin
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
```

Then, immediately before the `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` line that precedes `fun AppTheme(`, insert:

```kotlin
/**
 * App motion scheme: the M3 Expressive scheme with softened SPATIAL springs (lower stiffness =
 * slower; damping unchanged so the expressive bounce character is preserved). Effects (alpha/color)
 * keep the expressive defaults. Swapping this in for MotionScheme.expressive() is what slows the
 * search-bar expand/collapse and calms all spring-driven motion app-wide. Stiffness values are
 * starting points — tune on-device.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val AppMotionScheme: MotionScheme = object : MotionScheme {
    private val base = MotionScheme.expressive()
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spring(0.8f, 250f) // expressive 380
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spring(0.6f, 600f)    // expressive 800
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spring(0.8f, 140f)    // expressive 200
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = base.defaultEffectsSpec()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = base.fastEffectsSpec()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = base.slowEffectsSpec()
}
```

(`spring(0.8f, 250f)` is `androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 250f)` — positional order is dampingRatio then stiffness.)

- [ ] **Step 3: Use the scheme in the theme**

In `Theme.kt`, inside `MaterialExpressiveTheme(...)`, change the `motionScheme` argument:

```kotlin
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = AppMotionScheme,
        shapes = ExpressiveShapes,
    ) {
```

(was `motionScheme = MotionScheme.expressive(),`)

- [ ] **Step 4: Verify it compiles**

```bash
cd /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/agentic-dev-android
[ -f local.properties ] || echo 'sdk.dir=/home/arcatva/Android/Sdk' > local.properties
~/.local/share/gradle-8.10.2/bin/gradle -p . :app:compileDebugKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`. (If `MotionScheme` interface method signatures don't match, re-check against the verified API: 6 methods `defaultSpatialSpec/fastSpatialSpec/slowSpatialSpec/defaultEffectsSpec/fastEffectsSpec/slowEffectsSpec`, each `<T> FiniteAnimationSpec<T>`.)

- [ ] **Step 5: Commit**

```bash
cd /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/agentic-dev-android
git add app/src/main/java/dev/agentic/ui/Motion.kt app/src/main/java/dev/agentic/ui/Theme.kt
git commit -m "feat(motion): add AppMotion tokens + softened AppMotionScheme"
```

---

### Task 2: Wire call sites to the motion layer

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/nav/AppNav.kt`
- Modify: `app/src/main/java/dev/agentic/ui/session/Transcript.kt`
- Modify: `app/src/main/java/dev/agentic/ui/session/TranscriptScrollbar.kt`
- Modify: `app/src/main/java/dev/agentic/ui/workflow/AgentTranscriptPane.kt`
- Modify: `app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt`
- Modify: `app/src/main/java/dev/agentic/ui/newrequest/NewRequestScreen.kt`

**Interfaces:**
- Consumes (from Task 1): `AppMotion.DurationShort4`, `AppMotion.DurationMedium1`, `AppMotion.DurationNav`, `AppMotion.Emphasized`, `appSpatialSpec()`, `appEffectsSpec()`.
- Produces: nothing for later tasks.

Each edit below is mechanical. All `appSpatialSpec()`/`appEffectsSpec()` calls are inside `@Composable` scope at these sites (verified).

- [ ] **Step 1: `AppNav.kt` — replace legacy easing**

Remove the import line:
```kotlin
import androidx.compose.animation.core.FastOutSlowInEasing
```
Add this import (with the other `dev.agentic` imports):
```kotlin
import dev.agentic.ui.AppMotion
```
Change the two constants (lines ~72–73):
```kotlin
private const val NAV_MOTION_MS = AppMotion.DurationNav
private val navEasing = AppMotion.Emphasized
```
(were `private const val NAV_MOTION_MS = 300` and `private val navEasing = FastOutSlowInEasing`. The four `tween(NAV_MOTION_MS, easing = navEasing)` call sites stay unchanged.)

- [ ] **Step 2: `Transcript.kt` — expressive spring for the two `animateContentSize`**

Add import:
```kotlin
import dev.agentic.ui.appSpatialSpec
```
There are exactly two identical occurrences of `Column(Modifier.animateContentSize()) {`. Replace BOTH with:
```kotlin
        Column(Modifier.animateContentSize(appSpatialSpec())) {
```
(Use a replace-all on the exact string `Column(Modifier.animateContentSize()) {` → `Column(Modifier.animateContentSize(appSpatialSpec())) {`.)

- [ ] **Step 3: `TranscriptScrollbar.kt` — tokenize the alpha tween**

Add import:
```kotlin
import dev.agentic.ui.AppMotion
```
Change the `animateFloatAsState` spec (line ~132):
```kotlin
        animationSpec = tween(durationMillis = AppMotion.DurationShort4, easing = AppMotion.Emphasized),
```
(was `animationSpec = tween(durationMillis = 200),`)

- [ ] **Step 4: `AgentTranscriptPane.kt` — expressive spring for `animateContentSize`**

Add import:
```kotlin
import dev.agentic.ui.appSpatialSpec
```
Change the single occurrence (line ~134):
```kotlin
    Column(Modifier.animateContentSize(appSpatialSpec())) {
```
(was `Column(Modifier.animateContentSize()) {`)

- [ ] **Step 5: `AdaptiveHome.kt` — rail transition + crossfade**

Add imports:
```kotlin
import dev.agentic.ui.AppMotion
import dev.agentic.ui.appSpatialSpec
import dev.agentic.ui.appEffectsSpec
```
Change the rail `AnimatedVisibility` enter/exit (lines ~228–229):
```kotlin
                        enter = expandHorizontally(appSpatialSpec(), expandFrom = Alignment.Start) + fadeIn(appEffectsSpec()),
                        exit = shrinkHorizontally(appSpatialSpec(), shrinkTowards = Alignment.Start) + fadeOut(appEffectsSpec()),
```
(were `expandHorizontally(expandFrom = Alignment.Start) + fadeIn()` / `shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut()`. `expandHorizontally`/`shrinkHorizontally` take the animation spec as their first positional `FiniteAnimationSpec<IntSize>`; `fadeIn`/`fadeOut` take `FiniteAnimationSpec<Float>`.)

Change the session-switch `Crossfade` spec (line ~309):
```kotlin
                            animationSpec = tween(durationMillis = AppMotion.DurationMedium1, easing = AppMotion.Emphasized),
```
(was `animationSpec = tween(220),`)

- [ ] **Step 6: `NewRequestScreen.kt` — effects spring for the slider colors**

Add import:
```kotlin
import dev.agentic.ui.appEffectsSpec
```
There are exactly two identical occurrences of `        animationSpec = tween(400),` (inside the two `animateColorAsState` calls). Replace BOTH with:
```kotlin
        animationSpec = appEffectsSpec(),
```
(Use a replace-all on `animationSpec = tween(400),` → `animationSpec = appEffectsSpec(),`.) If the build then warns that `import androidx.compose.animation.core.tween` is unused in this file, remove that import; if `tween` is still used elsewhere in the file, leave it.

- [ ] **Step 7: Verify everything compiles**

```bash
cd /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/agentic-dev-android
~/.local/share/gradle-8.10.2/bin/gradle -p . :app:compileDebugKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`. (If a `tween(...)` call complains about an `Int` where an `Easing` is expected, you passed easing positionally — name it `easing =`.)

- [ ] **Step 8: Commit**

```bash
cd /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/agentic-dev-android
git add app/src/main/java/dev/agentic/ui/nav/AppNav.kt \
        app/src/main/java/dev/agentic/ui/session/Transcript.kt \
        app/src/main/java/dev/agentic/ui/session/TranscriptScrollbar.kt \
        app/src/main/java/dev/agentic/ui/workflow/AgentTranscriptPane.kt \
        app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt \
        app/src/main/java/dev/agentic/ui/newrequest/NewRequestScreen.kt
git commit -m "refactor(motion): route nav/transcript/rail/slider animations through the motion layer"
```

---

### Task 3: Build, deliver, verify on-device

**Files:** none changed (build + deliver only).

- [ ] **Step 1: Push to master**

```bash
cd /home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/agentic-dev-android
git fetch -q origin master && git rebase origin/master
git push origin HEAD:master
```
Expected: push succeeds (rebase first since master may have advanced).

- [ ] **Step 2: Build the signed release APK in the main checkout**

```bash
cd ~/src/agentic-dev-android && git fetch -q origin && git checkout -q master && git pull --ff-only origin master
~/.local/share/gradle-8.10.2/bin/gradle assembleRelease --console=plain
```
Expected: `BUILD SUCCESSFUL`; APK at `~/src/agentic-dev-android/app/build/outputs/apk/release/app-release.apk`.

- [ ] **Step 3: Deliver to the outbox (build-time name)**

```bash
OUT=/home/arcatva/src/agentic-worktrees/39cacb91-f8ef-4508-bb32-3abe85770428/outbox
mkdir -p "$OUT"
cp ~/src/agentic-dev-android/app/build/outputs/apk/release/app-release.apk "$OUT/$(date +%Y%m%d-%H%M).apk"
ls -1t "$OUT"/*.apk | head -1
```
Expected: a `<YYYYMMDD-HHMM>.apk` in the outbox.

- [ ] **Step 4: Manual on-device verification (human)**

Install and check:
- **Search bar** expand/collapse is noticeably calmer than before (the main fix); collapse no longer feels snappier than expand.
- **Navigation** screen-to-screen slide uses the smoother emphasized curve (no abrupt M2 feel).
- **Transcript** tool/notes chips and spawn cards, and the **workflow rail** (wide layout) expand/collapse with the expressive spring.
- **New-request** slider color shift (sliding onto the ultracode notch) eases instead of snapping.
- Nothing feels sluggish (lists, FAB, swipe-to-delete still responsive). If the search bar / global feel is still off, tune the three stiffness values in `Theme.kt` `AppMotionScheme` (higher = faster) and rebuild.

---

## Self-Review

**Spec coverage** (against `docs/superpowers/specs/2026-06-22-md3e-animation-refactor-design.md`):
- §1 custom `AppMotionScheme` softened spatial springs + theme swap → Task 1.
- §2 `Motion.kt` token layer (`AppMotion` + `appSpatialSpec`/`appEffectsSpec`) → Task 1.
- §3 per-site changes: nav easing (T2 S1), 4× `animateContentSize` (T2 S2 ×2, S4) → Transcript×2 + AgentTranscriptPane; rail AnimatedVisibility (T2 S5); session crossfade (T2 S5); slider color (T2 S6); scrollbar alpha (T2 S3). All covered.
- §3 `WorkflowScreen.AgentRail` "optional canonicalization" → intentionally NOT changed: it already reads `MaterialTheme.motionScheme`, so it inherits `AppMotionScheme` automatically. Noted, no task — avoids churn.
- §4 leave-untouched list → respected (Global Constraints + no tasks touch them).
- §5 verification → Task 1/2 compile gates + Task 3 on-device checklist.
- Deviation from spec, intentional: `AppMotion` drops `DurationMedium4` and the decelerate/accelerate easings the spec listed — those were for the rejected search-bar-wrapper approach; nothing uses them now (YAGNI). The slider uses `appEffectsSpec()` (spec's first-listed option) rather than `tween(400)`.

**Placeholder scan:** none — every step has concrete code + commands + expected output.

**Type consistency:** `appSpatialSpec()`/`appEffectsSpec()`/`AppMotion.*` are defined in Task 1 and consumed with those exact names/signatures in Task 2. `AppMotionScheme` overrides the exact 6 `MotionScheme` methods (verified against the artifact). All `tween` calls pass `easing =` by name (avoids the `delayMillis` positional trap).
