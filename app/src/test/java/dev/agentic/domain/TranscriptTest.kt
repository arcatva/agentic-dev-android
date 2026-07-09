package dev.agentic.domain

import org.junit.Assert.*
import org.junit.Test

class TranscriptTest {
    @Test fun text_deltas_accumulate_into_one_text_node() {
        var n = appendText(emptyList(), "Hel")
        n = appendText(n, "lo")
        assertEquals(1, n.size)
        assertEquals("Hello", (n[0] as TextNode).text)
    }

    @Test fun applyEvent_prompt_then_tool() {
        val (a, _) = applyEvent(emptyList(), """{"kind":"prompt","text":"hi","at":5}""")
        assertTrue(a[0] is PromptNode)
        val (b, _) = applyEvent(a, """{"kind":"tool","name":"Read","input":{"file_path":"/x/Y.kt"}}""")
        assertEquals("Y.kt", (b[1] as ToolNode).summary)
    }

    @Test fun result_frame_appends_answer() {
        val (n, _) = applyEvent(emptyList(), """{"kind":"result","text":"done"}""")
        assertEquals("done", (n[0] as AnswerNode).text)
    }

    @Test fun frameBusy_true_for_content_false_for_result() {
        assertEquals(true, frameBusy("""{"kind":"tool"}"""))
        assertEquals(false, frameBusy("""{"kind":"result"}"""))
        assertNull(frameBusy("""{"kind":"other"}"""))
    }

    @Test fun retry_frame_makes_retry_node() {
        val (n, _) = applyEvent(emptyList(), """{"kind":"retry","attempt":2,"maxRetries":5,"category":"overloaded"}""")
        assertEquals(1, n.size)
        val r = n[0] as RetryNode
        assertEquals(2, r.attempt)
        assertEquals(5, r.maxRetries)
        assertEquals("overloaded", r.category)
    }

    @Test fun consecutive_retries_collapse_into_latest() {
        val (a, _) = applyEvent(emptyList(), """{"kind":"retry","attempt":1,"maxRetries":5,"category":"overloaded"}""")
        val (b, _) = applyEvent(a, """{"kind":"retry","attempt":2,"maxRetries":5,"category":"overloaded"}""")
        assertEquals(1, b.size)
        assertEquals(2, (b[0] as RetryNode).attempt)
    }

    @Test fun frameBusy_true_for_retry() {
        assertEquals(true, frameBusy("""{"kind":"retry"}"""))
    }

    @Test fun agent_event_captures_id_then_result_attaches_to_spawn() {
        val (a, _) = applyEvent(emptyList(), """{"kind":"agent","agents":[{"id":"tu_1","agentType":"Explore","description":"search"}]}""")
        val spawn = a[0] as SpawnNode
        assertEquals("tu_1", spawn.id)
        assertEquals("Explore", spawn.type)
        val (b, _) = applyEvent(a, """{"kind":"agentResult","toolUseId":"tu_1","text":"found it"}""")
        assertEquals(1, b.size) // attached under the agent card, not appended as a separate node
        assertEquals("found it", (b[0] as SpawnNode).result)
    }

    @Test fun agent_result_without_matching_spawn_is_not_dropped() {
        val (n, _) = applyEvent(emptyList(), """{"kind":"agentResult","toolUseId":"tu_x","text":"orphan"}""")
        assertEquals(1, n.size)
        assertEquals("orphan", (n[0] as SpawnNode).result)
    }

    @Test fun trailing_text_kept_when_turn_ends_without_result() {
        var n = applyEvent(emptyList(), """{"kind":"prompt","text":"go","at":1}""").first
        n = applyEvent(n, """{"kind":"text","text":"the answer"}""").first
        val (ended, isEnd) = applyEvent(n, """{"kind":"other","raw":{"engineExit":{"code":1}}}""")
        assertTrue(isEnd)
        assertEquals("the answer", (ended.last { it is TextNode } as TextNode).text)
    }

    @Test fun result_dedupes_duplicate_trailing_text_into_single_answer() {
        // The SDK repeats the final assistant message in the `result` frame, so the streamed `text`
        // and the `result` text are byte-identical. The result must render ONCE: appendAnswer drops
        // the duplicate trailing TextNode and keeps a single AnswerNode (regression: it used to show
        // both the streamed TextNode bubble and the AnswerNode bubble — the answer printed twice).
        var n = applyEvent(emptyList(), """{"kind":"prompt","text":"go","at":1}""").first
        n = applyEvent(n, """{"kind":"text","text":"the answer"}""").first
        n = applyEvent(n, """{"kind":"result","text":"the answer"}""").first
        val (ended, _) = applyEvent(n, """{"kind":"other","raw":{"engineExit":{"code":0}}}""")
        assertEquals("no duplicate streamed copy of the answer", 0, ended.count { it is TextNode })
        assertEquals(1, ended.count { it is AnswerNode })
        assertEquals("the answer", (ended.last { it is AnswerNode } as AnswerNode).text)
    }

    @Test fun result_keeps_distinct_trailing_text_alongside_answer() {
        // Only an EXACT duplicate is dropped. A genuinely different trailing prose (e.g. some
        // intermediate text that is not the final answer) is preserved; the AnswerNode is still added.
        var n = applyEvent(emptyList(), """{"kind":"prompt","text":"go","at":1}""").first
        n = applyEvent(n, """{"kind":"text","text":"looking into it"}""").first
        n = applyEvent(n, """{"kind":"result","text":"the answer"}""").first
        assertEquals(1, n.count { it is TextNode })
        assertEquals(1, n.count { it is AnswerNode })
    }

    @Test fun buildFromLog_dedupes_duplicate_trailing_text_into_single_answer() {
        // Same dedup on the persisted-log replay path (reopen/reconnect), so the duplicate does not
        // reappear when the transcript is rebuilt from disk.
        val nodes = buildFromLog(
            listOf(
                """{"type":"agentic_prompt","text":"go","at":1}""",
                """{"type":"stream_event","event":{"delta":{"type":"text_delta","text":"the answer"}}}""",
                """{"type":"result","result":"the answer"}""",
            )
        )
        assertEquals(0, nodes.count { it is TextNode })
        assertEquals(1, nodes.count { it is AnswerNode })
        assertEquals("the answer", (nodes.last { it is AnswerNode } as AnswerNode).text)
    }

    @Test fun buildFromLog_surfaces_text_when_killed_without_result() {
        val nodes = buildFromLog(
            listOf(
                """{"type":"agentic_prompt","text":"go","at":1}""",
                """{"type":"stream_event","event":{"delta":{"type":"text_delta","text":"partial answer"}}}""",
            )
        )
        assertEquals("partial answer", (nodes.last { it is TextNode } as TextNode).text)
    }

    @Test fun buildFromLog_attaches_agent_result_marker_to_its_spawn() {
        // The server persists a compact `agent_result` marker (only for spawned subagents). On reopen,
        // buildFromLog must attach its text to the matching SpawnNode so the agent card's body returns.
        val nodes = buildFromLog(
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"tu_1","name":"Agent","input":{"subagent_type":"Explore","description":"search"}}]}}""",
                """{"type":"agent_result","toolUseId":"tu_1","text":"found it"}""",
            )
        )
        assertEquals(1, nodes.size) // attached under the agent card, not a separate node
        val spawn = nodes[0] as SpawnNode
        assertEquals("Explore", spawn.type)
        assertEquals("found it", spawn.result)
    }

    @Test fun buildFromLog_parses_prompt_and_result() {
        val nodes = buildFromLog(
            listOf(
                """{"type":"agentic_prompt","text":"go","at":1}""",
                """{"type":"result","result":"ok"}""",
            )
        )
        assertTrue(nodes.first() is PromptNode)
        assertTrue(nodes.last() is AnswerNode)
    }

    // ── Sub-agent nesting: events carrying parentToolUseId route under their SpawnNode ──────────────

    @Test fun subagent_tool_nests_under_its_spawn() {
        val (a, _) = applyEvent(emptyList(), """{"kind":"agent","agents":[{"id":"tu_1","agentType":"Explore","description":"search"}]}""")
        val (b, _) = applyEvent(a, """{"kind":"tool","name":"Bash","input":{"command":"echo h"},"parentToolUseId":"tu_1"}""")
        assertEquals(1, b.size) // the tool nested under the spawn, not appended top-level
        val spawn = b[0] as SpawnNode
        assertEquals(1, spawn.children.size)
        assertEquals("Bash", (spawn.children[0] as ToolNode).name)
    }

    @Test fun subagent_tool_without_matching_spawn_falls_back_to_top_level() {
        val (n, _) = applyEvent(emptyList(), """{"kind":"tool","name":"Bash","input":{"command":"echo h"},"parentToolUseId":"tu_missing"}""")
        assertEquals(1, n.size) // never dropped
        assertTrue(n[0] is ToolNode)
    }

    @Test fun main_agent_tool_stays_top_level_with_spawn_present() {
        val (a, _) = applyEvent(emptyList(), """{"kind":"agent","agents":[{"id":"tu_1","agentType":"Explore","description":"x"}]}""")
        val (b, _) = applyEvent(a, """{"kind":"tool","name":"Read","input":{"file_path":"/x/Y.kt"}}""") // no parentToolUseId
        assertEquals(2, b.size)
        assertTrue(b[1] is ToolNode)
        assertEquals(0, (b[0] as SpawnNode).children.size)
    }

    @Test fun subagent_text_coalesces_within_its_spawn_children() {
        val (a, _) = applyEvent(emptyList(), """{"kind":"agent","agents":[{"id":"tu_1","agentType":"Explore","description":"x"}]}""")
        var n = applyEvent(a, """{"kind":"text","text":"Hel","parentToolUseId":"tu_1"}""").first
        n = applyEvent(n, """{"kind":"text","text":"lo","parentToolUseId":"tu_1"}""").first
        assertEquals(1, n.size)
        val spawn = n[0] as SpawnNode
        assertEquals(1, spawn.children.size)
        assertEquals("Hello", (spawn.children[0] as TextNode).text)
    }

    @Test fun subagent_text_does_not_merge_into_main_trailing_text() {
        val main = applyEvent(emptyList(), """{"kind":"text","text":"main "}""").first
        val (a, _) = applyEvent(main, """{"kind":"agent","agents":[{"id":"tu_1","agentType":"Explore","description":"x"}]}""")
        val (b, _) = applyEvent(a, """{"kind":"text","text":"sub","parentToolUseId":"tu_1"}""")
        assertEquals("main ", (b[0] as TextNode).text) // main text untouched
        assertEquals("sub", ((b[1] as SpawnNode).children[0] as TextNode).text)
    }

    @Test fun buildFromLog_nests_subagent_tool_under_its_spawn() {
        val nodes = buildFromLog(
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"tu_1","name":"Agent","input":{"subagent_type":"Explore","description":"search"}}]}}""",
                """{"type":"assistant","parent_tool_use_id":"tu_1","message":{"content":[{"type":"tool_use","id":"tu_2","name":"Bash","input":{"command":"echo h"}}]}}""",
            )
        )
        assertEquals(1, nodes.size)
        val spawn = nodes[0] as SpawnNode
        assertEquals(1, spawn.children.size)
        assertEquals("Bash", (spawn.children[0] as ToolNode).name)
    }

    @Test fun subagent_tool_routes_by_id_not_latest_spawn() {
        // Two agents are live; tu_2 is the most-recent SpawnNode. A tool tagged for tu_1 must still
        // nest under tu_1 (matched by id) — NOT under the latest spawn. Guards route()'s id predicate.
        var n = applyEvent(emptyList(), """{"kind":"agent","agents":[{"id":"tu_1","agentType":"Explore","description":"a"}]}""").first
        n = applyEvent(n, """{"kind":"agent","agents":[{"id":"tu_2","agentType":"Plan","description":"b"}]}""").first
        n = applyEvent(n, """{"kind":"tool","name":"Bash","input":{"command":"echo h"},"parentToolUseId":"tu_1"}""").first
        n = applyEvent(n, """{"kind":"tool","name":"Read","input":{"file_path":"/x/Y.kt"},"parentToolUseId":"tu_2"}""").first
        assertEquals(2, n.size)
        val a = n[0] as SpawnNode
        val b = n[1] as SpawnNode
        assertEquals("tu_1", a.id)
        assertEquals("tu_2", b.id)
        assertEquals("Bash", (a.children.single() as ToolNode).name)
        assertEquals("Read", (b.children.single() as ToolNode).name)
    }

    @Test fun agent_result_preserves_already_nested_children() {
        // The live order is: subagent's steps stream in, then the agentResult frame closes the card.
        // attachAgentResult must set the result WITHOUT clobbering the children collected so far.
        var n = applyEvent(emptyList(), """{"kind":"agent","agents":[{"id":"tu_1","agentType":"Explore","description":"a"}]}""").first
        n = applyEvent(n, """{"kind":"tool","name":"Bash","input":{"command":"echo h"},"parentToolUseId":"tu_1"}""").first
        n = applyEvent(n, """{"kind":"agentResult","toolUseId":"tu_1","text":"found it"}""").first
        assertEquals(1, n.size)
        val spawn = n[0] as SpawnNode
        assertEquals("found it", spawn.result)
        assertEquals(1, spawn.children.size)
        assertEquals("Bash", (spawn.children[0] as ToolNode).name)
    }

    @Test fun buildFromLog_nests_subagent_text_delta_under_its_spawn() {
        // The reseed/reconnect path (where the original leak lived): a subagent's streamed text arrives
        // as stream_event text_deltas tagged with parent_tool_use_id and must coalesce under its spawn.
        val nodes = buildFromLog(
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"tu_1","name":"Agent","input":{"subagent_type":"Explore","description":"search"}}]}}""",
                """{"type":"stream_event","parent_tool_use_id":"tu_1","event":{"delta":{"type":"text_delta","text":"Hel"}}}""",
                """{"type":"stream_event","parent_tool_use_id":"tu_1","event":{"delta":{"type":"text_delta","text":"lo"}}}""",
            )
        )
        assertEquals(1, nodes.size)
        val spawn = nodes[0] as SpawnNode
        assertEquals(1, spawn.children.size)
        assertEquals("Hello", (spawn.children[0] as TextNode).text)
    }

    // ── Assistant prose blocks render on reseed (includePartialMessages OFF — no stream deltas) ──────

    @Test fun buildFromLog_renders_assistant_text_before_parked_ask() {
        // The reported bug: with the bridge's includePartialMessages OFF, the model's prose lives ONLY
        // in the complete `assistant` message (a text block next to the AskUserQuestion tool_use). A
        // turn parked on that ask has no `result` line yet, so on a background-return reseed the prose
        // must still come from the assistant text block — not vanish until the question is answered.
        val nodes = buildFromLog(
            listOf(
                """{"type":"agentic_prompt","text":"go","at":1}""",
                """{"type":"assistant","message":{"content":[""" +
                    """{"type":"text","text":"Here are the options"},""" +
                    """{"type":"tool_use","id":"tu_1","name":"AskUserQuestion","input":{"questions":[{"question":"Pick one","options":[{"label":"A"},{"label":"B"}]}]}}""" +
                    """]}}""",
            )
        )
        val textIdx = nodes.indexOfFirst { it is TextNode && it.text == "Here are the options" }
        val askIdx = nodes.indexOfFirst { it is AskNode }
        assertTrue("assistant prose rendered", textIdx >= 0)
        assertTrue("ask card rendered", askIdx >= 0)
        assertTrue("prose renders before the ask card", textIdx < askIdx)
        assertFalse("ask is unanswered while parked", (nodes[askIdx] as AskNode).answered)
    }

    @Test fun buildFromLog_completed_turn_dedupes_assistant_final_text_against_result() {
        // A COMPLETED turn repeats the final assistant text in the `result` line. Now that buildFromLog
        // renders the assistant text block, appendAnswer must still drop that single trailing duplicate
        // so the answer renders exactly once (no double bubble) — matching the live applyEvent path.
        val nodes = buildFromLog(
            listOf(
                """{"type":"agentic_prompt","text":"go","at":1}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"the answer"}]}}""",
                """{"type":"result","result":"the answer"}""",
            )
        )
        assertEquals(0, nodes.count { it is TextNode })
        assertEquals(1, nodes.count { it is AnswerNode })
        assertEquals("the answer", (nodes.last { it is AnswerNode } as AnswerNode).text)
    }

    @Test fun buildFromLog_renders_assistant_thinking_block() {
        // Extended-thinking blocks arrive in the same complete assistant message (partials OFF). They
        // must reseed as a ThinkingNode, mirroring the live stream parser's `thinking` event.
        val nodes = buildFromLog(
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"pondering"}]}}""",
            )
        )
        assertEquals(1, nodes.count { it is ThinkingNode })
        assertEquals("pondering", (nodes.first { it is ThinkingNode } as ThinkingNode).text)
    }

}
