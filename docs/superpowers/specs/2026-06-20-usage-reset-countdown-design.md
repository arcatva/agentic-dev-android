# Tap usage meters to show time-until-reset

## Goal
On the Home screen, the two plan-usage meters are labelled `5h` and `7d`. Tapping either
meter toggles **both** labels between the window name (`5h` / `7d`) and the time remaining
until that window resets, e.g. `3h29m` and `3d21h`. Tapping again toggles back.

## Why it's cheap
The data is already present end-to-end. The server passes Anthropic's per-window
`resets_at` through unchanged (`GET /api/usage`), and the Android model already carries it:
`UsageWindow(utilization, resets_at)`. The field is currently unused. So this is a
client-only change — no server change, no API change.

## Behaviour
- **Shared toggle.** One boolean state held in `UsageMeters`. Tapping either meter flips it,
  so both meters switch together (per user request).
- The right-hand `NN%` and the progress bar are unchanged; only the left label text swaps.
- The whole meter (the `Column`) is the tap target.

## Time format (`resetIn`)
Remaining = `resets_at` − now, clamped to ≥ 0, rendered compactly:
| remaining        | output   |
|------------------|----------|
| ≥ 1 day          | `3d21h`  |
| ≥ 1 h, < 1 day   | `3h29m`  |
| ≥ 1 min, < 1 h   | `29m`    |
| < 1 min          | `<1m`    |
| missing/unparsable | `—`    |

## Parsing `resets_at`
Anthropic returns an ISO-8601 instant; we don't have a confirmed sample (tests use
placeholder `"x"`/`"y"`), so parse defensively:
1. `java.time.Instant.parse(...)` (available natively — `minSdk = 26`).
2. Fallback: numeric epoch — treat as ms if large, else seconds.
3. Anything else / null / blank → `—`.

## Refresh
Usage already polls every 60 s; that recomposes `UsageMeters` and recomputes `resetIn`
against the current clock, so the countdown stays fresh at minute granularity without a
dedicated per-second ticker.

## Where the code goes
- **`domain/Usage.kt`** (new): pure `parseResetAt(resets_at): Long?` and
  `resetIn(resets_at, nowMs): String`. Lives in `domain/` so it's unit-testable (matches the
  existing pattern — `domain/` holds tested pure logic, `ui/` consumes it).
- **`ui/home/HomeScreen.kt`**: `UsageMeters` gains the shared `showReset` state; `Meter`
  gains an `onToggle` lambda + `clickable`; the label arg becomes `resetIn(...)` when toggled.

## Testing
JVM unit test (`app/src/test/.../domain/UsageTest.kt`, JUnit 4) for `resetIn`: the four
duration buckets, the `<1m`/past case, the `—`/null case, ISO and numeric-epoch parsing.
UI wiring is trivial and verified by building + running the app.

## Delivery
Client change → rebuild release APK in the main checkout and drop it in `outbox/`.
