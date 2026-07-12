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

/** Extract an ultracode workflow's real name from its inline script's `export const meta = { name: '…' }` so a reseed matches the live `kind:workflow` chip. */
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
    // Single list allocation (copy + replace tail) instead of `dropLast(1) + plus` (two lists per token). Text concat remains a String (changing TextNode.text to StringBuilder would alter the node model).
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

/** Materialise terminal `result` as [AnswerNode], dropping the trailing top-level TextNode that duplicates it (SDK repeats final assistant message in `result`; streamed prose + [text] are the same bytes — without this, the answer renders twice). Keep [AnswerNode] (also carries error text which has no matching TextNode); only drop the duplicate streamed copy after the last [PromptNode]. Trailing text ≠ [text] (genuinely different prose) is left intact. */
fun appendAnswer(nodes: List<Node>, text: String): List<Node> {
    val lastPrompt = nodes.indexOfLast { it is PromptNode }
    val idx = nodes.indexOfLast { it is TextNode }
    val base = if (idx > lastPrompt && (nodes[idx] as TextNode).text.trim() == text.trim())
        nodes.toMutableList().also { it.removeAt(idx) }
    else nodes
    return base + AnswerNode(text)
}

/** Append retry notice; consecutive retries collapse to latest attempt (one chip, no stale "retrying" stack). */
fun appendRetry(nodes: List<Node>, attempt: Int, maxRetries: Int, category: String): List<Node> {
    val node = RetryNode(attempt, maxRetries, category)
    return if (nodes.lastOrNull() is RetryNode) nodes.dropLast(1) + node else nodes + node
}

/** Attach subagent result to the [SpawnNode] whose tool_use id matches [toolUseId]; fallback to a standalone SpawnNode (never silently drop). Live-only — `agentResult` absent from backfilled log, reseed flattens it back out. */
fun attachAgentResult(nodes: List<Node>, toolUseId: String, text: String): List<Node> {
    val idx = if (toolUseId.isEmpty()) -1
        else nodes.indexOfLast { it is SpawnNode && it.id == toolUseId }
    return if (idx >= 0) {
        nodes.toMutableList().also { it[idx] = (it[idx] as SpawnNode).copy(result = text) }
    } else {
        nodes + SpawnNode("agent", "", toolUseId, text)
    }
}

/** Mark the most recent Perm/Plan node with [id] as decided (permResolved event / log marker); no-op if none matches (line may be filtered out of a partial log). */
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

/** Fill [WorkflowNode.runId] of the card whose tool_use [id] matches (`workflowRun` link event/marker) so a click opens the exact run. Updates top-level, else one level of spawn children; idempotent; no-op when blank/missing. */
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

/** Route a child produced by [op] under the [SpawnNode] whose tool_use id == [parentId] (the agent that emitted this event, via `parentToolUseId`). Null/blank/no-match → run on top-level (never drop). [op] receives the appropriate list (top or children) so coalesce helpers merge in the right scope. One level only: nested spawn's grandchildren fall back. */
private fun route(nodes: List<Node>, parentId: String?, op: (List<Node>) -> List<Node>): List<Node> {
    if (parentId.isNullOrBlank()) return op(nodes)
    val idx = nodes.indexOfLast { it is SpawnNode && it.id == parentId }
    if (idx < 0) return op(nodes)
    val spawn = nodes[idx] as SpawnNode
    return nodes.toMutableList().also { it[idx] = spawn.copy(children = op(spawn.children)) }
}

private fun String?.orBlank() = this ?: ""

/** Build transcript nodes from the persisted log (raw stream-json lines) for a terminal session. */
fun buildFromLog(log: List<String>): List<Node> {
    var nodes = emptyList<Node>()
    // Streaming dedup (partials ON): a turn's text/thinking arrives as `stream_event` token deltas AND restated in complete `assistant`. Once any delta produced this turn, the sealed block is redundant — skip. A no-delta turn (old logs / parked before streaming) renders the block normally. Reset per user turn.
    var streamedThisTurn = false
    for (line in log) {
        val o = try { J.parseToJsonElement(line).jsonObject } catch (e: Exception) { continue }
        // Subagent lines carry parent_tool_use_id (the spawning Agent tool_use); route under that SpawnNode. null = main agent.
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
            // Delivered outbox file (backend marks at delivery moment) — lands INLINE at real position (no client-side mtime/content guessing); interleaveShared dedups the poll-derived copy.
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
                        // Bridge runs includePartialMessages=OFF: text/thinking arrive ONLY in this complete assistant block (no stream_event deltas in log). Without rendering, a turn parked mid-stream on an AskUserQuestion/perm/plan (no `result` line) loses preceding prose on every reseed (background return/reconnect). Completed turn's final prose duplicates `result`; appendAnswer drops that single trailing TextNode. Skip when this turn already streamed deltas (sealed block is redundant).
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
                                // `delegate` fan-out is surfaced live as a workflow card by backend; on RELOAD parse raw tool_use directly — mirror that (title = run name) rather than falling through to raw tool chip.
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
            // Engine-synthesized marker for a spawned subagent's returned text (raw `user` tool_result it derives from isn't persisted in the rendered log). Re-attach so body survives reopen/reconnect — mirrors live `kind:agentResult`.
            "agent_result" -> nodes = attachAgentResult(
                nodes,
                o["toolUseId"]?.jsonPrimitive?.contentOrNull ?: "",
                o["text"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            "result" -> (o["result"]?.jsonPrimitive?.contentOrNull ?: o["error"]?.jsonPrimitive?.contentOrNull)
                ?.takeIf { it.isNotBlank() }?.let { nodes = appendAnswer(nodes, it) }
            // Backend PR-card marker; same shape as live `kind:pr` frame.
            "pr" -> nodes = nodes + prNodeFrom(o)
            // Backend link marker: fill matching workflow card's runId (mirrors live `kind:workflowRun`).
            "workflowRun" -> nodes = setWorkflowRunId(
                nodes,
                o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                o["runId"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }
    return markAnsweredAsks(nodes)
}

/** Build a [PrNode] from a parsed `pr` frame/marker — identical field names on live wire and persisted log, so [applyEvent]/[buildFromLog] decode a PR the same way. */
private fun prNodeFrom(o: JsonObject): PrNode = PrNode(
    url = o["url"]?.jsonPrimitive?.contentOrNull ?: "",
    number = o["number"]?.jsonPrimitive?.intOrNull ?: 0,
    repo = o["repo"]?.jsonPrimitive?.contentOrNull ?: "",
    title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
    body = o["body"]?.jsonPrimitive?.contentOrNull ?: "",
    state = o["state"]?.jsonPrimitive?.contentOrNull ?: "OPEN",
)

/** Fold a live WS event frame (parsed ClaudeEvent). `busy` = turn-state signal (true generating/false done/null N/A) derived from the JsonObject parsed HERE so the caller never re-parses to read top-level kind. */
/** Dispatch a structured ClaudeEvent (kind-tagged JSON, from [ClaudeEvent.to_wire]) onto a node list. Used by both the REST events endpoint and the live WebSocket stream. Returns (nextNodes, ended). */
fun applyEvent(nodes: List<Node>, o: JsonObject): Pair<List<Node>, Boolean> {
    // null/absent = main agent; else the id of the Agent tool_use that spawned this subagent — nest under that SpawnNode (see [route]).
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

/** Fold a live WS event frame string (parses JSON then delegates to [applyEvent]); also extracts the `busy` turn-state signal. */
fun applyEvent(nodes: List<Node>, frame: String): Triple<List<Node>, Boolean, Boolean?> {
    val o = try { J.parseToJsonElement(frame).jsonObject } catch (e: Exception) { return Triple(nodes, false, null) }
    val kind = o["kind"]?.jsonPrimitive?.contentOrNull
    // Same mapping as standalone frameBusy(frame) — kept here so its second JSON parse can go.
    val busy: Boolean? = when (kind) {
        "result" -> false
        "prompt", "text", "thinking", "tool", "skill", "agent", "workflow", "ask", "perm", "plan", "retry" -> true
        else -> null
    }
    val (next, ended) = applyEvent(nodes, o)
    return Triple(next, ended, busy)
}

/** Busy signal from a live frame (true generating/false done/null N/A). Production reads this off [applyEvent]'s parsed frame (the third Triple element) so the stream parses once; retained for direct unit tests. */
fun frameBusy(frame: String): Boolean? = try {
    when (J.parseToJsonElement(frame).jsonObject["kind"]?.jsonPrimitive?.contentOrNull) {
        "result" -> false
        "prompt", "text", "thinking", "tool", "skill", "agent", "workflow", "ask", "perm", "plan", "retry" -> true
        else -> null
    }
} catch (e: Exception) { null }

/** One-line permission card label (reuses the tool summary the chips use). */
fun permSummary(tool: String, input: JsonObject?): String {
    val s = toolSummary(tool, input)
    return tool.replaceFirstChar { it.uppercase() } + if (s.isNotBlank()) " · $s" else ""
}
