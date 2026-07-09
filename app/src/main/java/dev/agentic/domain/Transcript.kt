package dev.agentic.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val J = Json { ignoreUnknownKeys = true }

private val ATTACH_RE = Regex("""\n*\[attached: ([^\]]+)]\s*$""")

private val META_NAME_RE = Regex("""meta\s*=\s*\{[\s\S]*?name\s*:\s*['"]([^'"]+)['"]""")

/** A dynamic ("ultracode") workflow's real name lives only inside its inline script's
 *  `export const meta = { name: '…' }`, not as a tool input. Extract it so a reseed shows the same real
 *  name the backend's live `kind:workflow` event already does (keeping the chip stable across reconnects). */
internal fun metaName(script: String?): String? =
    script?.let { META_NAME_RE.find(it)?.groupValues?.get(1) }

/** Split a prompt's trailing "[attached: a, b]" marker into (cleanText, paths). */
fun splitAttachments(text: String): Pair<String, List<String>> {
    val m = ATTACH_RE.find(text) ?: return text to emptyList()
    val paths = m.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
    return text.removeRange(m.range).trimEnd() to paths
}

fun appendText(nodes: List<Node>, text: String): List<Node> {
    if (text.isEmpty()) return nodes
    val last = nodes.lastOrNull()
    // Coalesce into the trailing TextNode with a SINGLE list allocation: copy the list once and replace
    // the last element in place, instead of `dropLast(1) + plus` (which allocated two whole lists per
    // streamed token). The text itself is still a String concat (the node model holds a String); see the
    // file/PR notes on why an amortised-O(1) StringBuilder would require changing TextNode.text's type.
    return if (last is TextNode) {
        ArrayList<Node>(nodes).also { it[it.lastIndex] = last.copy(text = last.text + text) }
    } else nodes + TextNode(text)
}

fun appendThinking(nodes: List<Node>, text: String): List<Node> {
    if (text.isEmpty()) return nodes
    val last = nodes.lastOrNull()
    return if (last is ThinkingNode) {
        ArrayList<Node>(nodes).also { it[it.lastIndex] = last.copy(text = last.text + text) }
    } else nodes + ThinkingNode(text)
}

/** Materialise a turn's terminal `result` text as an [AnswerNode], dropping the trailing top-level
 *  [TextNode] that duplicates it. The SDK repeats the final assistant message in the `result` frame,
 *  so the streamed prose (a TextNode coalesced from the turn's `text` deltas) and [text] are the same
 *  bytes; rendering both would show the answer twice. Keep the [AnswerNode] (the canonical turn-end
 *  answer — also the carrier for error text, which has no matching TextNode) and remove only the
 *  duplicate streamed copy after the last [PromptNode]. A trailing text that does NOT equal [text]
 *  (genuinely different prose, e.g. a partial answer) is left intact. */
fun appendAnswer(nodes: List<Node>, text: String): List<Node> {
    val lastPrompt = nodes.indexOfLast { it is PromptNode }
    val idx = nodes.indexOfLast { it is TextNode }
    val base = if (idx > lastPrompt && (nodes[idx] as TextNode).text.trim() == text.trim())
        nodes.toMutableList().also { it.removeAt(idx) }
    else nodes
    return base + AnswerNode(text)
}

/** Append a retry notice, collapsing a run of consecutive retries into the latest attempt so a flaky
 *  turn shows one chip, not a stack of stale "retrying" lines. */
fun appendRetry(nodes: List<Node>, attempt: Int, maxRetries: Int, category: String): List<Node> {
    val node = RetryNode(attempt, maxRetries, category)
    return if (nodes.lastOrNull() is RetryNode) nodes.dropLast(1) + node else nodes + node
}

/** Attach a subagent's returned text to the [SpawnNode] whose tool_use id matches [toolUseId]. Falls
 *  back to a standalone SpawnNode so the result is never silently dropped if no card matches.
 *  Note: live-only — `agentResult` is absent from the backfilled log, so a reseed (reconnect / turn
 *  end) flattens this back out. */
fun attachAgentResult(nodes: List<Node>, toolUseId: String, text: String): List<Node> {
    val idx = if (toolUseId.isEmpty()) -1
        else nodes.indexOfLast { it is SpawnNode && it.id == toolUseId }
    return if (idx >= 0) {
        nodes.toMutableList().also { it[idx] = (it[idx] as SpawnNode).copy(result = text) }
    } else {
        nodes + SpawnNode("agent", "", toolUseId, text)
    }
}

/** Mark the most recent Perm/Plan node with [id] as decided (from a permResolved event / log marker).
 *  No-op if none matches (the perm line may have been filtered out of a partial log). */
fun markPermDecided(nodes: List<Node>, id: String, decision: String): List<Node> {
    val idx = nodes.indexOfLast { (it is PermNode && it.id == id) || (it is PlanNode && it.id == id) }
    if (idx < 0) return nodes
    return nodes.toMutableList().also {
        it[idx] = when (val n = it[idx]) {
            is PermNode -> n.copy(decided = true, decision = decision)
            is PlanNode -> n.copy(decided = true, decision = decision)
            else -> n
        }
    }
}

/** Fill the [WorkflowNode.runId] of the card whose tool_use [id] matches (from a `workflowRun` link
 *  event / log marker), so a click opens the EXACT run instead of guessing by name. Updates the last
 *  matching card at the top level, else one level of spawn children (a card emitted by a subagent).
 *  Idempotent and a no-op when [id]/[runId] is blank or nothing matches (the card may be trimmed). */
fun setWorkflowRunId(nodes: List<Node>, id: String, runId: String): List<Node> {
    if (id.isEmpty() || runId.isEmpty()) return nodes
    val idx = nodes.indexOfLast { it is WorkflowNode && it.id == id }
    if (idx >= 0) {
        return nodes.toMutableList().also { it[idx] = (it[idx] as WorkflowNode).copy(runId = runId) }
    }
    var changed = false
    return nodes.map { n ->
        if (changed || n !is SpawnNode) return@map n
        val ci = n.children.indexOfLast { it is WorkflowNode && it.id == id }
        if (ci < 0) n else {
            changed = true
            n.copy(children = n.children.toMutableList().also {
                it[ci] = (it[ci] as WorkflowNode).copy(runId = runId)
            })
        }
    }
}

/** Route a child produced by [op] under the [SpawnNode] whose tool_use id == [parentId] — i.e. the
 *  agent that emitted this event ([parentToolUseId] on the frame / `parent_tool_use_id` in the log).
 *  A null/blank parent (a main-agent event) or no matching SpawnNode runs [op] on the top-level list,
 *  so nothing is ever dropped. [op] receives whichever list the child belongs to (top level, or the
 *  matched spawn's children), so coalescing helpers (appendText/appendThinking) merge within the
 *  right scope. One level only: a nested spawn's grandchildren find no top-level parent and fall back. */
private fun route(nodes: List<Node>, parentId: String?, op: (List<Node>) -> List<Node>): List<Node> {
    if (parentId.isNullOrBlank()) return op(nodes)
    val idx = nodes.indexOfLast { it is SpawnNode && it.id == parentId }
    if (idx < 0) return op(nodes)
    val spawn = nodes[idx] as SpawnNode
    return nodes.toMutableList().also { it[idx] = spawn.copy(children = op(spawn.children)) }
}

private fun String?.orBlank() = this ?: ""

/** Build transcript nodes from the persisted log (raw stream-json lines) — for a terminal session. */
fun buildFromLog(log: List<String>): List<Node> {
    var nodes = emptyList<Node>()
    // Streaming dedup (partials ON): a turn's text/thinking arrives BOTH as `stream_event` token
    // deltas AND restated in the complete `assistant` message. Once this turn has produced any delta,
    // its sealed assistant text/thinking is the redundant reassembly — skip it (the deltas already
    // built the node). A turn with NO deltas (old logs, or a turn parked before any token streamed)
    // renders the assistant block normally. Reset per user turn.
    var streamedThisTurn = false
    for (line in log) {
        val o = try { J.parseToJsonElement(line).jsonObject } catch (e: Exception) { continue }
        // Subagent lines carry parent_tool_use_id (the spawning Agent tool_use); route their parts
        // under that SpawnNode instead of the top-level list. null = the main agent.
        val parent = o["parent_tool_use_id"]?.jsonPrimitive?.contentOrNull
        when (o["type"]?.jsonPrimitive?.contentOrNull) {
            "agentic_prompt" -> {
                streamedThisTurn = false   // new user turn
                val (txt, atts) = splitAttachments(o["text"]?.jsonPrimitive?.contentOrNull ?: "")
                nodes = nodes + PromptNode(txt, o["at"]?.jsonPrimitive?.longOrNull ?: 0L) +
                    atts.map { AttachmentNode(it, fromUser = true) }
            }
            "agentic_perm" -> {
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: ""
                nodes = if (o["permKind"]?.jsonPrimitive?.contentOrNull == "plan") {
                    route(nodes, parent) { it + PlanNode(id, o["plan"]?.jsonPrimitive?.contentOrNull ?: "") }
                } else {
                    val tool = o["tool"]?.jsonPrimitive?.contentOrNull ?: "tool"
                    route(nodes, parent) { it + PermNode(id, tool, permSummary(tool, o["input"] as? JsonObject)) }
                }
            }
            "agentic_perm_resolved" -> nodes = markPermDecided(
                nodes,
                o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                o["decision"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            // A delivered outbox file, recorded by the backend at the moment it appeared — so it lands
            // INLINE at its real delivery position (no client-side mtime/content guessing). Rendered
            // here; interleaveShared dedups the poll-derived copy of the same path.
            "agentic_file" -> nodes = nodes + AttachmentNode(
                o["path"]?.jsonPrimitive?.contentOrNull ?: "",
                at = o["at"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
            "stream_event" -> {
                val d = o["event"]?.jsonObject?.get("delta")?.jsonObject
                when (d?.get("type")?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> { nodes = route(nodes, parent) { appendText(it, d["text"]?.jsonPrimitive?.contentOrNull ?: "") }; streamedThisTurn = true }
                    "thinking_delta" -> { nodes = route(nodes, parent) { appendThinking(it, d["thinking"]?.jsonPrimitive?.contentOrNull ?: "") }; streamedThisTurn = true }
                }
            }
            "assistant" -> {
                val blocks = o["message"]?.jsonObject?.get("content") as? JsonArray
                blocks?.forEach { el ->
                    val b = el.jsonObject
                    when (b["type"]?.jsonPrimitive?.contentOrNull) {
                        // Prose / reasoning blocks. The bridge runs with includePartialMessages OFF, so
                        // the model's text/thinking arrives ONLY inside this complete assistant message —
                        // there are NO `stream_event` text deltas in the log. Render them here, mirroring
                        // the live stream parser (server `stream.rs`, which emits a `text`/`thinking`
                        // event per block). Without this, a turn PARKED mid-stream on an AskUserQuestion
                        // (or a permission/plan card) — which has no `result` line yet — lost the prose
                        // that preceded the card on every reseed (background return / reconnect): the
                        // text reappeared only once the question was answered and the turn wrote a
                        // `result`. A COMPLETED turn's final prose duplicates the `result`; appendAnswer
                        // drops that single trailing TextNode so the answer still renders once.
                        // Skip when the turn already streamed these as deltas (see streamedThisTurn) —
                        // the sealed block is the redundant reassembly. A non-streamed turn renders it.
                        "text" -> if (!streamedThisTurn) nodes = route(nodes, parent) { appendText(it, b["text"]?.jsonPrimitive?.contentOrNull ?: "") }
                        "thinking" -> if (!streamedThisTurn) nodes = route(nodes, parent) { appendThinking(it,
                            b["thinking"]?.jsonPrimitive?.contentOrNull ?: b["text"]?.jsonPrimitive?.contentOrNull ?: "") }
                        "tool_use" -> {
                            val input = b["input"]?.jsonObject
                            when (val name = b["name"]?.jsonPrimitive?.contentOrNull) {
                                "Skill" -> input?.get("skill")?.jsonPrimitive?.contentOrNull?.let { sk -> nodes = route(nodes, parent) { it + SkillNode(sk) } }
                                "Agent", "Task" -> nodes = route(nodes, parent) { it + SpawnNode(
                                    input?.get("subagent_type")?.jsonPrimitive?.contentOrNull ?: "agent",
                                    input?.get("description")?.jsonPrimitive?.contentOrNull.orBlank(),
                                    b["id"]?.jsonPrimitive?.contentOrNull ?: "") }
                                "Workflow" -> nodes = route(nodes, parent) { it + WorkflowNode(
                                    input?.get("name")?.jsonPrimitive?.contentOrNull
                                        ?: input?.get("title")?.jsonPrimitive?.contentOrNull
                                        ?: metaName(input?.get("script")?.jsonPrimitive?.contentOrNull) ?: "workflow",
                                    b["id"]?.jsonPrimitive?.contentOrNull ?: "") }
                                // A `delegate` fan-out is surfaced live as a workflow card by the backend; on
                                // RELOAD we parse the raw tool_use directly, so mirror that here (title = run
                                // name) instead of letting it fall through to a raw tool chip.
                                "mcp__agentic__delegate" -> nodes = route(nodes, parent) { it + WorkflowNode(
                                    input?.get("title")?.jsonPrimitive?.contentOrNull?.takeIf { t -> t.isNotBlank() } ?: "delegate",
                                    b["id"]?.jsonPrimitive?.contentOrNull ?: "", delegate = true) }
                                "AskUserQuestion" -> parseAskQuestions(input?.get("questions") as? JsonArray)
                                    .takeIf { it.isNotEmpty() }?.let { qs -> nodes = route(nodes, parent) { it + AskNode(qs) } }
                                null -> {}
                                else -> nodes = route(nodes, parent) { it + ToolNode(name, toolSummary(name, input), toolDetail(name, input)) }
                            }
                        }
                        else -> {}
                    }
                }
            }
            // Engine-synthesized marker for a spawned subagent's returned text (the raw `user` tool_result
            // it derives from is not persisted in the rendered log). Re-attach it to its agent card so the
            // body survives a reopen/reconnect — mirrors the live `kind:agentResult` path.
            "agent_result" -> nodes = attachAgentResult(
                nodes,
                o["toolUseId"]?.jsonPrimitive?.contentOrNull ?: "",
                o["text"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            "result" -> (o["result"]?.jsonPrimitive?.contentOrNull ?: o["error"]?.jsonPrimitive?.contentOrNull)
                ?.takeIf { it.isNotBlank() }?.let { nodes = appendAnswer(nodes, it) }
            // A backend-emitted PR-card marker — same shape as the live `kind:pr` frame; rebuild the card.
            "pr" -> nodes = nodes + prNodeFrom(o)
            // A backend-emitted link marker: fill the matching workflow card's runId so a click opens
            // the exact run (mirrors the live `kind:workflowRun` frame).
            "workflowRun" -> nodes = setWorkflowRunId(
                nodes,
                o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                o["runId"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }
    return markAnsweredAsks(nodes)
}

/** Build a [PrNode] from a parsed `pr` frame/marker object (identical field names on the live wire and
 *  in the persisted log marker), so [applyEvent] and [buildFromLog] decode a PR the same way. */
private fun prNodeFrom(o: JsonObject): PrNode = PrNode(
    url = o["url"]?.jsonPrimitive?.contentOrNull ?: "",
    number = o["number"]?.jsonPrimitive?.intOrNull ?: 0,
    repo = o["repo"]?.jsonPrimitive?.contentOrNull ?: "",
    title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
    body = o["body"]?.jsonPrimitive?.contentOrNull ?: "",
    state = o["state"]?.jsonPrimitive?.contentOrNull ?: "OPEN",
)

/** Fold a live WS event frame (a parsed ClaudeEvent). Returns (nodes, ended, busy).
 *  `busy` is the same turn-state signal [frameBusy] used to return — true = a turn is generating,
 *  false = turn finished, null = not a turn-state signal — but derived from the JsonObject parsed
 *  HERE so the caller never re-parses the frame just to read its top-level kind. */
/** Dispatch a structured ClaudeEvent (kind-tagged JSON, from [ClaudeEvent.to_wire] output)
 *  onto a node list. Used by both the REST events endpoint and the live WebSocket stream.
 *  Returns (nextNodes, ended). */
fun applyEvent(nodes: List<Node>, o: JsonObject): Pair<List<Node>, Boolean> {
    // null/absent = the main agent; otherwise the id of the Agent tool_use that spawned the subagent
    // producing this event — used to nest the event under that agent's SpawnNode (see [route]).
    val parent = o["parentToolUseId"]?.jsonPrimitive?.contentOrNull
    val kind = o["kind"]?.jsonPrimitive?.contentOrNull
    return when (kind) {
        "prompt" -> {
            val (txt, atts) = splitAttachments(o["text"]?.jsonPrimitive?.contentOrNull ?: "")
            (nodes + PromptNode(txt, o["at"]?.jsonPrimitive?.longOrNull ?: 0L) +
                atts.map { AttachmentNode(it, fromUser = true) }) to false
        }
        "text" -> route(nodes, parent) { appendText(it, o["text"]?.jsonPrimitive?.contentOrNull ?: "") } to false
        "thinking" -> route(nodes, parent) { appendThinking(it, o["text"]?.jsonPrimitive?.contentOrNull ?: "") } to false
        "tool" -> {
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
            val inp = o["input"]?.jsonObject
            route(nodes, parent) { it + ToolNode(name, toolSummary(name, inp), toolDetail(name, inp)) } to false
        }
        "skill" -> {
            val names = (o["names"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            route(nodes, parent) { cur -> cur + names.map { SkillNode(it) } } to false
        }
        "agent" -> {
            val spawn = (o["agents"] as? JsonArray)?.map {
                val a = it.jsonObject
                SpawnNode(
                    a["agentType"]?.jsonPrimitive?.contentOrNull ?: "agent",
                    a["description"]?.jsonPrimitive?.contentOrNull.orBlank(),
                    a["id"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            } ?: emptyList()
            route(nodes, parent) { it + spawn } to false
        }
        "agentResult" -> attachAgentResult(
            nodes,
            o["toolUseId"]?.jsonPrimitive?.contentOrNull ?: "",
            o["text"]?.jsonPrimitive?.contentOrNull ?: "",
        ) to false
        "workflow" -> {
            val isDeleg = o["delegate"]?.jsonPrimitive?.contentOrNull.toBoolean()
            route(nodes, parent) { it + WorkflowNode(
                o["name"]?.jsonPrimitive?.contentOrNull ?: if (isDeleg) "delegate" else "workflow",
                o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                isDeleg) } to false
        }
        "workflowRun" -> setWorkflowRunId(
            nodes,
            o["id"]?.jsonPrimitive?.contentOrNull ?: "",
            o["runId"]?.jsonPrimitive?.contentOrNull ?: "",
        ) to false
        "ask" -> {
            val questions = parseAskQuestions(o["questions"] as? JsonArray)
            (if (questions.isNotEmpty()) route(nodes, parent) { it + AskNode(questions) } else nodes) to false
        }
        "perm" -> {
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val tool = o["tool"]?.jsonPrimitive?.contentOrNull ?: "tool"
            route(nodes, parent) { it + PermNode(id, tool, permSummary(tool, o["input"] as? JsonObject)) } to false
        }
        "plan" -> {
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: ""
            route(nodes, parent) { it + PlanNode(id, o["plan"]?.jsonPrimitive?.contentOrNull ?: "") } to false
        }
        "permResolved" -> markPermDecided(
            nodes,
            o["id"]?.jsonPrimitive?.contentOrNull ?: "",
            o["decision"]?.jsonPrimitive?.contentOrNull ?: "",
        ) to false
        "result" -> {
            val txt = o["text"]?.jsonPrimitive?.contentOrNull
            (if (!txt.isNullOrBlank()) appendAnswer(nodes, txt) else nodes) to false
        }
        "retry" -> appendRetry(
            nodes,
            o["attempt"]?.jsonPrimitive?.intOrNull ?: 0,
            o["maxRetries"]?.jsonPrimitive?.intOrNull ?: 0,
            o["category"]?.jsonPrimitive?.contentOrNull ?: "",
        ) to false
        "file" -> (nodes + AttachmentNode(
            o["path"]?.jsonPrimitive?.contentOrNull ?: "",
            at = o["at"]?.jsonPrimitive?.longOrNull ?: 0L,
        )) to false
        "pr" -> (nodes + prNodeFrom(o)) to false
        "other" -> {
            val ended = o["raw"]?.jsonObject?.get("engineExit") != null
            nodes to ended
        }
        else -> nodes to false
    }
}

/** Fold a live WS event frame string. Parses the JSON then delegates to [applyEvent].
 *  Also extracts the `busy` turn-state signal (true = generating, false = done, null = N/A). */
fun applyEvent(nodes: List<Node>, frame: String): Triple<List<Node>, Boolean, Boolean?> {
    val o = try { J.parseToJsonElement(frame).jsonObject } catch (e: Exception) { return Triple(nodes, false, null) }
    val kind = o["kind"]?.jsonPrimitive?.contentOrNull
    // Same mapping the standalone frameBusy(frame) used — kept here so its second JSON parse can go.
    val busy: Boolean? = when (kind) {
        "result" -> false
        "prompt", "text", "thinking", "tool", "skill", "agent", "workflow", "ask", "perm", "plan", "retry" -> true
        else -> null
    }
    val (next, ended) = applyEvent(nodes, o)
    return Triple(next, ended, busy)
}

/** Busy signal from a live frame: true = a turn is generating, false = turn finished,
 *  null = not a turn-state signal. Production now reads this off [applyEvent]'s already-parsed frame
 *  (the third Triple element) so the stream path parses each frame once; retained for direct unit
 *  tests of the busy mapping. */
fun frameBusy(frame: String): Boolean? = try {
    when (J.parseToJsonElement(frame).jsonObject["kind"]?.jsonPrimitive?.contentOrNull) {
        "result" -> false
        "prompt", "text", "thinking", "tool", "skill", "agent", "workflow", "ask", "perm", "plan", "retry" -> true
        else -> null
    }
} catch (e: Exception) { null }

/** Short one-line label for a permission card's tool call (reuses the tool summary the chips use). */
fun permSummary(tool: String, input: JsonObject?): String {
    val s = toolSummary(tool, input)
    return tool.replaceFirstChar { it.uppercase() } + if (s.isNotBlank()) " · $s" else ""
}
