# Session List: Workflow-Aware Status, Unread Dot, and Last-Message Sort

**Date:** 2026-06-20
**Repos touched:** `agentic-dev` (TypeScript/Node backend), `agentic-dev-android` (Kotlin/Compose client)
**Status:** Approved design, ready for implementation planning

## Overview

Three independent improvements to the session list (`HomeScreen`):

1. **Workflow-aware "running":** A session with a still-running background workflow shows as `running` in the list, even after its turn has finished.
2. **Unread dot:** When a session fully finishes, the existing check mark no longer just disappears — if you haven't looked at the session since it finished, the check resolves into a persistent **unread dot**. If you have already read it (or are viewing it when it finishes), the check fades to nothing as today.
3. **Last-message sort:** The list is ordered by **the time you last sent a message** in each session (most recent on top), not by session creation time.

These compose: the check (and then the unread dot) appears only when *both* the turn and any background workflow are done.

## Background (current behavior)

- **Backend** (`agentic-dev`) is API-only. `GET /api/sessions` → `engine.list()` → `store.list()`, mapped through `withActivity()`. Sessions are sorted `ORDER BY createdAt DESC, seq DESC` (`server/engine/store.ts`). Session status type: `"pending" | "running" | "done" | "failed" | "killed"` (`server/engine/types.ts`). A turn that finishes sets `awaitingInput = true`; a background workflow can keep running while the session is idle. Live workflow state is derivable from disk via `listWorkflows()` / `WorkflowRun.isActive()` (`server/engine/workflows.ts`), reading `subagents/workflows/<runId>/`.
- **Android** (`agentic-dev-android`) renders the list in `HomeScreen.kt` → `SessionRow` → `StatusIndicator.kt`. `domain/Status.kt#statusVisual()` maps `(status, awaitingInput)` to `DONE | FAILED | KILLED | RUNNING | IDLE | PENDING`. On a `running → done/idle` transition, `StatusIndicator` pops a transient check, holds it `CHECK_HOLD_MS = 2000ms`, then shrinks it to nothing (`DONE`/`IDLE` resting glyph = `NONE`). The client does **not** sort — it shows the server order. There is **no read/unread concept anywhere** today. The list does not poll per-session workflows (only the workflow detail screen does).

## Confirmed product decisions

- **Unread state is stored on-device only** (Android local storage). No backend read tracking, no cross-device sync.
- **Re-completion re-marks unread:** after you read a finished session, sending another message that runs and finishes again brings the unread dot back ("unread since last completion").
- **Sort key = the time you last sent a message** (a user turn submission), not last activity. A session you started long ago that is still running a background workflow sinks below newer chats. This is the intended, accepted trade-off.

---

## Feature 1 — Workflow-aware running status

### Backend (`agentic-dev`)

- Add `workflowRunning: boolean` to the session shape returned by `engine.list()` and `engine.get()` (via `withActivity()`).
- Compute it **only for sessions that would otherwise render done/idle** (turn not actively executing). For those, check whether the session has any active workflow run on disk — existence of a live run directory plus a non-terminal state, *without* parsing full journals. Sessions actively executing a turn are already `running`, so skip the filesystem check for them. This bounds the per-poll filesystem cost to the subset of finished/idle sessions.
- Optional hardening if the finished-session set is large: cache the per-session workflow-active result with a short TTL (≈ the 2s poll interval). Out of scope unless profiling shows it is needed.

### Android (`agentic-dev-android`)

- Add `workflowRunning: Boolean = false` to the `Session` data class (default keeps backward/forward compatibility with older servers).
- Introduce an **effective status** in `SessionRow`: if `workflowRunning == true`, treat the session as `running`; otherwise use the existing `statusVisual(status, awaitingInput)`. Feed the effective status to `StatusIndicator`.
- Result: the existing check animation fires when the effective status flips `running → done/idle`, i.e. when the turn **and** all workflows are done.

### Edge cases

- A `failed`/`killed` session with a still-running workflow: `workflowRunning` takes precedence and shows `running` while the workflow is live (the active thing is what the user wants to see). Once the workflow ends, the underlying terminal status is shown again.

---

## Feature 2 — Unread dot (local read tracking)

### Behavior

- On the effective `running → done/idle` transition: the check pops and holds `CHECK_HOLD_MS = 2000ms` (unchanged).
- After the hold:
  - **Unread** (finished after you last read it) → the check shrinks/morphs into a small **persistent unread dot**.
  - **Read** (you are viewing it when it finishes, or opened it since it finished) → the check fades to nothing (today's behavior).
- **Cold load** (app was closed while the session finished): an unread, successfully-finished session renders the dot directly, with no check animation (the check is only a flourish on a live transition).
- The unread dot applies to **successful `done`/`idle`** sessions only (these render nothing today). `failed`/`killed` keep their existing red error / stop icons, which are already attention-grabbing — they are out of scope for the dot. (Revisit only if requested.)

### Local read store

A new on-device persistent store (DataStore):

- `lastReadAt: Map<sessionId, Long>` — epoch ms of the last time the user opened that session's detail.
- `unreadBaselineAt: Long` — set once to "now" on first launch (first time the store is created). Sessions finished before this baseline with no read record are treated as **read**, preventing a wall of dots on a fresh install / new device.

### Completion timestamp

`completedAt(session) = max(server endedAt ?? 0, observedCompletedAt ?? 0)` where:

- `endedAt` comes from the backend session record (covers app-was-closed completions).
- `observedCompletedAt` is recorded in the `HomeViewModel` when it detects a live effective `running → done/idle` flip for a session this app-session (covers a workflow finishing later than the turn while the app is open).

### Unread predicate

For a session shown in the list:

```
threshold   = lastReadAt[sessionId] ?? unreadBaselineAt
isUnread    = effectiveStatus in {DONE, IDLE}
              && completedAt(session) > threshold
              && sessionId != currentlyOpenSessionId
```

- **Re-completion → unread again** falls out naturally: a new completion pushes `completedAt` past `lastReadAt`.
- **Viewing while it finishes → stays read:** the currently-open session is excluded from the predicate, and on leaving the detail screen `lastReadAt[sessionId] = now` is persisted.

### Marking read

- Opening a session's detail screen sets `lastReadAt[sessionId] = now` (persisted on entry; refreshed on exit so completions during viewing remain read).

### Android components

- New `ReadStateStore` (DataStore-backed) + a small repository exposing a `Flow` of read state.
- `HomeViewModel` combines sessions + read state into per-row `unread: Boolean`, and tracks `observedCompletedAt` from status transitions.
- `StatusIndicator` gains an `unread: Boolean` parameter and a new resting glyph `DOT` for the done/idle + unread case, plus the check → dot morph.
- Detail screen (session open) triggers `markRead(sessionId)`.

### Visual detail (defer to implementation / optional visual pass)

Dot = small filled circle in the existing accent/tertiary color, occupying the same 16dp slot as the status indicator. Exact color, size, and the check → dot transition curve can be refined with a quick visual mock before/while implementing.

---

## Feature 3 — Sort by last user message time

### Backend (`agentic-dev`)

- Add column `lastUserMessageAt: number` (epoch ms) to the `sessions` table and the session shape.
- Write `lastUserMessageAt = Date.now()`:
  - on initial session submission (`store.create()` / `submitSession()`), and
  - on every subsequent user message sent to an existing session (the multi-turn send/append path).
- Change ordering to `ORDER BY lastUserMessageAt DESC, seq DESC` in `store.list()`.
- **Migration:** add the column; backfill existing rows with their `createdAt`. Invariant: `lastUserMessageAt >= createdAt` (every session begins with a user message).

### Android (`agentic-dev-android`)

- The server already returns the list in the desired order; the client keeps showing the server order. Add `lastUserMessageAt: Long = 0` to the `Session` model for completeness/future use; no client-side re-sort.

### Interaction note

Feature 3 can sink a long-running, workflow-active session toward the bottom (sorted by your last message), while Feature 1 still shows it spinning as `running`. "It is running but ranked low" is the direct, accepted consequence of sorting by last-message time.

---

## Change summary

| Repo | Changes |
|---|---|
| `agentic-dev` (backend) | `workflowRunning` flag in `engine.list()`/`get()` (lightweight active-workflow check for finished/idle sessions); `lastUserMessageAt` column + backfill migration + write on submit/append + `ORDER BY lastUserMessageAt DESC, seq DESC` |
| `agentic-dev-android` (client) | `Session` gains `workflowRunning`, `lastUserMessageAt`; effective-status mapping in `SessionRow`; `ReadStateStore` (DataStore) + read-state repo; `HomeViewModel` unread computation + observed-completion tracking; `StatusIndicator` `unread` param + `DOT` glyph + check→dot morph; mark-read on opening detail |

## Testing approach

- **Backend:** unit tests for `store.list()` ordering by `lastUserMessageAt`; `lastUserMessageAt` updated on submit and on follow-up message; migration backfill = `createdAt`; `workflowRunning` true/false against fixture workflow-run directories (active vs terminal).
- **Android:** unit tests for the unread predicate (baseline suppresses pre-install completions; re-completion re-marks unread; currently-open session excluded; read clears on open); effective-status mapping (`workflowRunning ⇒ RUNNING`); `StatusIndicator` Compose previews for `done+unread` (dot), `done+read` (none), live transition (check → dot vs check → none).

## Out of scope

- Cross-device sync of read state; any server-side read/unread tracking.
- Unread dot for `failed`/`killed` sessions (they keep existing icons).
- Reworking the workflow detail screen.
- Client-side sorting (sorting stays server-authoritative).

## Open visual question (non-blocking)

Exact appearance and transition of the unread dot (color/size/morph). Can be finalized with a browser mock during implementation; does not block the plan.
