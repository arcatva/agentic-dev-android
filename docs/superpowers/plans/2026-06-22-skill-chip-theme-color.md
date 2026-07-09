# Skill Chip Theme Color Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Selected (lit) skill chips in the New Request screen carry the skill inline-card theme color (cyan), while Repos chips stay on the default look.

**Architecture:** Add an opt-in accent to the shared private `ChipPicker` composable (two optional `Color?` params, default null = today's look). The Skills call site passes the cyan inline-card pair; the Repos call site is unchanged. Pure presentational change — only `FilterChip` colors.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3 (`FilterChip` / `FilterChipDefaults`).

## Global Constraints

- Compose Material3 is pinned to `1.4.0-alpha18` — do not bump it.
- Do NOT build in the worktree (no Gradle wrapper / keystore). Build in `~/src/agentic-dev-android` after pushing to master (see repo CLAUDE.md).
- Colors come from `app/src/main/java/dev/agentic/ui/Theme.kt`: `AccentCyanContainer = Color(0xFF00504C)`, `OnAccentCyanContainer = Color(0xFF76F3EA)`. Do not redefine them.
- Color only — no leading/sparkle icon. Repos, templates, sliders untouched. No data-model/API/ViewModel change.

---

### Task 1: Color selected skill chips with the inline-card cyan

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/newrequest/NewRequestScreen.kt`
  - imports block (around lines 30, 59-60)
  - `ChipPicker` composable (lines 393-428)
  - Skills `ChipPicker` call site (lines 218-227)

**Interfaces:**
- Consumes: `AccentCyanContainer`, `OnAccentCyanContainer` from `dev.agentic.ui` (Theme.kt); `FilterChipDefaults` from Material 3.
- Produces: `ChipPicker(label, options, selected, onToggle, selectedContainer: Color? = null, selectedContent: Color? = null)` — two new trailing optional params; existing callers that omit them are unaffected.

- [ ] **Step 1: Add the three imports**

Add to the import block (keep them grouped with neighbors):

```kotlin
import androidx.compose.material3.FilterChipDefaults
```
(place right after the existing `import androidx.compose.material3.FilterChip`)

```kotlin
import dev.agentic.ui.AccentCyanContainer
import dev.agentic.ui.OnAccentCyanContainer
```
(place just before the existing `import dev.agentic.ui.AccentViolet`)

`Color` is already imported (`androidx.compose.ui.graphics.Color`) — do not re-add.

- [ ] **Step 2: Add the optional accent params + conditional colors to `ChipPicker`**

Replace the `ChipPicker` signature and add the `chipColors` value. Final form:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipPicker(
    label: String,
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    // Optional accent for the SELECTED (lit) chips. When both are non-null the lit chip is filled
    // in these colors — used by Skills to match the cyan skill inline card. Null (the default,
    // used by Repos) keeps FilterChip's stock Material 3 look.
    selectedContainer: Color? = null,
    selectedContent: Color? = null,
) {
    var q by remember { mutableStateOf("") }
    val shown = options.filter { q.isBlank() || it.contains(q, ignoreCase = true) }
    // Default colors when no accent is supplied; an accented (filled) selected state otherwise.
    val chipColors = if (selectedContainer != null && selectedContent != null) {
        FilterChipDefaults.filterChipColors(
            selectedContainerColor = selectedContainer,
            selectedLabelColor = selectedContent,
            selectedLeadingIconColor = selectedContent,
        )
    } else {
        FilterChipDefaults.filterChipColors()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            placeholder = { Text("Search…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            shown.forEach { o ->
                FilterChip(
                    selected = o in selected,
                    onClick = { onToggle(o) },
                    label = { Text(o) },
                    colors = chipColors,
                )
            }
        }
    }
}
```

- [ ] **Step 3: Pass the cyan accent at the Skills call site**

In the `ChipPicker(label = "Skills", …)` call (lines 218-227), add the two accent args after `onToggle`. Leave the `Repos` call (line 206) unchanged. Final Skills call:

```kotlin
ChipPicker(
    label = "Skills",
    options = s.availableSkills.map { it.name },
    selected = s.selectedSkills.toSet(),
    onToggle = { skill ->
        val updated = if (skill in s.selectedSkills) s.selectedSkills - skill
                      else s.selectedSkills + skill
        realVm.setSkills(updated)
    },
    selectedContainer = AccentCyanContainer,
    selectedContent = OnAccentCyanContainer,
)
```

- [ ] **Step 4: Verify by read-back**

Re-read the three edited regions of `NewRequestScreen.kt` and confirm:
- the two `import dev.agentic.ui.AccentCyan*` lines and `FilterChipDefaults` import are present;
- `ChipPicker` has `selectedContainer`/`selectedContent` params and the `chipColors` val;
- every `FilterChip` inside `ChipPicker` passes `colors = chipColors`;
- the Skills call passes the cyan pair and the Repos call does NOT.

(No Compose unit test exists for this screen and chip color isn't assertable via semantics in this project's setup, so verification is read-back here + the visual APK check in Task 2. The build itself is the compile check.)

- [ ] **Step 5: Commit**

```bash
cd ~/src/agentic-worktrees/c04fc3e5-a523-4036-8840-2e15a8ea7f30/agentic-dev-android
git add app/src/main/java/dev/agentic/ui/newrequest/NewRequestScreen.kt
git commit -m "feat(new-request): lit skill chips use the cyan inline-card theme color"
```

---

### Task 2: Build and deliver the APK

**Files:** none (build/deploy only).

- [ ] **Step 1: Push to master**

```bash
cd ~/src/agentic-worktrees/c04fc3e5-a523-4036-8840-2e15a8ea7f30/agentic-dev-android
git push origin HEAD:master   # if rejected: git pull --rebase origin master && git push origin HEAD:master
```

- [ ] **Step 2: Build the release APK in the main checkout** (worktree has no Gradle/keystore)

```bash
cd ~/src/agentic-dev-android && git pull --ff-only origin master
~/.local/share/gradle-8.10.2/bin/gradle assembleRelease
```
Expected: `BUILD SUCCESSFUL`, output at `~/src/agentic-dev-android/app/build/outputs/apk/release/app-release.apk`.

- [ ] **Step 3: Deliver to outbox, renamed by build time**

```bash
mkdir -p ~/src/agentic-worktrees/c04fc3e5-a523-4036-8840-2e15a8ea7f30/agentic-dev-android/outbox
cp ~/src/agentic-dev-android/app/build/outputs/apk/release/app-release.apk \
  ~/src/agentic-worktrees/c04fc3e5-a523-4036-8840-2e15a8ea7f30/agentic-dev-android/outbox/$(date +%Y%m%d-%H%M).apk
```

- [ ] **Step 4: User visually confirms** selected skill chips render dark-teal/cyan and Repos chips are unchanged.

## Self-Review

- **Spec coverage:** color-only selected Skills chips (Task 1, Steps 2-3 ✓); Repos unchanged (Task 1, Step 3 ✓ — Repos call untouched); inline-card colors from Theme.kt (Global Constraints + Step 3 ✓); no icon / no data-model change (Global Constraints ✓); build/deliver per repo CLAUDE.md (Task 2 ✓).
- **Placeholder scan:** none — all code shown in full.
- **Type consistency:** `selectedContainer`/`selectedContent` (`Color?`) defined in the signature and used in `chipColors` and the Skills call; `AccentCyanContainer`/`OnAccentCyanContainer` are the exact Theme.kt names; `FilterChipDefaults.filterChipColors` param names (`selectedContainerColor`, `selectedLabelColor`, `selectedLeadingIconColor`) are the Material 3 API names.
