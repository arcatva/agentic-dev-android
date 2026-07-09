# Logout Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a visible Log out icon button to the Home top app bar that, after a confirmation dialog, calls the existing `AuthRepository.logout()`.

**Architecture:** UI-only change. `HomeTopBar` (shared by the narrow `HomeScreen` and the wide `AdaptiveHome`) gains an `onLogout` callback, a confirm-dialog state, and a logout `IconButton` in its non-selection `actions` slot. Both call sites wire `onLogout = container.authRepo::logout`. No new navigation or auth logic — `AppNav` already redirects to `Login` when the token clears.

**Tech Stack:** Kotlin, Jetbrains Compose, Material 3 Expressive (`androidx.compose.material3:1.4.0-alpha18`), `material-icons-extended`.

## Global Constraints

- Compose Material3 is pinned to `1.4.0-alpha18` (exposes Expressive APIs) — **do not bump**.
- Logout icon MUST be `Icons.AutoMirrored.Rounded.Logout` (auto-mirrored for right-to-left locales), matching existing `Icons.AutoMirrored.Rounded.*` usage.
- Use standard Expressive components + existing motion tokens only. **Never** introduce a bare integer duration or the legacy `FastOutSlowInEasing`. (No custom motion is needed for this feature.)
- Confirm button is a plain `TextButton` labelled `Log out` — same style as the existing delete-confirm dialog (no error-color emphasis).
- The logout control appears only in the **non-selection** top bar, not the multi-select contextual bar.
- **Do NOT build in the worktree** (no Gradle wrapper / no keystore here). Build in the main checkout `~/src/agentic-dev-android`.
- Commit/push directly to master (worktree flow: finish in worktree → `git push origin HEAD:master` → build in main checkout → deliver renamed APK to `outbox/`).
- **No new Compose UI test harness** (out of scope). The project has JVM unit tests only.

## Testing approach (read this before Task 1)

There is no JVM-testable unit in this change and no Compose UI test infrastructure (no Robolectric, no `androidTest`, no `compose-ui-test`). The logout *behavior* (token cleared → redirect) is already covered by `app/src/test/java/dev/agentic/data/repo/AuthRepositoryTest.kt` and the `AppNav` redirect. Therefore each implementation step is verified by **reading back the edited region** (the global rule: verify every Edit by grepping/reading the affected region), the whole change is **compile-verified by the release build** in Task 2, and the user-visible behavior is verified by the **manual on-device checklist** in Task 2.

## File Structure

- Modify: `app/src/main/java/dev/agentic/ui/home/HomeScreen.kt`
  - `HomeTopBar` composable: new `onLogout` param, `confirmLogout` state + logout `AlertDialog`, logout `IconButton` in the non-selection `actions` slot.
  - `HomeScreen` composable: pass `onLogout` to its `HomeTopBar` call.
  - One new import.
- Modify: `app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt`
  - `AdaptiveHome` composable: pass `onLogout` to its `HomeTopBar` call.

These two files must change together: adding a required `onLogout` parameter to `HomeTopBar` makes both call sites fail to compile until both are updated. They are therefore one task.

---

### Task 1: Add logout control to HomeTopBar and wire both call sites

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/home/HomeScreen.kt` (import ~line 27; `HomeTopBar` signature line 172-179; insert dialog after line 200; non-selection `TopAppBar` lines 228-240; `HomeScreen` call site lines 122-129)
- Modify: `app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt` (`HomeTopBar` call site lines 126-133)

**Interfaces:**
- Consumes: `AppContainer.authRepo: AuthRepository` (already referenced as `container` in both files), `AuthRepository.logout(): Unit` (existing, public).
- Produces: `HomeTopBar(..., onLogout: () -> Unit)` — the trailing required parameter both call sites now pass.

- [ ] **Step 1: Add the logout icon import to HomeScreen.kt**

In `app/src/main/java/dev/agentic/ui/home/HomeScreen.kt`, add this import alongside the other icon imports (immediately after line 27 `import androidx.compose.material.icons.Icons`):

```kotlin
import androidx.compose.material.icons.automirrored.rounded.Logout
```

- [ ] **Step 2: Add the `onLogout` parameter to `HomeTopBar`**

Replace the `HomeTopBar` signature (lines 172-179) with:

```kotlin
internal fun HomeTopBar(
    selectionMode: Boolean,
    selectedCount: Int,
    totalCount: Int,
    onCloseSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onLogout: () -> Unit,
) {
```

- [ ] **Step 3: Add the logout confirm state + dialog**

Immediately after the existing delete-confirm `AlertDialog` block (after its closing `}` on line 200, before the `if (selectionMode) {` on line 202), insert:

```kotlin
    // Logout confirm — independent of the delete `confirm` flag (which is keyed on selection
    // mode). Lives in the non-selection top bar. Mirrors the delete dialog's structure/styling.
    var confirmLogout by remember { mutableStateOf(false) }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
            title = { Text("Log out?") },
            text = { Text("You'll need to sign in again to continue.") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("Log out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("Cancel") }
            },
        )
    }

```

- [ ] **Step 4: Add the logout `IconButton` to the non-selection `TopAppBar`**

Replace the `else` branch's `TopAppBar` (lines 227-241) with the version below — it adds an `actions` slot; the `title` block is unchanged:

```kotlin
    } else {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "agentic-dev",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            actions = {
                IconButton(onClick = { confirmLogout = true }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = "Log out",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
```

- [ ] **Step 5: Wire the `HomeScreen` call site**

Replace the `HomeTopBar(...)` call in `HomeScreen` (lines 122-129) with:

```kotlin
            HomeTopBar(
                selectionMode = s.selectionMode,
                selectedCount = s.selectedCount,
                totalCount = s.sessions.size,
                onCloseSelection = resolvedVm::clearSelection,
                onSelectAll = resolvedVm::selectAll,
                onDeleteSelected = resolvedVm::deleteSelected,
                onLogout = container.authRepo::logout,
            )
```

(`container` is already obtained at `HomeScreen.kt:104`.)

- [ ] **Step 6: Wire the `AdaptiveHome` call site**

In `app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt`, replace the `HomeTopBar(...)` call (lines 126-133) with:

```kotlin
            HomeTopBar(
                selectionMode = homeState.selectionMode,
                selectedCount = homeState.selectedCount,
                totalCount = homeState.sessions.size,
                onCloseSelection = homeVm::clearSelection,
                onSelectAll = homeVm::selectAll,
                onDeleteSelected = homeVm::deleteSelected,
                onLogout = container.authRepo::logout,
            )
```

(`container` is already obtained at `AdaptiveHome.kt:93`. No new import is needed here.)

- [ ] **Step 7: Read-back verification (the test cycle for a UI-only change)**

Run:

```bash
cd /home/arcatva/src/agentic-worktrees/3ba3d620-35e2-4c60-9886-a6556dd6e9ef/agentic-dev-android
grep -n "automirrored.rounded.Logout" app/src/main/java/dev/agentic/ui/home/HomeScreen.kt
grep -n "onLogout" app/src/main/java/dev/agentic/ui/home/HomeScreen.kt app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt
grep -n "confirmLogout\|AutoMirrored.Rounded.Logout\|\"Log out\"" app/src/main/java/dev/agentic/ui/home/HomeScreen.kt
```

Expected:
- The import line is present in `HomeScreen.kt`.
- `onLogout` appears: once in the `HomeTopBar` signature, once in the `HomeScreen` call, once in the `AdaptiveHome` call (3+ matches total across the two files).
- `confirmLogout` appears 3× (declaration, `if`, two assignments), the icon is referenced in both the dialog and the action button, and `"Log out"` appears (title + confirm button).

If any expected match is missing, fix the corresponding step before committing.

- [ ] **Step 8: Commit**

```bash
cd /home/arcatva/src/agentic-worktrees/3ba3d620-35e2-4c60-9886-a6556dd6e9ef/agentic-dev-android
git add app/src/main/java/dev/agentic/ui/home/HomeScreen.kt app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt
git commit -m "feat: logout button in Home top app bar

Add a Log out IconButton (Icons.AutoMirrored.Rounded.Logout) to the
non-selection HomeTopBar actions, guarded by a confirm dialog that mirrors
the delete-confirm. Wires the existing AuthRepository.logout() from both the
narrow (HomeScreen) and wide (AdaptiveHome) call sites; AppNav already
redirects to Login on logout.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Build the release APK, deliver, and verify on device

**Files:** none (build + delivery + manual verification).

**Interfaces:**
- Consumes: the committed change from Task 1.
- Produces: a release-signed APK in `outbox/` and a confirmed working logout flow.

- [ ] **Step 1: Push the worktree branch to master**

```bash
cd /home/arcatva/src/agentic-worktrees/3ba3d620-35e2-4c60-9886-a6556dd6e9ef/agentic-dev-android
git push origin HEAD:master
# if rejected: git pull --rebase origin master && git push origin HEAD:master
```

Expected: push succeeds (or rebase-then-push succeeds).

- [ ] **Step 2: Build the release APK in the main checkout (NOT the worktree)**

```bash
cd ~/src/agentic-dev-android && git pull --ff-only origin master
~/.local/share/gradle-8.10.2/bin/gradle assembleRelease
```

Expected: `BUILD SUCCESSFUL`; output at `~/src/agentic-dev-android/app/build/outputs/apk/release/app-release.apk`. A compile failure here means the Kotlin in Task 1 is wrong — fix in the worktree, re-push, rebuild.

- [ ] **Step 3: Deliver the APK to `outbox/` with a timestamped name**

```bash
cd /home/arcatva/src/agentic-worktrees/3ba3d620-35e2-4c60-9886-a6556dd6e9ef/agentic-dev-android
mkdir -p ./outbox && cp ~/src/agentic-dev-android/app/build/outputs/apk/release/app-release.apk \
  "./outbox/$(date +%Y%m%d-%H%M).apk"
ls -la ./outbox/
```

Expected: a `YYYYMMDD-HHMM.apk` file is listed in `outbox/`.

- [ ] **Step 4: Manual on-device verification**

Install the delivered APK and confirm:
1. On Home (narrow phone layout) the Log out icon (door-with-arrow) is visible at the top-right of the top app bar.
2. On a wide/tablet layout (`AdaptiveHome`) the same icon is visible.
3. Tapping it opens a "Log out?" dialog with "You'll need to sign in again to continue.", a "Log out" button and a "Cancel" button.
4. "Cancel" dismisses the dialog and stays on Home.
5. "Log out" dismisses the dialog and navigates back to the Login screen; re-login works.
6. Entering multi-select mode (long-press a session) hides the logout icon (contextual bar shows instead).

If any check fails, return to Task 1.

---

## Self-Review

**1. Spec coverage:**
- Visible logout `IconButton` in Home top bar → Task 1 Step 4. ✓
- `Icons.AutoMirrored.Rounded.Logout` → Task 1 Steps 1, 3, 4. ✓
- Confirmation dialog mirroring delete-confirm, plain "Log out" button → Task 1 Step 3. ✓
- Both call sites wired (narrow + wide) → Task 1 Steps 5, 6. ✓
- Non-selection only → Task 1 Step 4 (action added to the `else`/non-selection `TopAppBar`). ✓
- Calls existing `logout()`, no new nav/auth logic → Task 1 Steps 5, 6. ✓
- MD3E/motion conformance (standard components, no raw durations) → Global Constraints + no custom motion in any step. ✓
- Verification (read-back, build, manual) → Task 1 Step 7, Task 2 Steps 2, 4. ✓
- Out of scope (no new screen/route, no test harness, no auth changes) → respected; no such steps. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to". All code blocks are complete. ✓

**3. Type consistency:** `onLogout: () -> Unit` is used identically in the signature (Step 2) and both call sites (Steps 5, 6). `confirmLogout` name is consistent across declaration, dialog, and action. `container.authRepo::logout` matches the existing `AuthRepository.logout()` signature. ✓
