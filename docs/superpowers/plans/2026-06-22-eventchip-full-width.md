# EventChip Full-Width Inline Markers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The skill / workflow / retry inline markers in the chat transcript span the full transcript width, lining up with the other inline cards instead of sitting as ragged left-aligned chips.

**Architecture:** One presentational change to the single shared private `EventChip` composable in `Transcript.kt`. All three markers (SkillNode → cyan, WorkflowNode → violet/clickable, RetryNode → error-red) render through `EventChip`, plus two nested call sites inside `SpawnCard`'s expanded body — so editing the one composable covers every case. Adopt the exact full-width pattern the sibling cards (`Collapsible`, `SpawnCard`) already use: `Modifier.fillMaxWidth()` on the `Surface` + a `FadingText` label with `Modifier.weight(1f)`.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3 (`Surface`, `FadingText`).

## Global Constraints

- Compose Material3 is pinned to `1.4.0-alpha18` — do not bump it.
- Do NOT build in the worktree (no Gradle wrapper / keystore). Build in `~/src/agentic-dev-android` after pushing to master (see repo CLAUDE.md).
- Scope is the chat-transcript markers only. One file: `app/src/main/java/dev/agentic/ui/session/Transcript.kt`. No data-model / API / ViewModel / Theme change.
- `Modifier.fillMaxWidth` (import line 16) and `FadingText` (import line 110) are ALREADY imported in this file; `weight` is a `RowScope` extension available inside the `Row`. No new imports.
- Keep the modifier order `fillMaxWidth().clip(shape).clickable(...)` — `clip` must stay before `clickable` so the tap ripple follows the rounded shape (same order as `Collapsible` and `SpawnCard`).

---

### Task 1: Make `EventChip` fill the transcript width

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/session/Transcript.kt`
  - `EventChip` composable (lines 408–438)

**Interfaces:**
- Consumes: `Modifier.fillMaxWidth()`, `FadingText(text, modifier, style, color, …)`, `RowScope.weight` — all already in scope.
- Produces: no signature change. `EventChip(icon, label, container, onColor, onClick)` keeps the same parameters; only its internal layout changes. All 5 call sites (Transcript.kt:262 SkillNode, :276 WorkflowNode, :286 RetryNode, :572 nested skill, :576 nested workflow) are unaffected and automatically inherit the full-width look.

- [ ] **Step 1: Replace the `EventChip` composable with the full-width version**

Two changes vs. the current body: (a) add `.fillMaxWidth()` as the FIRST modifier on the `Surface`; (b) replace the `Text(label, …)` with `FadingText(label, …, modifier = Modifier.weight(1f))`. Final form (replace lines 408–438 in full):

```kotlin
/** Full-width inline transcript marker for a skill / spawned agent / workflow / retry call. The row
 *  fills the transcript width so these markers line up with the other inline cards (Collapsible /
 *  SpawnCard / Ask / Perm / Plan) instead of sitting as ragged left-aligned chips. */
@Composable
private fun EventChip(
    icon: ImageVector,
    label: String,
    container: Color,
    onColor: Color,
    onClick: (() -> Unit)? = null,
) {
    // Inline cards are markers, not copyable prose — disable text selection so a long-press taps to
    // open/expand instead of being hijacked by the transcript's SelectionContainer.
    DisableSelection {
        Surface(
            color = container,
            shape = MaterialTheme.shapes.small,
            // fillMaxWidth before clip+clickable so the bar spans the transcript and the tap ripple
            // (clipped to the rounded shape) covers the whole row — same order as Collapsible/SpawnCard.
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(enabled = onClick != null) { onClick?.invoke() },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(icon, null, tint = onColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                // weight(1f) + FadingText: the label fills the remaining width on one line and fades at
                // the right edge if it overflows — exactly like the other full-width inline cards.
                FadingText(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = onColor,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
```

- [ ] **Step 2: Verify by read-back**

Re-read the `EventChip` region of `Transcript.kt` and confirm:
- the `Surface` modifier chain is `Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = onClick != null) { onClick?.invoke() }` (fillMaxWidth FIRST, clip before clickable);
- the label is now `FadingText(label, style = MaterialTheme.typography.labelMedium, color = onColor, modifier = Modifier.weight(1f))` — NOT a plain `Text`, and it carries `Modifier.weight(1f)`;
- no new `import` lines were added (fillMaxWidth and FadingText were already imported);
- the composable signature `EventChip(icon, label, container, onColor, onClick)` is unchanged.

(No Compose unit test exists in this project and chip width isn't assertable via semantics in this setup — confirmed: there is no compose-ui-test / Robolectric / screenshot harness, and `NodeLabelsTest` asserts label STRINGS only. So verification here is read-back + the visual APK check in Task 2. The build itself is the compile check.)

- [ ] **Step 3: Commit**

```bash
cd /home/arcatva/src/agentic-worktrees/2ab3d238-4ae2-4f29-a4c3-b8a49d998826/agentic-dev-android
git add app/src/main/java/dev/agentic/ui/session/Transcript.kt
git commit -m "fix(transcript): inline event markers span full transcript width"
```

---

### Task 2: Build and deliver the APK

**Files:** none (build/deploy only).

- [ ] **Step 1: Push to master**

```bash
cd /home/arcatva/src/agentic-worktrees/2ab3d238-4ae2-4f29-a4c3-b8a49d998826/agentic-dev-android
git push origin HEAD:master   # if rejected: git pull --rebase origin master && git push origin HEAD:master
```

- [ ] **Step 2: Build the release APK in the main checkout** (worktree has no Gradle/keystore — this is also the compile check)

```bash
cd ~/src/agentic-dev-android && git pull --ff-only origin master
~/.local/share/gradle-8.10.2/bin/gradle assembleRelease
```
Expected: `BUILD SUCCESSFUL`, output at `~/src/agentic-dev-android/app/build/outputs/apk/release/app-release.apk`. A compile error here means Step 1 of Task 1 was applied wrong — fix in the worktree, re-commit, re-push, rebuild.

- [ ] **Step 3: Deliver to outbox, renamed by build time**

```bash
mkdir -p /home/arcatva/src/agentic-worktrees/2ab3d238-4ae2-4f29-a4c3-b8a49d998826/agentic-dev-android/outbox
cp ~/src/agentic-dev-android/app/build/outputs/apk/release/app-release.apk \
  /home/arcatva/src/agentic-worktrees/2ab3d238-4ae2-4f29-a4c3-b8a49d998826/agentic-dev-android/outbox/$(date +%Y%m%d-%H%M).apk
```

- [ ] **Step 4: User visually confirms** the skill (cyan), workflow (violet), and retry (red) inline markers now stretch the full transcript width — left-aligned icon + label, background filling the row, aligned with the Agent / Notes / Ask / Plan cards above and below them.

## Self-Review

- **Spec coverage:** all three markers full-width (Task 1, Step 1 — single `EventChip` edit covers SkillNode/WorkflowNode/RetryNode + the two nested call sites ✓); other cards already full-width, untouched (scope note in Global Constraints ✓); build/deliver per repo CLAUDE.md (Task 2 ✓).
- **Placeholder scan:** none — the full final composable is shown; commands are concrete with the actual worktree path.
- **Type consistency:** `FadingText(text, modifier, style, color, …)` matches the signature in `FadingEdge.kt` (line 78) and the existing `SpawnCard`/`Collapsible` call sites; `Modifier.fillMaxWidth()` and `Modifier.weight(1f)` are the exact APIs already used at lines 464/474 (Collapsible) and 510/520 (SpawnCard); `EventChip`'s parameter list is unchanged so all 5 call sites compile as-is.
- **Adversarial verification (pre-plan):** 3 parallel read-only verifiers confirmed (a) `EventChip` is the only composable rendering these markers and all 5 call sites sit in width-bounded parents (no horizontalScroll/FlowRow) — `fillMaxWidth()` is safe everywhere; (b) the modifier order and `weight(1f)`/`FadingText` combo are Compose-correct with no height/spacing regression and no effect on non-clickable variants; (c) no existing test asserts `EventChip` width, so nothing breaks.
