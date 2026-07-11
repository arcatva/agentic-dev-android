package dev.agentic.domain

// A transcript is a flat, ordered list of nodes. Text accumulates into the trailing TextNode;
// skill / spawn / workflow markers interrupt it as inline chips.
sealed interface Node
data class PromptNode(val text: String, val at: Long = 0) : Node
/** Streamed assistant prose (`text` wire kind) — NOT extended thinking (that's [ThinkingNode]). */
data class TextNode(val text: String) : Node
data class AnswerNode(val text: String) : Node
data class ThinkingNode(val text: String) : Node
data class ToolNode(val name: String, val summary: String, val detail: String) : Node
data class ToolGroupNode(val name: String, val items: List<ToolNode>) : Node  // a run of consecutive same-name tool calls
data class SkillNode(val name: String) : Node
/** Spawned subagent (Task/Agent tool_use). [id] routes the `agentResult`/[children] back under this card; [children] = events whose `parentToolUseId == id` (nested under the agent, not main). */
data class SpawnNode(
    val type: String,
    val desc: String,
    val id: String = "",
    val result: String? = null,
    val children: List<Node> = emptyList(),
) : Node
/** Inline workflow card. [delegate] true = `delegate` fan-out; [name] = FALLBACK to correlate; [runId] = exact run (filled by `workflowRun` link keyed by [id], avoids guessing from a duplicated name). */
data class WorkflowNode(val name: String, val id: String = "", val delegate: Boolean = false, val runId: String? = null) : Node
/** Transient API-retry notice (`retry` frame). Consecutive retries collapse to one (latest attempt). */
data class RetryNode(val attempt: Int, val maxRetries: Int, val category: String) : Node
data class AttachmentNode(val path: String, val fromUser: Boolean = false, val at: Long = 0) : Node
/** AskUserQuestion event from the server. One node per call (not per question) so a single [answer] can't be misattributed. */
data class AskNode(val questions: List<AskQuestion>, val answered: Boolean = false, val answer: String = "") : Node
/** Tool awaiting allow/deny. [id] correlates with `agentic_perm_resolved` so a reseed shows it decided. */
data class PermNode(val id: String, val tool: String, val summary: String = "", val decided: Boolean = false, val decision: String = "") : Node
/** Plan (ExitPlanMode in plan mode) awaiting Approve / Keep-planning. */
data class PlanNode(val id: String, val plan: String, val decided: Boolean = false, val decision: String = "") : Node
/** PR created by the session (backend emits from `gh pr create`, enriched via `gh pr view`). One node per PR; [repo] = "owner/repo". */
data class PrNode(
    val url: String,
    val number: Int,
    val repo: String,
    val title: String,
    val body: String,
    val state: String = "OPEN",
) : Node
