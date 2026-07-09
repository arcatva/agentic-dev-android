package dev.agentic.domain

// A transcript is a flat, ordered list of nodes. Text accumulates into the trailing TextNode;
// skill / spawn / workflow markers interrupt it as inline chips.
sealed interface Node
data class PromptNode(val text: String, val at: Long = 0) : Node
/** Streamed assistant prose (the `text` wire kind). NB: this is NOT extended thinking — that is the
 *  separate [ThinkingNode] (`thinking` wire kind). */
data class TextNode(val text: String) : Node
data class AnswerNode(val text: String) : Node
data class ThinkingNode(val text: String) : Node
data class ToolNode(val name: String, val summary: String, val detail: String) : Node
data class ToolGroupNode(val name: String, val items: List<ToolNode>) : Node  // a run of consecutive same-name tool calls
data class SkillNode(val name: String) : Node
/** A spawned subagent (Task/Agent tool_use). [id] is the tool_use id used to route the subagent's
 *  returned [result] (the `agentResult` frame) and its internal steps ([children]) back under this
 *  card. [children] holds the subagent's own events (its tool calls, text, thinking) — i.e. every
 *  transcript event whose `parentToolUseId == id` — so they render nested under the agent instead of
 *  leaking into the main conversation. */
data class SpawnNode(
    val type: String,
    val desc: String,
    val id: String = "",
    val result: String? = null,
    val children: List<Node> = emptyList(),
) : Node
/** An inline workflow card. [delegate] true = a `delegate` fan-out (labelled "Workflow delegate");
 *  false = a native `Workflow` tool. [id] is the card's tool_use id. [name] is the run title, used only
 *  as a FALLBACK to correlate to a run. [runId] is the exact run this card maps to: it starts null and
 *  is filled when the backend's `workflowRun` link (keyed by [id]) arrives, so a click opens that
 *  precise run rather than guessing by the (often-duplicated) name. */
data class WorkflowNode(val name: String, val id: String = "", val delegate: Boolean = false, val runId: String? = null) : Node
/** A transient API-retry notice (from the server's `retry` frame, raw type system/api_retry). Shown
 *  as a status chip; consecutive retries collapse into one (the latest attempt). */
data class RetryNode(val attempt: Int, val maxRetries: Int, val category: String) : Node
data class AttachmentNode(val path: String, val fromUser: Boolean = false, val at: Long = 0) : Node
/** An AskUserQuestion event from the server — rendered as a card with option chips. */
/** One AskUserQuestion tool call, holding ALL its [questions] (answered together). [answer] is the
 *  combined submitted text shown once [answered]. One node per call (not one per question) so a
 *  single answer can't be misattributed across questions. */
data class AskNode(val questions: List<AskQuestion>, val answered: Boolean = false, val answer: String = "") : Node
/** A tool awaiting the user's allow/deny (permission mode = default/acceptEdits). [id] correlates the
 *  card with its backend agentic_perm_resolved marker so a reseed shows it decided. [summary] is a
 *  short human label for the tool call (e.g. "Bash · rm -rf x"). */
data class PermNode(val id: String, val tool: String, val summary: String = "", val decided: Boolean = false, val decision: String = "") : Node
/** A plan (ExitPlanMode in plan mode) awaiting Approve / Keep-planning. */
data class PlanNode(val id: String, val plan: String, val decided: Boolean = false, val decision: String = "") : Node
/** A pull request the session created — emitted by the backend (detected from a `gh pr create`, then
 *  enriched via `gh pr view`). Rendered as its own card; one node per PR. [repo] is "owner/repo". */
data class PrNode(
    val url: String,
    val number: Int,
    val repo: String,
    val title: String,
    val body: String,
    val state: String = "OPEN",
) : Node
