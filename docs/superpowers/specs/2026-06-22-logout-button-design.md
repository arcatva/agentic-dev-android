# Logout button in the Home top app bar

Date: 2026-06-22
Repo: agentic-dev-android
Status: design (awaiting user review)

## Problem

There is no way to log out from the UI. The auth machinery is already complete:

- `AuthRepository.logout()` (`app/src/main/java/dev/agentic/data/repo/AuthRepository.kt:73`)
  clears the stored token and `api.token`.
- `AppNav` observes `authRepo.isLoggedIn` and, on logout (or a 401), pops the back stack and
  navigates to `Login` (`app/src/main/java/dev/agentic/ui/nav/AppNav.kt:111`).

What is missing is purely the **UI trigger** — a visible control that calls `logout()`.

## Goal

A visible, discoverable **Log out** control in the Home top app bar, using a Material 3
Expressive icon, guarded by a confirmation dialog, conforming to the app's existing design and
motion conventions. No new navigation or auth logic.

## Decisions (from brainstorming)

- **Placement / pattern:** a directly visible `IconButton` in the Home top app bar (not an
  overflow menu, not an account menu) — the user wants an obvious button.
- **Confirmation:** a confirmation dialog before logout (prevents accidental taps).
- **Confirm button styling:** plain `TextButton` "Log out", identical in style to the existing
  delete-confirm dialog (no error-color emphasis — logout is reversible by signing in again).

## Where it lives

`app/src/main/java/dev/agentic/ui/home/HomeScreen.kt`, composable `HomeTopBar` (internal,
line 172). The control is added to the **non-selection** branch's `TopAppBar` `actions` slot
(currently that `TopAppBar` has only a `title`, lines 227-241). It is deliberately **not**
shown in the multi-select contextual bar (lines 202-226), which keeps its own actions.

`HomeTopBar` is the single source of top-bar chrome for **both** layouts:
- narrow: `HomeScreen` → `HomeTopBar` (`HomeScreen.kt:122`)
- wide:  `AdaptiveHome` → `HomeTopBar` (`AdaptiveHome.kt:126`)

So one change to `HomeTopBar` surfaces the logout button in both single-pane and 3-pane
layouts, and both call sites must pass the new callback.

## The control

```kotlin
IconButton(onClick = { confirmLogout = true }) {
    Icon(
        Icons.AutoMirrored.Rounded.Logout,
        contentDescription = "Log out",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
```

- Icon: `Icons.AutoMirrored.Rounded.Logout` (the MD3 door-with-arrow logout glyph).
  - The **AutoMirrored** variant is required by spec: the arrow flips for right-to-left
    locales. Matches existing usage of `Icons.AutoMirrored.Rounded.ArrowBack` / `.Send` in
    `SessionScreen.kt`, `NewRequestScreen.kt`, `CommitGraphScreen.kt`, `WorkflowScreen.kt`.
  - From `androidx.compose.material:material-icons-extended` (already a dependency).
- `tint = onSurfaceVariant` and an explicit `contentDescription` match the icon-button
  convention in `SessionScreen.kt` (history/workflow actions).

## Confirmation dialog

Mirror the existing delete-confirm `AlertDialog` already present in `HomeTopBar`
(`HomeScreen.kt:186-200`) so the two confirmations look and behave identically:

```kotlin
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

`confirmLogout` is independent of the existing `confirm` flag (which is keyed on
`selectionMode` for delete). The logout flag lives in the non-selection path and does not need
that keying.

## MD3 Expressive / motion conformance

The app has an explicit motion convention (`app/src/main/java/dev/agentic/ui/Motion.kt` and
`docs/superpowers/specs/2026-06-22-md3e-animation-refactor-design.md`): **use the motion
scheme / tokens; never a bare integer duration or the legacy `FastOutSlowInEasing`.** This
design conforms by leaning on standard Expressive components rather than hand-rolled motion:

- **Button press:** standard `IconButton` under `MaterialExpressiveTheme` (set in `Theme.kt`)
  already provides the Expressive press feedback (shape morph + spring ripple). No custom
  animation — using the standard component *is* the conforming choice.
- **Dialog enter/exit:** standard `AlertDialog`, identical to the existing delete dialog, runs
  on `MaterialExpressiveTheme` + the app's softened-spring `AppMotionScheme` (`Theme.kt:96`).
  Scale + fade carry the expressive spring character.
- **No raw `tween(...)` / `FastOutSlowInEasing` introduced.** If extra dialog expressiveness
  is ever wanted, it would use `appSpatialSpec()` (size/position) and `appEffectsSpec()`
  (alpha/color) from `Motion.kt` — not custom constants. Default keeps parity with the
  existing delete dialog (YAGNI).

## Concrete changes

1. `app/src/main/java/dev/agentic/ui/home/HomeScreen.kt`
   - `HomeTopBar` signature gains `onLogout: () -> Unit`.
   - Add `var confirmLogout by remember { mutableStateOf(false) }` and the logout
     `AlertDialog` (above).
   - Non-selection `TopAppBar` gains an `actions = { ... }` slot containing the logout
     `IconButton`.
   - `HomeScreen`'s `HomeTopBar(...)` call passes `onLogout = container.authRepo::logout`
     (`container` already obtained at `HomeScreen.kt:104`).
   - Add import `androidx.compose.material.icons.automirrored.rounded.Logout`.

2. `app/src/main/java/dev/agentic/ui/home/AdaptiveHome.kt`
   - Its `HomeTopBar(...)` call (line 126) passes `onLogout = container.authRepo::logout`
     (`container` already obtained at `AdaptiveHome.kt:93`, same as `HomeScreen`).

3. This design doc (new).

## Out of scope

- No new screen, route, settings screen, or profile screen.
- No change to `AuthRepository`, `AppNav`, `SettingsStore`, or any auth/navigation logic — the
  button only calls the existing `logout()`.
- No logout control in the multi-select contextual bar or in the Session/Workflow/History
  screens (Home is the single home for it).
- No new Compose UI test harness (see Verification).

## Verification

- **Logout behavior** (token cleared, redirect to Login) is already covered by
  `app/src/test/java/dev/agentic/data/repo/AuthRepositoryTest.kt` (`logout clears token…`,
  `onUnauthorized callback triggers logout`) plus the `AppNav` redirect — not re-tested.
- **New code is UI-only wiring** (an `IconButton` whose click delegates to the already-tested
  `logout()`), with no branching logic worth a unit test. The project has **no** Compose UI
  test infrastructure (no Robolectric, no `androidTest`, no `compose-ui-test` deps — tests are
  JVM-only for domain/repos/ViewModels). Adding a full UI-test harness for one button is out
  of scope.
- Verification path (per repo `CLAUDE.md`):
  1. Read back the edited regions to confirm the param, dialog, actions slot, both call sites,
     and the import are present.
  2. Commit + push to master; build a release APK in the main checkout
     (`~/src/agentic-dev-android`); rename to `<YYYYMMDD-HHMM>.apk`; copy into `outbox/`.
  3. Manual on-device check: logout icon visible in Home top bar (narrow and wide) → tap →
     dialog appears → "Cancel" leaves you on Home → "Log out" returns to the Login screen.
