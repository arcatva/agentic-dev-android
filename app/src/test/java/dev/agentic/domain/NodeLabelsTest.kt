package dev.agentic.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Inline transcript chips share one "{Kind} · {name}" shape (the same " · " separator the tool-group
 * / notes / retry chips already use). The agent chip keeps its friendly "#N" ordinal and "Subagent"
 * fallback; the opaque tool_use id is never shown.
 */
class NodeLabelsTest {

    // ── skillChipLabel (inline SkillNode card) ───────────────────────────────────────

    @Test fun skill_name() =
        assertEquals("Skill · deep-research", skillChipLabel(SkillNode("deep-research")))

    @Test fun skill_blank_name_falls_back() =
        assertEquals("Skill · skill", skillChipLabel(SkillNode("")))

    // ── agentChipLabel (inline SpawnNode card) ───────────────────────────────────────

    @Test fun agent_type_only() =
        assertEquals("Agent · Explore", agentChipLabel(SpawnNode("Explore", "")))

    @Test fun agent_type_and_desc() =
        assertEquals(
            "Agent · Explore — map the rail",
            agentChipLabel(SpawnNode("Explore", "map the rail")),
        )

    @Test fun agent_type_and_ordinal() =
        assertEquals("Agent · Explore #2", agentChipLabel(SpawnNode("Explore", ""), ordinal = 2))

    @Test fun agent_type_ordinal_and_desc() =
        assertEquals(
            "Agent · Explore #2 — map the rail",
            agentChipLabel(SpawnNode("Explore", "map the rail"), ordinal = 2),
        )

    // A real type name like "claude" passes through unchanged — only the literal "agent" is generic.
    @Test fun agent_named_type_passes_through() =
        assertEquals("Agent · claude", agentChipLabel(SpawnNode("claude", "")))

    // The old "agent: agent" eyesore: a blank or literal-"agent" type now reads "Subagent".
    @Test fun agent_blank_type_falls_back() =
        assertEquals("Agent · Subagent", agentChipLabel(SpawnNode("", "")))

    @Test fun agent_generic_type_falls_back() =
        assertEquals("Agent · Subagent", agentChipLabel(SpawnNode("agent", "")))

    @Test fun agent_generic_type_with_ordinal() =
        assertEquals("Agent · Subagent #3", agentChipLabel(SpawnNode("agent", ""), ordinal = 3))

    @Test fun agent_ignores_the_tool_use_id() =
        assertEquals(
            "Agent · Explore",
            agentChipLabel(SpawnNode("Explore", "", id = "toolu_ABCDEF123456")),
        )

    // ── workflowChipLabel (inline WorkflowNode card) ─────────────────────────────────

    @Test fun workflow_name_only() =
        assertEquals("Workflow · review", workflowChipLabel(WorkflowNode("review")))

    @Test fun workflow_blank_name_falls_back() =
        assertEquals("Workflow · workflow", workflowChipLabel(WorkflowNode("")))

    @Test fun workflow_ignores_the_tool_use_id() =
        assertEquals(
            "Workflow · review",
            workflowChipLabel(WorkflowNode("review", id = "wf_ABCDEF123456")),
        )
}
