---
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
execution: code
product_contract_source: ce-plan-bootstrap
type: refactor
created: 2026-07-13
depth: lightweight
title: "refactor: Replace text action buttons with icon-only buttons"
---

# refactor: Replace text action buttons with icon-only buttons

## Summary

The user (screenshots: **Skill store** screen, **Opus routing** dialog) wants the
text-label *action* buttons in these settings surfaces turned into icon-only buttons
— `Sources (2)`, `Refresh`, `Reset to default`, `Add`. This is a presentation swap:
`TextButton { Text(...) }` → `IconButton { Icon(...) }` with the label preserved as
`contentDescription`. Every `onClick` handler and enabled/visibility condition stays
exactly as-is, so no behavior changes. Two files, ~2 commits.

Primary/dialog-confirm buttons (`Save`, `Cancel`, the full-width `Add source` submit,
and the per-row `Install`/`Update`/`Remove`) stay as text — see Scope Boundaries.

---

## Problem Frame

Action buttons currently render their label as text (`TextButton`). The user finds the
text buttons visually noisy and wants compact icon buttons instead, matching the icon
buttons the app already uses elsewhere (the top-bar back button, the search-clear
button, the providers-list Edit/Delete buttons at `ProvidersScreen.kt:494-498`).

Constraints:
- **Accessibility is non-negotiable.** An icon-only button carries no visible text, so
  each converted button MUST set `contentDescription` to its former label — otherwise
  TalkBack users lose the control. This is a requirement, not a nice-to-have.
- **Behavior must be preserved.** Only the visual affordance changes; the action, the
  `enabled = ...` guards, and the conditional visibility (`if (family.customized)`,
  `s.sources?.let { ... }`) are unchanged.
- **The `Sources (N)` count is a real signal** and must survive the swap (see KTD-1).

---

## Requirements

- **R1** — In the Skill store top bar, `Sources (N)` and `Refresh` render as icon-only
  buttons; the source count remains visible.
- **R2** — In the Opus/native-model routing dialog, `Reset to default` renders as an
  icon-only button (still error-tinted, still shown only when the family is customized).
- **R3** — The `Add` toggle in the Sub-agent models section renders icon-only.
- **R4** — In the Store-sources sheet, each source row's `Remove` renders as an icon
  button, matching the delete-icon pattern the providers list already uses.
- **R5** — Every converted icon button sets a non-empty `contentDescription` so no
  control loses its accessible name: the former text label, optionally extended with the
  target entity (e.g. `"Remove owner/repo"`, `"Add a model"`). No behavior, enablement, or
  visibility condition changes.

---

## Key Technical Decisions

**KTD-1 — Preserve the `Sources (N)` count with a `BadgedBox`.** Wrap the Sources
`IconButton`'s `Icon` in `androidx.compose.material3.BadgedBox` with a `Badge { Text("$count") }`
so the count stays visible on the icon. Show the badge only when the count is > 0. This is
a deliberate, minor divergence from the current code — `?.let { " (${it.size})" }` renders
`Sources (0)` for a configured-but-empty list, and a `0` badge reads as noise, so suppress
it in the empty case. Simpler fallback if the
badge looks bad in the top bar: drop the badge and fold the count into the
`contentDescription` ("Sources, 2 configured") — but the badge is preferred because the
count is currently glanceable and a contentDescription is not.

**KTD-2 — Icons come from `material-icons-extended`, already a dependency**
(`gradle/libs.versions.toml:58,96`). Chosen icons and their existing-usage precedent:
| Button | Icon | Notes |
|---|---|---|
| Sources | `Icons.Rounded.Source` | fallback `Icons.AutoMirrored.Rounded.List` if `Source` doesn't resolve |
| Refresh | `Icons.Rounded.Refresh` | |
| Reset to default | `Icons.Rounded.RestartAlt` | `tint = MaterialTheme.colorScheme.error`; fallback `Icons.Rounded.SettingsBackupRestore` |
| Add (toggle) | `Icons.Rounded.Add` | already imported/used at `ProvidersScreen.kt:287`; optionally show `Icons.Rounded.Close` when the form is open |
| Remove (source row) | `Icons.Rounded.DeleteOutline` | already used for delete at `ProvidersScreen.kt:498` |

The implementer must confirm each exact icon name compiles (a known Compose gotcha: some
icon identifiers differ from their Material catalog name); swap to the listed fallback if
one doesn't resolve. This is the only execution-time unknown.

**KTD-3 — Tooltips are recommended but optional.** For the less obvious icons (Sources,
Reset to default) a Material3 `TooltipBox` + `PlainTooltip` would aid discovery, but the
repo has no existing tooltip pattern and `contentDescription` already satisfies the
accessibility floor. Add tooltips only if trivial; do not build tooltip scaffolding for
this change.

---

## Scope Boundaries

**In scope:** R1–R5 — the four named action buttons plus the source-row Remove, across
the two files below.

### Deliberately left as text (not a gap)
- **Dialog `Save` / `Cancel`** (routing dialog `ProvidersScreen.kt:1062,1078`; the
  delete-provider confirm dialog `ProvidersScreen.kt:221-234`) — Material `AlertDialog`
  confirm/dismiss buttons are conventionally text; icon-only confirm/cancel hurts clarity.
- **`Add source`** full-width filled `Button` in the sources sheet
  (`SkillStoreScreen.kt:360-366`) — a primary form-submit CTA, not a toolbar action.
- **`Install` / `Update` / `Remove`** on catalog rows (`SkillStoreScreen.kt:301-305`) —
  three distinct verbs whose meaning a single download-style icon would blur.

### Deferred to Follow-Up Work
- If the user *also* wants the catalog-row `Install`/`Update`/`Remove` and/or the
  `Add source` submit iconified, that is a small follow-up on the same files — call it
  out to the user rather than assuming it here.

---

## Implementation Units

### U1. Iconize Skill store top-bar actions and source-row Remove

**Requirements:** R1, R4, R5
**Dependencies:** none
**Files:** `feature/globalsettings/src/main/kotlin/dev/agentic/ui/globalsettings/SkillStoreScreen.kt`

**Approach:**
- Top-bar `actions` (lines 121-129): replace the two `TextButton`s with `IconButton`s.
  - Sources: `IconButton(onClick = { sourcesOpen = true })` containing a `BadgedBox`
    (KTD-1) wrapping `Icon(Icons.Rounded.Source, contentDescription = "Sources")`; badge
    shows `s.sources?.size` when present.
  - Refresh: `IconButton(onClick = { resolvedVm.loadCatalog(force = true) }, enabled = !s.catalogLoading)`
    with `Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")`. Keep the
    `enabled` guard verbatim.
- `SourcesSheet` per-row Remove (lines 339-341): replace the `TextButton` with
  `IconButton(onClick = { onRemoveSource(src) }, enabled = !busy)` and
  `Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove $src")`.
- Add the new icon imports; the old `TextButton` import stays (still used by the
  `Cancel`/`Install`/`Update`/`Remove`/`Add source` buttons that remain text).
- Leave the full-width `Add source` `Button` (360-366) and catalog-row actions unchanged.

**Patterns to follow:** the existing search-clear `IconButton` in this same file
(lines 155-159) and the providers-list delete `IconButton` (`ProvidersScreen.kt:497-498`).

**Test scenarios:** `Test expectation: none` — pure presentation swap; `onClick`,
`enabled`, and visibility logic are unchanged, and the repo has no Compose UI tests that
locate these buttons by text (verified: no `composeTestRule`/`onNodeWithText` in the repo).

**Verification:** module compiles (`./gradlew :feature:globalsettings:compileDebugKotlin`
or full `./gradlew testDebugUnitTest`); the top bar shows a source icon with the count
badge and a refresh icon; Refresh is still disabled while `catalogLoading`; each source
row in the sheet shows a delete icon that removes it.

### U2. Iconize the routing dialog "Reset to default" and the Sub-agent models "Add" toggle

**Requirements:** R2, R3, R5
**Dependencies:** none (independent of U1; different file)
**Files:** `feature/providers/src/main/kotlin/dev/agentic/ui/providers/ProvidersScreen.kt`

**Approach:**
- Reset to default (lines 1055-1058, inside `if (family.customized)`): replace the
  `TextButton` with `IconButton(onClick = onReset, enabled = !busy)` containing
  `Icon(Icons.Rounded.RestartAlt, contentDescription = "Reset to default", tint = MaterialTheme.colorScheme.error)`.
  Keep the `if (family.customized)` wrapper so it still only appears for customized
  families. Leave the sibling `Save` (confirmButton) and `Cancel` (dismissButton) as text.
- Sub-agent models `Add` trailing toggle (lines 279-289): it is already `Icon(Add) + Text("Add")`.
  Drop the `Text("Add")`, keep it as an `IconButton` (or the existing `TextButton` with
  just the icon) toggling `formVisible`. Set `contentDescription` on the icon —
  "Add a model" when collapsed. Optional: show `Icons.Rounded.Close` with
  contentDescription "Close" when `formVisible` is true, to mirror the toggle.
- Add the `RestartAlt` import (`Add` and `Close` are already imported at lines 31-32).

**Patterns to follow:** the Edit/Delete `IconButton`s already in this file
(`ProvidersScreen.kt:494-498`, size 48.dp).

**Test scenarios:** `Test expectation: none` — presentation-only; `onReset`, the
`family.customized` visibility, the `formVisible` toggle, and `!busy` guards are unchanged.

**Verification:** module compiles (`./gradlew :feature:providers:compileDebugKotlin`); the
routing dialog shows an error-tinted reset icon only for a customized family, and Save/Cancel
remain text; the Sub-agent models header shows an icon-only add toggle that still opens/closes
the form.

---

## Open Questions

- **Accessibility regression guard (test infra) — deferred.** The repo currently has **no**
  Compose UI tests, so nothing verifies the `contentDescription` values R5 mandates; a later
  refactor could silently drop or mistype one and break TalkBack for these controls. Standing
  up `androidTest` instrumentation (Compose test rule + `onNodeWithContentDescription`) just
  to assert four strings is disproportionate to a cosmetic swap, so this plan defers it.
  Decide during execution whether a lightweight a11y test is worth adding now or tracked as
  follow-up. (Raised by doc-review design-lens.)

---

## Assumptions

- "add" / "source" / "reset to default" in the request name examples ("什么的" = "and such");
  the intended set is the four toolbar/secondary action buttons above plus the consistent
  source-row Remove — **not** the dialog confirm/dismiss buttons or the primary form-submit
  CTA, which stay text per Material convention (Scope Boundaries).
- Material3 `BadgedBox`/`Badge` and `TooltipBox` are available at the pinned Compose
  Material3 `1.4.0-alpha18` (they are stable Material3 APIs); if `BadgedBox` is awkward in a
  `TopAppBar` action slot, KTD-1's contentDescription fallback applies.
- No new dependency is needed — `material-icons-extended` is already declared.
