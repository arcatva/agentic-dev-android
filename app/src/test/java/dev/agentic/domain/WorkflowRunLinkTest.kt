package dev.agentic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the workflow-card → run link: a backend `kind:workflowRun` WS frame (live) and the persisted
 * `{"type":"workflowRun",…}` log marker (reseed) both fill the matching [WorkflowNode.runId] (keyed by
 * the card's tool_use id), so a click opens the EXACT run live and after a reconnect. Without this the
 * client falls back to name matching, which is ambiguous when runs share a name.
 */
class WorkflowRunLinkTest {

    @Test
    fun liveFrameFillsRunIdOnMatchingCard() {
        // A workflow card exists (from a `workflow` frame), runId not yet known.
        val (afterCard, _, _) = applyEvent(
            emptyList(),
            """{"kind":"workflow","id":"toolu_W","name":"review","delegate":false}""",
        )
        assertNull((afterCard.single() as WorkflowNode).runId)

        // The link frame fills its runId by tool_use id.
        val (linked, ended, _) = applyEvent(
            afterCard,
            """{"kind":"workflowRun","id":"toolu_W","runId":"wf_abc123"}""",
        )
        val wf = linked.single() as WorkflowNode
        assertEquals("wf_abc123", wf.runId)
        assertEquals("review", wf.name) // name preserved
        assertFalse("a workflowRun frame does not end the turn", ended)
    }

    @Test
    fun logMarkerRebuildsRunIdOnReload() {
        val card = """{"type":"assistant","message":{"content":[""" +
            """{"type":"tool_use","id":"toolu_W","name":"Workflow","input":{"name":"review"}}]}}"""
        val marker = """{"type":"workflowRun","id":"toolu_W","runId":"wf_abc123"}"""
        val wf = buildFromLog(listOf(card, marker)).single() as WorkflowNode
        assertEquals("toolu_W", wf.id)
        assertEquals("wf_abc123", wf.runId)
    }

    @Test
    fun delegateCardIsLinkedToo() {
        val card = """{"type":"assistant","message":{"content":[""" +
            """{"type":"tool_use","id":"toolu_D","name":"mcp__agentic__delegate","input":{"title":"audit","tasks":[{"prompt":"x"}]}}]}}"""
        val marker = """{"type":"workflowRun","id":"toolu_D","runId":"wfdeleg-99-1"}"""
        val wf = buildFromLog(listOf(card, marker)).single() as WorkflowNode
        assertEquals(true, wf.delegate)
        assertEquals("wfdeleg-99-1", wf.runId)
    }

    @Test
    fun noMatchingCardIsANoOp() {
        // Marker for an unknown tool_use id, and blank ids, leave nodes untouched.
        assertEquals(emptyList<Node>(), setWorkflowRunId(emptyList(), "toolu_X", "wf_1"))
        val nodes = listOf<Node>(WorkflowNode("review", "toolu_W"))
        assertEquals(nodes, setWorkflowRunId(nodes, "toolu_OTHER", "wf_1"))
        assertEquals(nodes, setWorkflowRunId(nodes, "", "wf_1"))
        assertEquals(nodes, setWorkflowRunId(nodes, "toolu_W", ""))
    }
}
