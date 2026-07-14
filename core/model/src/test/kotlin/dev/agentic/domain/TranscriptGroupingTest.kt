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

    @Test fun truncated_window_with_no_prompts_hides_the_out_of_window_file() {
        // Windowed transcript (server caps /events at 100): a giant single turn fills the whole window,
        // so it holds NO PromptNode and the inline agentic_file event fell outside it. Like every other
        // card whose position is above the window, the file is NOT rendered — it appears when the user
        // pages back to its region (inline event for new logs, turn anchor for old ones).
        val display = listOf<Node>(
            TextNode("Task 5 complete."),
            ToolNode("Bash", "run tests", ""),
            TextNode("On to Task 7."),
        )
        val file = AttachmentNode("outbox/plan.md", at = 1_000)
        val out = interleaveShared(display, listOf(file), truncatedStart = true)
        assertEquals("out-of-window file is hidden, not pinned or appended", 0, out.count { it is AttachmentNode })
        assertEquals("display otherwise untouched", display, out)
    }

    @Test fun untruncated_window_with_no_prompts_still_appends() {
        // Full transcript from the very start (no older history) with no timestamped turns — the legacy
        // append fallback must be unchanged.
        val display = listOf<Node>(TextNode("hi") as Node)
        val file = AttachmentNode("outbox/plan.md", at = 1_000)
        val out = interleaveShared(display, listOf(file), truncatedStart = false)
        assertEquals("legacy append preserved", 1, out.indexOfFirst { it is AttachmentNode })
    }

    @Test fun truncated_window_where_file_predates_all_prompts_hides_it() {
        // Window holds one recent prompt, but the file is OLDER than it — its true position is above the
        // window, so it is hidden (never after the newer prompt, never at the bottom); paging back to
        // its delivering turn reveals it.
        val display = listOf<Node>(
            PromptNode("继续", at = 2_000),
            TextNode("continuing..."),
        )
        val file = AttachmentNode("outbox/plan.md", at = 1_000)
        val out = interleaveShared(display, listOf(file), truncatedStart = true)
        assertEquals("file older than every visible turn is hidden", 0, out.count { it is AttachmentNode })
    }

    @Test fun truncated_window_ignores_a_coincidental_later_mention_of_an_old_file() {
        // Real-world "sinks to the bottom": the tail window of a very long session. An outbox file was
        // delivered long ago (older than every visible turn) so its inline agentic_file marker is ABOVE
        // the window. The resident wrap-up report happens to CITE that file's name (e.g. it reports on the
        // spec it implemented). The poll copy must NOT latch onto that coincidental mention and sink to
        // the bottom — the file belongs to unloaded history above and is hidden (paging back reveals the
        // inline card at its true spot), exactly like a file that no visible message names at all.
        val display = listOf<Node>(
            PromptNode("继续下一批", at = 3_000),
            TextNode("最终报告：已实现 specs/2026-07-11-enterprise-refactor-design.md 的全部条目"),
        )
        val file = AttachmentNode("outbox/2026-07-11-enterprise-refactor-design.md", at = 1_000)
        val out = interleaveShared(display, listOf(file), truncatedStart = true, markerBacked = true)
        assertEquals("a coincidental late mention must not resurrect an old off-window card", 0, out.count { it is AttachmentNode })
        assertEquals("display otherwise untouched", display, out)
    }

    @Test fun legacy_session_without_markers_keeps_a_resident_delivery_with_backdated_mtime() {
        // Codex edge: a marker-LESS (legacy) backend where the file's genuine delivery message IS resident,
        // but its poll `at` (filesystem mtime) is backdated below the window start (e.g. copied with `cp -p`,
        // or `touch`-ed). Without markerBacked, no resident marker could have deduped a real delivery, so a
        // visible naming message must be trusted — the card anchors after it and is NOT hidden by the mtime
        // gate. (On a marker-backed backend this can't arise: the delivery's marker would be resident and
        // would have deduped the poll copy before the gate ran.)
        val display = listOf<Node>(
            PromptNode("build me the report", at = 5_000),
            TextNode("delivered: outbox/report.md"),
        )
        val file = AttachmentNode("outbox/report.md", at = 1_000)   // backdated mtime, older than the window
        val out = interleaveShared(display, listOf(file), truncatedStart = true, markerBacked = false)
        assertEquals("legacy resident delivery is not hidden by a backdated mtime", 1, out.count { it is AttachmentNode })
        assertEquals("anchored after its (resident) delivery message", 2, out.indexOfFirst { it is AttachmentNode })
    }

    @Test fun truncated_window_hides_only_the_old_file_when_pending_mixes_ages() {
        // The per-file gate is independent: in one poll batch an OLD off-window file (cited later by a
        // wrap-up) is hidden while a genuinely in-window NEW file (named by its delivery message) anchors.
        val display = listOf<Node>(
            PromptNode("继续下一批", at = 3_000),
            TextNode("delivered: outbox/new.md"),
            TextNode("收尾：见 outbox/old.md 的结论"),   // coincidental cite of the OLD file
        )
        val shared = listOf(
            AttachmentNode("outbox/old.md", at = 1_000),   // older than the window → hidden
            AttachmentNode("outbox/new.md", at = 3_500),   // in-window + named by its delivery line → anchored
        )
        val out = interleaveShared(display, shared, truncatedStart = true, markerBacked = true)
        assertEquals("old off-window file hidden", 0, out.count { (it as? AttachmentNode)?.path == "outbox/old.md" })
        val newIdx = out.indexOfFirst { (it as? AttachmentNode)?.path == "outbox/new.md" }
        val deliverIdx = out.indexOfFirst { it is TextNode && (it as TextNode).text.contains("delivered: outbox/new.md") }
        assertEquals("new in-window file anchored right after its delivery message", deliverIdx + 1, newIdx)
    }

    @Test fun truncated_window_keeps_a_file_delivered_exactly_at_the_window_start() {
        // Boundary: a file whose delivery time EQUALS the window's earliest timestamp sits on the window
        // edge, not above it — the gate is strict `<`, so it is kept and anchored, never hidden.
        val display = listOf<Node>(
            PromptNode("req", at = 1_000),
            TextNode("delivered: outbox/edge.md"),
        )
        val file = AttachmentNode("outbox/edge.md", at = 1_000)
        val out = interleaveShared(display, listOf(file), truncatedStart = true, markerBacked = true)
        assertEquals("file at the exact window-start is kept, not hidden", 1, out.count { it is AttachmentNode })
        assertEquals("anchored after its delivery message", 2, out.indexOfFirst { it is AttachmentNode })
    }

    @Test fun truncated_window_gate_uses_inline_marker_time_when_no_prompt_is_resident() {
        // windowStart comes from ANY timestamped resident node, not just prompts: here the only timestamp
        // is a resident inline agentic_file marker. An older poll file cited by a later message must still
        // be hidden — the turns-only fallback (turns.isEmpty) can't see this; the time gate can.
        val display = listOf<Node>(
            AttachmentNode("outbox/earlier.md", fromUser = false, at = 5_000),  // resident inline marker
            TextNode("回顾：见 outbox/old.md"),                                   // coincidental cite of the OLD file
        )
        val file = AttachmentNode("outbox/old.md", at = 1_000)
        val out = interleaveShared(display, listOf(file), truncatedStart = true, markerBacked = true)
        assertEquals("only the resident inline marker remains; the old cited file is hidden", 1, out.count { it is AttachmentNode })
        assertEquals("the one remaining card is the resident marker", "outbox/earlier.md", (out.first { it is AttachmentNode } as AttachmentNode).path)
    }

    @Test fun truncated_window_still_anchors_a_file_named_by_a_visible_message() {
        // truncatedStart must NOT hide a file whose anchor IS visible: a message in the window names
        // it → the card sits right after that message, exactly as in a full transcript.
        val display = listOf<Node>(
            TextNode("building..."),
            TextNode("delivered: outbox/app.apk"),
            TextNode("let me know"),
        )
        val file = AttachmentNode("outbox/app.apk", at = 1_000)
        val out = interleaveShared(display, listOf(file), truncatedStart = true)
        assertEquals("anchored after the delivering message despite truncation", 2, out.indexOfFirst { it is AttachmentNode })
    }

    @Test fun truncated_window_still_anchors_a_file_to_a_visible_older_turn() {
        // truncatedStart must NOT hide a file whose delivering turn IS resident: a prompt older than
        // the file sits in the window → anchor right after it, exactly as in a full transcript.
        val display = listOf<Node>(
            PromptNode("req", at = 500),
            TextNode("working"),
        )
        val file = AttachmentNode("outbox/silent.bin", at = 1_000)
        val out = interleaveShared(display, listOf(file), truncatedStart = true)
        assertEquals("anchored after the resident delivering turn", 1, out.indexOfFirst { it is AttachmentNode })
    }

    @Test fun untruncated_window_where_file_predates_all_prompts_keeps_legacy_top() {
        // FULL transcript (no truncation): a file older than the first prompt still pins to the top —
        // the pre-existing "older-than-first-prompt → top" behavior is unchanged.
        val display = listOf<Node>(
            PromptNode("req", at = 2_000),
            TextNode("working"),
        )
        val file = AttachmentNode("outbox/plan.md", at = 1_000)
        val out = interleaveShared(display, listOf(file), truncatedStart = false)
        assertEquals("legacy top placement preserved", 0, out.indexOfFirst { it is AttachmentNode })
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
