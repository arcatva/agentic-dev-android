package dev.agentic.domain

// Inline-chip label builders (pure/unit-testable) for SpawnNode/WorkflowNode/SkillNode — "{kind} · {name}" with U+00B7 separator; opaque tool_use id deliberately omitted.

/** "Skill · {name}" for a [SkillNode] inline card. */
fun skillChipLabel(node: SkillNode): String =
    "Skill · ${node.name.ifBlank { "skill" }}"

/** "Agent · {Name}[ #N][ — desc]": untyped/orphan subagents → "Subagent"; [ordinal] = 1-based "#N" (friendlier than tool_use id); desc follows em dash. */
fun agentChipLabel(node: SpawnNode, ordinal: Int? = null): String {
    val name = node.type.takeIf { it.isNotBlank() && !it.equals("agent", ignoreCase = true) } ?: "Subagent"
    val num = ordinal?.let { " #$it" } ?: ""
    val desc = if (node.desc.isNotBlank()) " — ${node.desc}" else ""
    return "Agent · $name$num$desc"
}

/** "Workflow delegate · {title}" for delegate fan-out, else "Workflow · {name}". */
fun workflowChipLabel(node: WorkflowNode): String =
    if (node.delegate) "Workflow delegate · ${node.name.ifBlank { "delegate" }}"
    else "Workflow · ${node.name.ifBlank { "workflow" }}"
