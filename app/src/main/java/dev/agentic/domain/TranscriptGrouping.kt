package dev.agentic.domain

/** Collapse a run of consecutive same-name tool cards (e.g. several Edit / Bash in a row) into one
 *  [ToolGroupNode] (rendered as "name · N+", expandable to the individual calls). A lone tool stays a
 *  plain [ToolNode]; any non-tool node passes through and breaks the run. Pure view transform — works
 *  the same live and on a terminal/reopened session. */
fun groupTools(nodes: List<Node>): List<Node> {
    val out = ArrayList<Node>(nodes.size)
    var i = 0
    while (i < nodes.size) {
        val n = nodes[i]
        if (n is ToolNode) {
            var j = i + 1
            while (j < nodes.size && (nodes[j] as? ToolNode)?.name == n.name) j++
            if (j - i > 1) out.add(ToolGroupNode(n.name, nodes.subList(i, j).map { it as ToolNode }))
            else out.add(n)
            i = j
        } else { out.add(n); i++ }
    }
    return out
}

/** Weave the agent's delivered files (outbox AttachmentNodes) into the transcript at the point they
 *  were DELIVERED — i.e. right after the actual message that sent them out.
 *
 *  Primary anchor (the real delivery position): the FIRST agent message whose text names the file (the
 *  agent writes the file, then says e.g. "delivered: outbox/<name>"). The card lands right after that
 *  message — the message it was sent in. Fallback when no message names it: after the prompt of the
 *  turn whose start time (PromptNode.at) most recently precedes the file's mtime (older-than-first-prompt
 *  → top; no timestamped turn → appended).
 *
 *  Both anchors are FIXED node indices, never the turn's streamed end / `display.size` — that end grows
 *  on every streamed frame, which made a mid-turn file float to the absolute bottom below everything the
 *  agent emitted after it ("the APK sinks to the bottom and new cards appear above it").
 *
 *  [truncatedStart] = the window does NOT start at the log's beginning (older events exist above —
 *  TranscriptState.hasMore). The server caps every /events response at 100 events, so a long session's
 *  seed window routinely misses BOTH the inline `agentic_file` event AND every prompt older than the
 *  file — one giant turn can fill the whole window with promptless nodes. With no anchor at all, the
 *  old fallback appended at `display.size`, re-gluing the card under the newest streamed content on
 *  every frame (the "sinks to the bottom" bug, windowed edition). When the start is truncated, a file
 *  with no visible anchor belongs to the unloaded history above → it is HIDDEN, exactly like every
 *  other card whose position is outside the window. Paging back reveals it at its true position: on
 *  current logs the inline `agentic_file` event pages in (and dedups the poll copy); on old logs the
 *  delivering turn's prompt pages in and the turn anchor below starts matching. Only an anchor that is
 *  genuinely VISIBLE in the window (a message naming the file, or a resident turn old enough to have
 *  delivered it) places the poll copy. */
fun interleaveShared(display: List<Node>, shared: List<AttachmentNode>, truncatedStart: Boolean = false): List<Node> {
    if (shared.isEmpty()) return display
    // A file already delivered INLINE via an `agentic_file` log event is positioned by the log itself
    // (its AttachmentNode is already in `display`). Drop the poll-derived copy of the same path so the
    // card isn't rendered twice. Old backends emit no such event → nothing is dropped → those files
    // still get positioned by the delivery-message heuristic below.
    val inlinePaths = display.mapNotNullTo(HashSet()) { (it as? AttachmentNode)?.path }
    val pending = shared.filter { it.path !in inlinePaths }
    if (pending.isEmpty()) return display
    val turns = display.mapIndexedNotNull { i, n -> if (n is PromptNode && n.at > 0) i to n.at else null }
    val inserts = HashMap<Int, MutableList<AttachmentNode>>()
    for (f in pending.sortedBy { it.at }) {
        val name = f.path.substringAfterLast('/')
        // The actual delivery point: the first agent message (text/answer) that names the file.
        val mention = if (name.isNotEmpty()) display.indexOfFirst { n ->
            val t = (n as? TextNode)?.text ?: (n as? AnswerNode)?.text
            t != null && t.contains(name)
        } else -1
        val insertAt = when {
            mention >= 0 -> mention + 1
            // No timestamped turn in a TRUNCATED window → the file's region is not loaded; hide it like
            // any other out-of-window card. Untruncated (full transcript) keeps the legacy append.
            turns.isEmpty() -> if (truncatedStart) continue else display.size
            else -> {
                // No message names it → anchor after the prompt of its delivering turn (a fixed index).
                var k = -1
                for (j in turns.indices) if (turns[j].second <= f.at) k = j
                when {
                    k >= 0 -> turns[k].first + 1
                    // Every visible turn is NEWER than the file → its delivering turn lives above a
                    // truncated window: hidden until paged in. A full transcript keeps legacy top.
                    truncatedStart -> continue
                    else -> 0
                }
            }
        }
        inserts.getOrPut(insertAt) { mutableListOf() }.add(f)
    }
    val out = ArrayList<Node>(display.size + shared.size)
    for (i in 0..display.size) {
        inserts[i]?.let { out.addAll(it) }
        if (i < display.size) out.add(display[i])
    }
    return out
}

/** A question the agent asked is "answered" once the conversation moves past it: the follow-up turn
 *  carrying the chosen answer is logged as a user prompt right after the ask. Pair each unanswered
 *  ask with the next user prompt ONE-TO-ONE (FIFO), consuming each prompt once — so when two asks are
 *  pending and only one answer was sent, only the first ask is marked (never both). */
fun markAnsweredAsks(nodes: List<Node>): List<Node> {
    if (nodes.none { it is AskNode }) return nodes
    val out = nodes.toMutableList()
    val pending = ArrayDeque<Int>()   // indices of unanswered asks still awaiting a prompt
    for (i in nodes.indices) {
        val n = nodes[i]
        if (n is AskNode) { if (!n.answered) pending.addLast(i) }
        else if (n is PromptNode && pending.isNotEmpty()) {
            val ai = pending.removeFirst()
            out[ai] = (nodes[ai] as AskNode).copy(answered = true, answer = n.text)
        }
    }
    return out
}

/** Collapse PromptNodes that are the SAME logical user turn re-materialized through more than one
 *  ingestion path — the live `kind:prompt` frame AND the persisted `agentic_prompt` log entry (the
 *  backend writes both for one turn, with one shared server timestamp), or a reseed/backfill
 *  re-delivery. Identity = (server `at` > 0, normalized text); both channels carry the SAME `at` (one
 *  `now` in engine.ts), so this never collapses two genuinely distinct turns — a user who sends the
 *  same text twice does so at different times, hence a different `at`. Nodes with `at == 0` (optimistic
 *  overlays / missing timestamp) are fail-open (never collapsed on text alone; the ViewModel overlay
 *  layer reconciles those). Keeps the FIRST occurrence and drops later twins plus any user-attachment
 *  nodes that immediately trail a dropped twin. Idempotent: re-running over already-deduped nodes is a
 *  no-op, so it converges no matter which fold (applyFrame append / seedFromLog replace) produced the
 *  twin. This is the single, correct-by-construction dedup — the duplicate is two REAL PromptNodes in
 *  the reducer, which the ViewModel's optimistic-overlay reconciliation structurally cannot see. */
fun dedupePrompts(nodes: List<Node>): List<Node> {
    val seen = HashSet<Pair<Long, String>>()
    val out = ArrayList<Node>(nodes.size)
    var i = 0
    while (i < nodes.size) {
        val n = nodes[i]
        val key = (n as? PromptNode)?.takeIf { it.at > 0 }?.let { it.at to splitAttachments(it.text).first.trim() }
        if (key != null && !seen.add(key)) {
            i++ // drop the twin and any user attachments that belonged to it
            while (i < nodes.size && (nodes[i] as? AttachmentNode)?.fromUser == true) i++
            continue
        }
        out.add(n); i++
    }
    return out
}
