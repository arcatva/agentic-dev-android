package dev.agentic.domain
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class TranscriptPermTest {
    @Test fun live_perm_then_resolved_marks_decided() {
        var nodes = emptyList<Node>()
        nodes = applyEvent(nodes, """{"kind":"perm","id":"perm-1","tool":"Bash","input":{"command":"ls"}}""").first
        assertTrue(nodes.last() is PermNode)
        assertEquals(false, (nodes.last() as PermNode).decided)
        nodes = applyEvent(nodes, """{"kind":"permResolved","id":"perm-1","decision":"allow"}""").first
        assertEquals("allow", (nodes.single { it is PermNode } as PermNode).decision)
        assertTrue((nodes.single { it is PermNode } as PermNode).decided)
    }
    @Test fun log_plan_renders_and_resolves() {
        val log = listOf(
            """{"type":"agentic_perm","permKind":"plan","id":"p2","plan":"# Plan"}""",
            """{"type":"agentic_perm_resolved","id":"p2","decision":"deny"}""",
        )
        val nodes = buildFromLog(log)
        val plan = nodes.single { it is PlanNode } as PlanNode
        assertEquals("# Plan", plan.plan)
        assertTrue(plan.decided)
        assertEquals("deny", plan.decision)
    }
    @Test fun frameBusy_true_for_perm_and_plan() {
        assertEquals(true, frameBusy("""{"kind":"perm","id":"x"}"""))
        assertEquals(true, frameBusy("""{"kind":"plan","id":"x"}"""))
    }
}
