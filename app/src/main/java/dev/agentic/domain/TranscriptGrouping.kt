package dev.agentic.domain

/** Collapse consecutive same-name tool calls (e.g. several Edit/Bash) into one [ToolGroupNode] ("name · N+", expandable). Lone tools stay plain [ToolNode]; non-tools pass through and break the run. Pure view transform — same live and terminal. */
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

/** Weave delivered outbox [AttachmentNode]s in at their actual delivery point (right after the message that sent them).
 *  Primary anchor: FIRST agent message naming the file ("delivered: outbox/<name>").
 *  Fallback: after the prompt of the turn whose `at` most recently precedes the file's mtime (older than first prompt → top; none → append).
 *  Anchors are FIXED indices (not streamed-end/`display.size`) so a mid-turn file doesn't float below everything emitted after it ("APK sinks to the bottom").
 *  [truncatedStart] = window doesn't start at log beginning. With no VISIBLE anchor, a file with no anchor in the window belongs to unloaded history above → HIDDEN (paging back reveals: inline `agentic_file` on current logs pages in & dedups the poll copy; on old logs the delivering turn's prompt pages in & its anchor matches). Only a genuinely-visible anchor places the poll copy. */
fun interleaveShared(display: List<Node>, shared: List<AttachmentNode>, truncatedStart: Boolean = false): List<Node> {
    if (shared.isEmpty()) return display
    // `agentic_file` already positioned inline by the log — drop the poll-derived copy of the same path to avoid rendering twice. Old backends emit no such event → nothing is dropped → those files use the delivery-message heuristic below.
    val inlinePaths = display.mapNotNullTo(HashSet()) { (it as? AttachmentNode)?.path }
    val pending = shared.filter { it.path !in inlinePaths }
    if (pending.isEmpty()) return display
    val turns = display.mapIndexedNotNull { i, n -> if (n is PromptNode && n.at > 0) i to n.at else null }
    val inserts = HashMap<Int, MutableList<AttachmentNode>>()
    for (f in pending.sortedBy { it.at }) {
        val name = f.path.substringAfterLast('/')
        // Actual delivery point: first agent message (text/answer) naming the file.
        val mention = if (name.isNotEmpty()) display.indexOfFirst { n ->
            val t = (n as? TextNode)?.text ?: (n as? AnswerNode)?.text
            t != null && t.contains(name)
        } else -1
        val insertAt = when {
            mention >= 0 -> mention + 1
            // No timestamped turn in TRUNCATED window → file's region unloaded; hide like any out-of-window card. Untruncated (full transcript) keeps legacy append.
            turns.isEmpty() -> if (truncatedStart) continue else display.size
            else -> {
                // No message names it → anchor after the prompt of its delivering turn (fixed index).
                var k = -1
                for (j in turns.indices) if (turns[j].second <= f.at) k = j
                when {
                    k >= 0 -> turns[k].first + 1
                    // Every visible turn is NEWER than the file → delivering turn lives above a truncated window: hidden until paged in. Full transcript keeps legacy top.
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

/** Pair unanswered [AskNode]s with the next [PromptNode] ONE-TO-ONE (FIFO, each prompt consumed once) so two asks + one answer marks only the first. */
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

/** Collapse PromptNodes that are the SAME logical user turn re-materialized through multiple paths (live `kind:prompt` + persisted `agentic_prompt`, or reseed/backfill). Identity = (server `at` > 0, normalized text); both channels share one `at` (one `now` in engine.ts) so distinct turns at different times never collapse. `at == 0` (optimistic overlays / missing timestamp) is fail-open (ViewModel overlay layer reconciles). Keeps FIRST occurrence + drops twins + their trailing user-attachment nodes. Idempotent (no-op over already-deduped). This is the correct-by-construction dedup — ViewModel's optimistic-overlay reconciliation structurally cannot see two real PromptNodes in the reducer. */
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
