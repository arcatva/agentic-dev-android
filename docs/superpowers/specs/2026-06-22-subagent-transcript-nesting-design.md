# Sub-agent transcript nesting (Android) — design

**Date:** 2026-06-22
**Status:** Approved (design); pending implementation plan
**Area:** `agentic-dev-android` — session transcript (`domain/Transcript.kt`, `domain/Node.kt`, `ui/session/Transcript.kt`)

## Problem

When the main agent spawns a sub-agent (Task/Agent tool), the sub-agent's internal steps —
its tool calls (e.g. `Bash echo h`), text, thinking — render as **top-level inline cards in the
main conversation** instead of being grouped under the spawning agent.

### Root cause

- The backend already tags every transcript event with `parentToolUseId`
  (`server/engine/streamParser.ts:20,30`; `server/engine/types.ts:55-67`):
  `null` = the main agent, otherwise the id of the `Agent` tool_use that spawned the sub-agent
  producing the event. The web UI uses this to route parts into the correct agent column.
- The Android client **never reads `parentToolUseId`** (confirmed: zero references in `app/src`).
  Both build paths — live `applyEvent` and reseed `buildFromLog` (`domain/Transcript.kt`) — append
  every event to a single **flat** `List<Node>`. So a sub-agent's `kind:tool` frame becomes a
  plain top-level `ToolNode`, indistinguishable from a main-agent tool.
- The model already represents a spawned agent as a single `SpawnNode` card whose body is the
  agent's final returned text (`attachAgentResult`, `kind:agentResult`). The internal steps were
  never meant to surface on their own — but with no `parentToolUseId` filtering, they leak in.

## Goal

Sub-agent events nest under their spawning agent's `SpawnNode` as **collapsible** secondary
("二级") cards. The agent card is **collapsed by default**; tapping it expands the nested steps.
The main conversation stays clean whether or not an agent ran many steps.

## Design

### Model (`domain/Node.kt`, `domain/Transcript.kt`)

- Add `children: List<Node> = emptyList()` to `SpawnNode` (data class → `.copy(children = …)`).
- **Routing rule** — when building a node from an event that carries `parentToolUseId`:
  - `null` → append to the top-level list (unchanged behavior).
  - matches the `id` of an existing `SpawnNode` → append to **that** node's `children`.
  - no matching `SpawnNode` (deeper nesting or unknown parent) → fall back to top-level. **No data
    is ever dropped.**
- Single shared helper `routeUnderParent(nodes, parentId, child)` used by both paths.
- `appendText` / `appendThinking` gain an optional `parentId`: route **first**, then coalesce the
  delta within the target list (a sub-agent's streamed text must not merge into the main agent's
  trailing text, and vice-versa).
- `agentResult` keeps attaching to the `SpawnNode` as `result`; rendered as the footer of the
  expanded region.

### Rendering (`ui/session/Transcript.kt`)

- `SpawnCard` becomes expandable, reusing the existing `Collapsible` component (already used for
  `ToolGroupNode`): collapsed shows `agent · <name>` + result summary + chevron + child-step count;
  expanded reveals the children.
- Children render with the **existing** per-node composables (`ToolNode`/`ThinkingNode` → `StepCard`,
  `TextNode` → its card, etc.), inset with a left violet rule + indentation to read as secondary.
- Expanded state held per card via `remember` keyed by `SpawnNode.id`; default collapsed.

### Edge cases

- **Ordering:** the spawn marker (the `Agent` tool_use) always precedes the sub-agent's events, so
  the `SpawnNode` exists before any child arrives.
- **Reseed/rebuild:** `buildFromLog` re-derives nesting from each raw log line's
  `parent_tool_use_id` (the persisted log is raw stream-json, so the field is present). This is
  more robust than today's live-only attachment. *Verify the field's presence on rebuilt
  `assistant` lines during implementation.*
- **Multi-level depth:** only one level (direct children of a top-level `SpawnNode`) is supported;
  deeper events fall back to top-level. Main-session sub-agents rarely nest further (YAGNI).

### Testing (`app/src/test/.../domain`)

Mirror existing domain unit tests (e.g. `StatusTest`, `NodeLabelsTest`):

1. A `tool` event with `parentToolUseId = X` nests under the `SpawnNode` whose `id == X`.
2. A `tool` event whose `parentToolUseId` matches no `SpawnNode` falls back to top-level.
3. A sub-agent's `text` deltas coalesce within that agent's `children`, not into the main
   transcript's trailing text.
4. `agentResult` still attaches to the correct `SpawnNode`.
5. A main-agent (`parentToolUseId = null`) tool stays top-level (regression guard).

## Out of scope

- Workflow detail screen (already renders per-agent transcripts separately).
- Web-client parity.
- Persisting expand/collapse state across session reopen.

## Alternatives considered

- **Flat list + owner-id + render-time grouping.** Keep one list, tag each node with its owner
  agent id, group at render. Rejected: ordering/grouping is more error-prone than an explicit
  child list, and it muddies the renderer. The `children`-on-`SpawnNode` model is cleaner and maps
  directly to the requested "二级卡片".
