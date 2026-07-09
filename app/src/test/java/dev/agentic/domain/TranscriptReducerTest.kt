package dev.agentic.domain

import org.junit.Assert.*
import org.junit.Test

class TranscriptReducerTest {
    @Test fun seed_from_log_display_is_non_empty() {
        val r = TranscriptReducer()
        r.seedFromLog(listOf(
            """{"type":"agentic_prompt","text":"go","at":1}""",
            """{"type":"result","result":"ok"}""",
        ))
        assertTrue(r.display().isNotEmpty())
    }

    @Test fun applyFrame_tool_appears_in_display() {
        val r = TranscriptReducer()
        r.applyFrame("""{"kind":"tool","name":"Read","input":{"file_path":"/a/B.kt"}}""")
        assertTrue(r.display().any { it is ToolNode })
    }

    @Test fun applyFrame_result_returns_false_and_answer_appears() {
        val r = TranscriptReducer()
        val ended = r.applyFrame("""{"kind":"result","text":"done"}""")
        assertFalse(ended)
        assertTrue(r.display().any { it is AnswerNode })
    }

    @Test fun applyFrame_engineExit_returns_true() {
        val r = TranscriptReducer()
        val ended = r.applyFrame("""{"kind":"other","raw":{"engineExit":true}}""")
        assertTrue(ended)
    }

    @Test fun raw_matches_nodes_before_grouping() {
        val r = TranscriptReducer()
        repeat(8) { r.applyFrame("""{"kind":"tool","name":"Read","input":{}}""") }
        r.applyFrame("""{"kind":"result","text":"done"}""")
        // raw has all 8 ToolNodes ungrouped; display collapses the same-name run into one group
        assertEquals(8, r.raw.count { it is ToolNode })
        assertTrue(r.display().any { it is ToolGroupNode })
    }

    @Test fun setShared_shows_attachment_in_display() {
        val r = TranscriptReducer()
        r.seedFromLog(listOf("""{"type":"agentic_prompt","text":"hi","at":1}"""))
        r.setShared(listOf(AttachmentNode("file.txt", at = 2)))
        assertTrue(r.display().any { it is AttachmentNode })
    }

    // An AskUserQuestion with multiple questions must be ONE AskNode holding all questions (not one
    // node per question) — so a single answer can never be misattributed across questions.
    @Test fun ask_with_multiple_questions_is_a_single_node() {
        val r = TranscriptReducer()
        r.seedFromLog(listOf(
            """{"type":"agentic_prompt","text":"go","at":1}""",
            """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"AskUserQuestion","input":{"questions":[{"question":"Q1","options":["A"]},{"question":"Q2","options":["B"]}]}}]}}""",
        ))
        val asks = r.display().filterIsInstance<AskNode>()
        assertEquals("one AskNode per AskUserQuestion call", 1, asks.size)
        assertEquals("holding both questions", 2, asks.single().questions.size)
    }

    // ── "prompt shows twice" regression: one logical turn re-materialized through BOTH ingestion
    //    channels (persisted agentic_prompt in the log + live kind:prompt frame, same server `at`)
    //    must render as ONE PromptNode. Both channels carry the same `at` (one `now` in engine.ts). ──
    @Test fun prompt_from_log_and_live_frame_same_at_collapses_to_one() {
        val r = TranscriptReducer()
        r.seedFromLog(listOf("""{"type":"agentic_prompt","text":"hello","at":100}"""))   // persisted copy
        r.applyFrame("""{"kind":"prompt","text":"hello","at":100}""")                     // live copy, SAME at
        assertEquals(1, r.display().count { it is PromptNode && it.text == "hello" })
    }

    @Test fun live_prompt_then_reseed_with_same_prompt_stays_one() {
        val r = TranscriptReducer()
        r.applyFrame("""{"kind":"prompt","text":"hello","at":100}""")                     // live first
        r.seedFromLog(listOf("""{"type":"agentic_prompt","text":"hello","at":100}"""))    // reseed
        r.applyFrame("""{"kind":"prompt","text":"hello","at":100}""")                     // re-delivered backfill
        assertEquals(1, r.display().count { it is PromptNode && it.text == "hello" })
    }

    // Must NOT over-dedup: the user legitimately sending the SAME text in two different turns (distinct
    // server `at`) keeps BOTH bubbles.
    @Test fun same_text_different_at_is_kept_as_two_turns() {
        val r = TranscriptReducer()
        r.seedFromLog(listOf(
            """{"type":"agentic_prompt","text":"retry","at":100}""",
            """{"type":"result","result":"ok"}""",
            """{"type":"agentic_prompt","text":"retry","at":250}""",
        ))
        assertEquals(2, r.display().count { it is PromptNode && it.text == "retry" })
    }

    // at==0 nodes (optimistic overlays / missing timestamp) are fail-open: never collapsed on text
    // alone, proving the dedup keys on the server `at`, never on text by itself.
    @Test fun at_zero_prompts_are_not_collapsed() {
        val r = TranscriptReducer()
        r.seedFromLog(listOf(
            """{"type":"agentic_prompt","text":"hi","at":0}""",
            """{"type":"agentic_prompt","text":"hi","at":0}""",
        ))
        assertEquals(2, r.display().count { it is PromptNode && it.text == "hi" })
    }

    // Agent card regression ("sometimes appears then disappears"): a subagent's result arrives ONLY on a
    // live agentResult frame (never persisted to the log). A reseed (turn-end / reconnect) rebuilds the
    // SpawnNode from the log WITHOUT the result; the reducer must re-attach the remembered result so the
    // card body does not vanish.
    @Test fun agent_result_survives_reseed_from_log() {
        val assistantLog =
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"tu_1","name":"Task","input":{"subagent_type":"Explore","description":"search"}}]}}"""
        val r = TranscriptReducer()
        r.applyFrame("""{"kind":"agent","agents":[{"id":"tu_1","agentType":"Explore","description":"search"}]}""")
        r.applyFrame("""{"kind":"agentResult","toolUseId":"tu_1","text":"found it"}""")
        // turn ends → repo reseeds from the fresh persisted log (which has the assistant line, NOT result)
        r.seedFromLog(listOf(assistantLog))
        val card = r.display().filterIsInstance<SpawnNode>().single { it.id == "tu_1" }
        assertEquals("found it", card.result)
    }
}
