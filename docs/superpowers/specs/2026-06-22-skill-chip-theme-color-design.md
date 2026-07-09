# Skill chips use the inline-card theme color in New Request

Date: 2026-06-22
Repo: agentic-dev-android
Status: design (awaiting user review)

## Problem

In the New Request screen, skills are shown as plain Material 3 `FilterChip`s — a text
label with default neutral styling, no color. Everywhere else in the app a skill has a
visual identity: the **skill inline card** in the transcript renders in the cyan accent
family. The New Request skill picker doesn't carry that identity, so a selected (lit) skill
looks the same as any other chip.

## Goal

A **selected (lit) skill chip** in the New Request screen carries the **same theme color as
the skill inline card** — nothing more. Color only, no icon.

## Source of truth — the skill inline card

`app/src/main/java/dev/agentic/ui/session/Transcript.kt`, composable `EventChip`, rendered
for `SkillNode` (around lines 263-268) with:

- container (background): `AccentCyanContainer` = `Color(0xFF00504C)` (dark teal)
- on-container (icon + label): `OnAccentCyanContainer` = `Color(0xFF76F3EA)` (bright cyan)

Both defined in `app/src/main/java/dev/agentic/ui/Theme.kt` (lines 70-75).

Note: this is deliberately the inline-card look (filled container pair), **not** the
SessionTags skill chip look (flat `AccentCyan #59D6CE` on a bordered/transparent chip). The
user chose the inline card.

## Scope

In scope:
- Selected Skills chips in `NewRequestScreen.kt` get the cyan inline-card color.

Out of scope:
- No sparkle / leading icon (the inline card has `Icons.Rounded.AutoAwesome`; we are matching
  color only, per the user's choice).
- No change to Repos chips, template chips, sliders, or any other UI.
- No change to the skill data model, `/api/skills`, `SKILL.md` frontmatter, ViewModel, or
  submit payload. Purely presentational.

## Approach (chosen)

Add an **opt-in accent** to the existing generic `ChipPicker` composable, mirroring the
pattern already in this file where `SliderField` takes an `accentActive` flag to opt into the
ultracode violet.

`ChipPicker` is reused for both **Repos** (line 206) and **Skills** (line 218). Adding the
accent as optional params keeps Repos on the default look while letting only the Skills call
opt in. This avoids string-matching on the `label` and avoids duplicating the
search/scroll/chip logic into a second composable.

Rejected alternatives:
- Branch inside `ChipPicker` on `label == "Skills"` — couples behavior to a display string.
- A separate `SkillChipPicker` composable — duplicates the picker logic for one color diff.

## Concrete change

File: `app/src/main/java/dev/agentic/ui/newrequest/NewRequestScreen.kt`

1. `ChipPicker` gains two optional params, both defaulting to `null`:
   - `selectedContainer: Color? = null`
   - `selectedContent: Color? = null`

2. Inside `ChipPicker`, when both are non-null, build a `FilterChipDefaults.filterChipColors(
   selectedContainerColor = selectedContainer, selectedLabelColor = selectedContent,
   selectedLeadingIconColor = selectedContent)` and pass it to each `FilterChip(colors = …)`.
   When either is null, omit `colors` so the chip keeps today's default styling.

3. The **Skills** `ChipPicker` call passes
   `selectedContainer = AccentCyanContainer, selectedContent = OnAccentCyanContainer`.
   The **Repos** call is left unchanged (no accent → default look).

4. Imports added: `dev.agentic.ui.AccentCyanContainer`, `dev.agentic.ui.OnAccentCyanContainer`,
   `androidx.compose.material3.FilterChipDefaults`.

## Behavior

- Skills start all-selected (existing behavior), so the Skills row opens as a row of filled
  cyan chips.
- Deselecting a skill drops that chip to the default neutral outlined look — reinforcing the
  existing lit = on / dim = hidden meaning, now in the skill's own color.
- Repos and every other control look exactly as before.

## Verification

- Read-back of the edited region to confirm params, colors, and imports are present.
- Build a release APK (in the main checkout per repo CLAUDE.md) and deliver to `outbox/`; the
  user visually confirms selected skill chips are cyan and repos are unchanged.
- No automated UI test exists for this screen; this is a visual-only change, so verification
  is read-back + APK visual check.
