package dev.agentic.domain

import org.junit.Assert.*
import org.junit.Test

class TranscriptGroupingTest {
    private fun reads(k: Int): List<Node> = (1..k).map { ToolNode("Read", "f$it", "") as Node }

    @Test fun consecutive_same_name_tools_group_into_one() {
        val out = groupTools(reads(3))
        assertEquals("a run collapses to a single node", 1, out.size)
        val g = out.single() as ToolGroupNode
        assertEquals("Read", g.name)
        assertEquals(3, g.items.size)
    }

    @Test fun lone_tool_stays_a_plain_tool() {
        assertTrue(groupTools(reads(1)).single() is ToolNode)
    }

    @Test fun different_names_do_not_group() {
        val nodes = listOf(ToolNode("Read", "a", "") as Node, ToolNode("Bash", "b", ""))
        val out = groupTools(nodes)
        assertEquals(2, out.size)
        assertTrue(out.none { it is ToolGroupNode })
    }

    @Test fun a_non_tool_node_breaks_the_run() {
        val nodes = reads(2) + AnswerNode("x") + reads(2)
        val out = groupTools(nodes)
        assertEquals(3, out.size)
        assertTrue(out[0] is ToolGroupNode)
        assertTrue(out[1] is AnswerNode)
        assertTrue(out[2] is ToolGroupNode)
    }

    @Test fun interleave_appends_when_no_turn_times() {
        val d = listOf(AnswerNode("a") as Node)
        val f = listOf(AttachmentNode("x.png", at = 1))
        assertEquals(2, interleaveShared(d, f).size)
    }

    @Test fun outbox_file_anchors_after_the_message_that_delivered_it() {
        // The agent writes the file then names it ("delivered: outbox/<name>"). The card must sit right
        // after THAT message — its actual delivery position — not after the user's prompt and not
        // floating to the streaming end below later content.
        val display = listOf<Node>(
            PromptNode("build me an apk", at = 100),
            TextNode("building..."),
            TextNode("delivered: outbox/app-2233.apk"),  // the delivery announcement
            TextNode("let me know if it works"),         // streamed AFTER delivery
        )
        val file = AttachmentNode("outbox/app-2233.apk", at = 250)
        val out = interleaveShared(display, listOf(file))
        val fileIdx = out.indexOfFirst { it is AttachmentNode }
        val deliverIdx = out.indexOfFirst { it is TextNode && (it as TextNode).text.contains("delivered") }
        assertEquals("card sits right after the delivering message", deliverIdx + 1, fileIdx)
        assertTrue("must not float to the end", fileIdx < out.size - 1)
    }

    @Test fun outbox_file_with_no_naming_message_falls_back_to_its_turn() {
        // No message names the file → anchor after its delivering turn's prompt (a fixed slot), not the
        // streaming end.
        val display = listOf<Node>(
            PromptNode("req1", at = 100),
            TextNode("done"),
        )
        val file = AttachmentNode("outbox/silent.bin", at = 150)
        val out = interleaveShared(display, listOf(file))
        val fileIdx = out.indexOfFirst { it is AttachmentNode }
        assertEquals("anchored right after the turn's prompt", 1, fileIdx)
    }

    @Test fun truncated_window_with_no_prompts_pins_old_file_to_top_not_bottom() {
        // Windowed transcript (server caps /events at 100): a giant single turn fills the whole window,
        // so it holds NO PromptNode and the inline agentic_file event fell outside it. The poll copy of
        // an OLD file must pin to the TOP of the window (its history lives above), NOT glue to the
        // streaming bottom below everything the agent emits afterwards.
        val display = listOf<Node>(
            TextNode("Task 5 complete."),
            ToolNode("Bash", "run tests", ""),
            TextNode("On to Task 7."),
        )
        val file = AttachmentNode("outbox/plan.md", at = 1_000)
        val out = interleaveShared(display, listOf(file), truncatedStart = true)
        assertEquals("old file pins to the top of a truncated window", 0, out.indexOfFirst { it is AttachmentNode })
    }

    @Test fun untruncated_window_with_no_prompts_still_appends() {
        // Full transcript from the very start (no older history) with no timestamped turns — the legacy
        // append fallback must be unchanged.
        val display = listOf<Node>(TextNode("hi") as Node)
        val file = AttachmentNode("outbox/plan.md", at = 1_000)
        val out = interleaveShared(display, listOf(file), truncatedStart = false)
        assertEquals("legacy append preserved", 1, out.indexOfFirst { it is AttachmentNode })
    }

    @Test fun truncated_window_where_file_predates_all_prompts_pins_to_top() {
        // Window holds one recent prompt, but the file is OLDER than it (its true position is above the
        // window) — anchor at top, never after the newer prompt or at the bottom. Pins the PRE-EXISTING
        // k=-1 fallback (top regardless of truncatedStart) as a regression guard for the windowed world.
        val display = listOf<Node>(
            PromptNode("继续", at = 2_000),
            TextNode("continuing..."),
        )
        val file = AttachmentNode("outbox/plan.md", at = 1_000)
        val out = interleaveShared(display, listOf(file), truncatedStart = true)
        assertEquals("file older than every visible turn pins to top", 0, out.indexOfFirst { it is AttachmentNode })
    }

    @Test fun inline_agentic_file_dedups_the_poll_copy() {
        // A file already present inline (from an agentic_file log event) must NOT be added a second time
        // by the poll-derived shared set (same path) — no duplicate card.
        val display = listOf<Node>(
            PromptNode("req", at = 100),
            TextNode("delivered: outbox/app.apk"),
            AttachmentNode("outbox/app.apk", fromUser = false, at = 250),  // inline, from the log event
        )
        val pollCopy = listOf(AttachmentNode("outbox/app.apk", fromUser = false, at = 250))  // same path, from /outbox
        val out = interleaveShared(display, pollCopy)
        assertEquals("inline file is not duplicated", 1, out.count { it is AttachmentNode })
        assertEquals("display unchanged", display.size, out.size)
    }

    // ── markAnsweredAsks: pair prompts to asks one-to-one (no greedy over-marking) ──

    @Test fun two_pending_asks_one_answer_marks_only_the_first() {
        val a1 = AskNode(listOf(AskQuestion("Q1")))
        val a2 = AskNode(listOf(AskQuestion("Q2")))
        val asks = markAnsweredAsks(listOf(a1, a2, PromptNode("ans1"))).filterIsInstance<AskNode>()
        assertTrue("first ask answered", asks[0].answered)
        assertEquals("ans1", asks[0].answer)
        assertFalse("second ask must stay unanswered", asks[1].answered)
    }

    @Test fun a_prompt_after_an_ask_marks_it_answered() {
        val out = markAnsweredAsks(listOf(AskNode(listOf(AskQuestion("Q"))), PromptNode("yes")))
        assertTrue((out.single { it is AskNode } as AskNode).answered)
    }

    @Test fun two_asks_two_prompts_pair_in_order() {
        val a1 = AskNode(listOf(AskQuestion("Q1")))
        val a2 = AskNode(listOf(AskQuestion("Q2")))
        val asks = markAnsweredAsks(listOf(a1, PromptNode("p1"), a2, PromptNode("p2"))).filterIsInstance<AskNode>()
        assertEquals("p1", asks[0].answer)
        assertEquals("p2", asks[1].answer)
    }
}
