package dev.agentic.domain

/**
 * Inline-chip label builders for the spawned-agent / workflow / skill markers in the chat transcript.
 * Kept here (not in the Compose layer) so the formatting is pure and unit-testable, mirroring
 * [toolSummary]/[toolDetail] in ToolDisplay.kt.
 *
 * They share one shape — "{kind} · {name}" with the same " · " (U+00B7) separator the tool-group /
 * notes / retry chips already use — so every inline marker reads consistently. The opaque tool_use id
 * is deliberately not shown.
 */

/** "Skill · {name}" for a [SkillNode] inline card. */
fun skillChipLabel(node: SkillNode): String =
    "Skill · ${node.name.ifBlank { "skill" }}"

/**
 * Inline [SpawnNode] card label: "Agent · {Name}[ #N][ — desc]".
 *
 * The subagent's *type* is its name (e.g. "Explore", "Plan", "general-purpose"); the generic
 * fallback — an untyped/orphan subagent — reads "Subagent" rather than the old, repetitive
 * "agent: agent". An optional 1-based [ordinal] appends a clean "#N", a friendlier way to tell
 * concurrent agents apart than the opaque tool_use id we used to dangle here. The task description,
 * when present, follows after an em dash.
 */
fun agentChipLabel(node: SpawnNode, ordinal: Int? = null): String {
    val name = node.type.takeIf { it.isNotBlank() && !it.equals("agent", ignoreCase = true) } ?: "Subagent"
    val num = ordinal?.let { " #$it" } ?: ""
    val desc = if (node.desc.isNotBlank()) " — ${node.desc}" else ""
    return "Agent · $name$num$desc"
}

/** Inline workflow-card label: "Workflow delegate · {title}" for a delegate fan-out, else
 *  "Workflow · {name}" for a native Workflow. */
fun workflowChipLabel(node: WorkflowNode): String =
    if (node.delegate) "Workflow delegate · ${node.name.ifBlank { "delegate" }}"
    else "Workflow · ${node.name.ifBlank { "workflow" }}"
