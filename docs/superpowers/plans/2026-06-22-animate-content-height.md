# Height-Only Card Animation (`animateContentHeight`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The transcript cards that currently grow "short→long" in width when the window is resized (Notes/tool/thinking `Collapsible`, agent `SpawnCard`, and the workflow `TaskDisclosure`) instead snap to full width instantly — like every other card — while keeping their smooth expand/collapse and streaming height animation.

**Architecture:** Those three composables wrap their content in `Column(Modifier.animateContentSize(appSpatialSpec()))`. `animateContentSize` animates the whole `IntSize`, so on a live width change (window resize / unfold / split-screen drag) a `fillMaxWidth` card animates its WIDTH too — the "short→long" grow the user sees. Replace it with a new height-only modifier `Modifier.animateContentHeight()` that reports the child's measured width every frame (width snaps) and animates only the height. The height animation defaults to the app motion layer's `appSpatialSpec()` (added by the MD3E motion refactor on master, `dev.agentic.ui.Motion`) so the spring feel still matches the rest of the app. One new shared file in `dev.agentic.ui`, used from `ui/session/Transcript.kt` and `ui/workflow/AgentTranscriptPane.kt`.

**Tech Stack:** Kotlin, Jetpack Compose (`Modifier.composed`, `Modifier.layout`, `androidx.compose.animation.core.Animatable`, `dev.agentic.ui.appSpatialSpec`).

## Global Constraints

- Compose Material3 is pinned to `1.4.0-alpha18` — do not bump it.
- Do NOT build in the worktree (no Gradle wrapper / keystore). Build in `~/src/agentic-dev-android` after pushing to master (see repo CLAUDE.md). The `assembleRelease` compile (incl. `lintVitalRelease`) is the compile check.
- The project has NO Compose UI test harness. Verification is read-back + the successful release build + a manual APK visual check. Do not stand up a UI-test harness.
- This change sits ON TOP of master's MD3E motion refactor (`Motion.kt`: `appSpatialSpec<T>(): FiniteAnimationSpec<T>` = `MaterialTheme.motionScheme.defaultSpatialSpec()`, backed by `spring(0.8f, 250f)`; it is `@Composable @ReadOnlyComposable`). The new modifier must route its height spring through `appSpatialSpec()`, NOT a bare `spring(...)` — the motion refactor's rule is "size/position → appSpatialSpec()".
- Scope: only the three cards that use `animateContentSize(appSpatialSpec())` — `Collapsible` and `SpawnCard` in `Transcript.kt`, `TaskDisclosure` in `AgentTranscriptPane.kt`. Do NOT touch `WorkflowScreen.kt` (`AgentCard` in the side rail — different purpose, out of scope).
- Preserve exactly: collapsed card = header-height only; expand/collapse and streaming text still animate the height smoothly; only WIDTH stops animating.

---

### Task 1: Add `animateContentHeight` (motion-integrated) and switch the three cards

**Files:**
- Create: `app/src/main/java/dev/agentic/ui/AnimateContentHeight.kt`
- Modify: `app/src/main/java/dev/agentic/ui/session/Transcript.kt` (imports lines 3–4; `Collapsible` comment + Column; `SpawnCard` docstring + Column)
- Modify: `app/src/main/java/dev/agentic/ui/workflow/AgentTranscriptPane.kt` (imports lines 3–4; `TaskDisclosure` Column)

**Interfaces:**
- Produces: `fun Modifier.animateContentHeight(animationSpec: FiniteAnimationSpec<Int>? = null): Modifier` in package `dev.agentic.ui`. Height-only drop-in for `animateContentSize`; defaults its height spring to `appSpatialSpec<Int>()` (same package, no import).
- Consumes: `dev.agentic.ui.appSpatialSpec` (same package).

- [ ] **Step 1: Create the modifier** — `app/src/main/java/dev/agentic/ui/AnimateContentHeight.kt`:

```kotlin
package dev.agentic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.launch

/**
 * Like [androidx.compose.animation.animateContentSize] but animates ONLY the height. Width is
 * reported as the child's measured width every frame, so a full-width card snaps to the new width
 * instantly on a window resize — it never grows short→long. Height changes (expand/collapse,
 * streaming text) still animate smoothly. Height spring defaults to the motion layer's
 * [appSpatialSpec] so the feel matches the rest of the app; pass [animationSpec] to override.
 */
fun Modifier.animateContentHeight(
    animationSpec: FiniteAnimationSpec<Int>? = null,
): Modifier = composed {
    val spec = animationSpec ?: appSpatialSpec<Int>()
    val scope = rememberCoroutineScope()
    // Lazily seeded on first measure to the initial height — no grow-in from 0 when the card appears.
    var animatable by remember { mutableStateOf<Animatable<Int, AnimationVector1D>?>(null) }
    clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val targetHeight = placeable.height
        val anim = animatable ?: Animatable(targetHeight, Int.VectorConverter).also { animatable = it }
        if (anim.targetValue != targetHeight) {
            scope.launch { anim.animateTo(targetHeight, spec) }
        }
        layout(placeable.width, anim.value) { placeable.place(0, 0) }
    }
}
```

- [ ] **Step 2: `Transcript.kt` imports** — replace the two lines (3–4):

```kotlin
import androidx.compose.animation.animateContentSize
import dev.agentic.ui.appSpatialSpec
```
with:
```kotlin
import dev.agentic.ui.animateContentHeight
```

- [ ] **Step 3: `Collapsible` — comment + Column.** Replace the comment:

```kotlin
    // animateContentSize (not AnimatedVisibility, which reserves layout height even when hidden) so a
    // collapsed chip takes only the header's height — keeps inter-chip spacing tight.
```
with:
```kotlin
    // animateContentHeight (height-only; not AnimatedVisibility, which reserves layout height even
    // when hidden) so a collapsed chip takes only the header's height — keeps inter-chip spacing tight
    // — and the card never grows short→long in width when the window is resized.
```
and replace `Column(Modifier.animateContentSize(appSpatialSpec())) {` with `Column(Modifier.animateContentHeight()) {` (this exact line appears twice in the file — Collapsible and SpawnCard — replace BOTH).

- [ ] **Step 4: `SpawnCard` docstring.** Replace `it just grows the result in (animateContentSize).` with `it just grows the result in (animateContentHeight).` (the SpawnCard Column is covered by Step 3's both-occurrences replace).

- [ ] **Step 5: `AgentTranscriptPane.kt`.** Replace imports lines 3–4 (`animateContentSize` + `appSpatialSpec`) with `import dev.agentic.ui.animateContentHeight`, and replace `Column(Modifier.animateContentSize(appSpatialSpec())) {` with `Column(Modifier.animateContentHeight()) {`.

- [ ] **Step 6: Verify by read-back** —
```bash
cd /home/arcatva/src/agentic-worktrees/2ab3d238-4ae2-4f29-a4c3-b8a49d998826/agentic-dev-android
grep -rn "animateContentSize\|appSpatialSpec" app/src/main/java/dev/agentic/ui/session/Transcript.kt app/src/main/java/dev/agentic/ui/workflow/AgentTranscriptPane.kt   # expect: NONE
grep -rn "animateContentHeight" app/src/main/java/dev/agentic/ui   # expect: def + import&usage in each file
```

- [ ] **Step 7: Commit** —
```bash
git add app/src/main/java/dev/agentic/ui/AnimateContentHeight.kt \
        app/src/main/java/dev/agentic/ui/session/Transcript.kt \
        app/src/main/java/dev/agentic/ui/workflow/AgentTranscriptPane.kt
git commit -m "fix(transcript): animate card height only, so width snaps on resize"
```

---

### Task 2: Build and deliver the APK

**Files:** none (build/deploy only).

- [ ] **Step 1: Push to master** — `git push origin HEAD:master` (rebase onto master first if rejected).
- [ ] **Step 2: Build in main checkout** — `cd ~/src/agentic-dev-android && git pull --ff-only origin master && ~/.local/share/gradle-8.10.2/bin/gradle assembleRelease`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Deliver** — copy `app/build/outputs/apk/release/app-release.apk` to the worktree-root `outbox/` renamed `$(date +%Y%m%d-%H%M).apk`.
- [ ] **Step 4: User visually confirms** — resize the window: Notes/tool/thinking, agent, and Task cards snap to the new width instantly (no short→long grow); tap-to-expand/collapse and streaming still animate the height smoothly with the app's spring feel.

## Self-Review

- **Spec coverage:** motion-integrated height-only modifier (Step 1, defaults to `appSpatialSpec<Int>()` ✓); three in-scope cards switched (Steps 3–5 ✓); width snaps / height animates via motion spec (modifier `layout(placeable.width, anim.value)` + `appSpatialSpec` ✓); rail `AgentCard` untouched (Global Constraints ✓); both old imports dropped since `appSpatialSpec` was used only at the replaced sites (Steps 2,5 ✓).
- **Placeholder scan:** none — full modifier shown; each edit gives exact before/after; real worktree path.
- **Type consistency:** `animateContentHeight(animationSpec: FiniteAnimationSpec<Int>? = null)`; `appSpatialSpec<Int>()` returns `FiniteAnimationSpec<Int>` (it is `@Composable @ReadOnlyComposable`, legal inside the `composed {}` block); `Animatable<Int, AnimationVector1D>` matches `Animatable(targetHeight, Int.VectorConverter)`; `placeable.width`/`anim.value` are `Int` for `layout(width, height)`.
- **Rebase note:** authored after master's MD3E motion refactor landed; the call sites this replaces were `animateContentSize(appSpatialSpec())`, so routing the height spring through `appSpatialSpec()` preserves the motion feel rather than reverting it to a bare spring.
