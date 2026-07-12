package dev.agentic.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * Proves the O(L²) → O(L) streaming-accumulation optimisation in [TranscriptReducer] is
 * behaviour-preserving: for representative frame sequences, the optimised reducer's [display],
 * [raw], and [applyFrameWithBusy] results are byte-for-byte identical to the prior behaviour.
 *
 * The "prior behaviour" oracle ([referenceDisplay]) folds the SAME frames with the pure
 * [applyEvent] per token — i.e. the old reducer, which coalesced each delta with the immutable
 * `last.text + delta` concat — then runs the identical display pipeline. Equal display() lists
 * (Node is a data class, so `==` is structural / value equality on every field including `text`)
 * means the StringBuilder path produces the same nodes the String-concat path did.
 */
class TranscriptReducerStreamingTest {

    // ── Oracle: the old per-token path (pure applyEvent), same display pipeline as the reducer. ──
    private fun referenceNodes(frames: List<String>): List<Node> {
        var n = emptyList<Node>()
        for (f in frames) n = applyEvent(n, f).first
        return n
    }

    private fun referenceDisplay(frames: List<String>, shared: List<AttachmentNode> = emptyList()): List<Node> {
        val n = referenceNodes(frames)
        return interleaveShared(groupTools(markAnsweredAsks(dedupePrompts(n))), shared)
    }

    private fun reduce(frames: List<String>): TranscriptReducer =
        TranscriptReducer().apply { frames.forEach { applyFrame(it) } }

    private fun textFrame(t: String, parent: String? = null) =
        if (parent == null) """{"kind":"text","text":${esc(t)}}"""
        else """{"kind":"text","text":${esc(t)},"parentToolUseId":"$parent"}"""

    private fun thinkingFrame(t: String) = """{"kind":"thinking","text":${esc(t)}}"""

    // Minimal JSON string escaper for the delta payloads used here.
    private fun esc(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    // ── 1. Many consecutive text tokens: display identical to the per-token oracle, and the final
    //       node text equals the naive concatenation of every delta. ──────────────────────────────
    @Test fun many_text_tokens_match_oracle_and_concatenation() {
        val deltas = (0 until 500).map { "tok$it " }
        val frames = listOf("""{"kind":"prompt","text":"go","at":1}""") + deltas.map { textFrame(it) }

        val r = reduce(frames)
        assertEquals("display() must equal the per-token oracle", referenceDisplay(frames), r.display())

        val expected = deltas.joinToString("")
        val node = r.display().filterIsInstance<TextNode>().single()
        assertEquals("accumulated text == naive concatenation of all deltas", expected, node.text)
    }

    // ── 2. Many thinking tokens: same guarantees for the thinking run. ───────────────────────────
    @Test fun many_thinking_tokens_match_oracle_and_concatenation() {
        val deltas = (0 until 400).map { "th$it " }
        val frames = listOf("""{"kind":"prompt","text":"go","at":1}""") + deltas.map { thinkingFrame(it) }

        val r = reduce(frames)
        assertEquals(referenceDisplay(frames), r.display())

        val node = r.display().filterIsInstance<ThinkingNode>().single()
        assertEquals(deltas.joinToString(""), node.text)
    }

    // ── 3. text run → tool node breaks the run → text run again. The second run must be a SEPARATE
    //       TextNode whose text is exactly its own deltas (the builder reset, not carrying over). ──
    @Test fun text_run_then_tool_then_text_run_again() {
        val run1 = (0 until 50).map { "a$it" }
        val run2 = (0 until 50).map { "b$it" }
        val frames = buildList {
            add("""{"kind":"prompt","text":"go","at":1}""")
            run1.forEach { add(textFrame(it)) }
            add("""{"kind":"tool","name":"Read","input":{"file_path":"/x/Y.kt"}}""")
            run2.forEach { add(textFrame(it)) }
        }

        val r = reduce(frames)
        assertEquals(referenceDisplay(frames), r.display())

        val texts = r.display().filterIsInstance<TextNode>()
        assertEquals(2, texts.size)
        assertEquals(run1.joinToString(""), texts[0].text)
        assertEquals(run2.joinToString(""), texts[1].text)
    }

    // ── 4. text run interrupted by a prompt/spawn node, then resumed. ────────────────────────────
    @Test fun text_run_then_prompt_then_text_and_thinking_interleaved() {
        val frames = buildList {
            add("""{"kind":"prompt","text":"first","at":1}""")
            (0 until 30).forEach { add(textFrame("x$it")) }
            add("""{"kind":"agent","agents":[{"id":"tu_1","agentType":"Explore","description":"d"}]}""")
            (0 until 30).forEach { add(textFrame("y$it")) }
        }
        val r = reduce(frames)
        assertEquals(referenceDisplay(frames), r.display())
    }

    // ── 5. Thinking run interleaved with text: thinking, then text breaks it, then thinking again.
    //       Each kind switch flushes its builder; neither run leaks into the other. ───────────────
    @Test fun thinking_and_text_runs_interleave_without_leaking() {
        val frames = buildList {
            add("""{"kind":"prompt","text":"go","at":1}""")
            (0 until 40).forEach { add(thinkingFrame("t$it")) }
            (0 until 40).forEach { add(textFrame("u$it")) }
            (0 until 40).forEach { add(thinkingFrame("v$it")) }
            (0 until 40).forEach { add(textFrame("w$it")) }
        }
        val r = reduce(frames)
        assertEquals(referenceDisplay(frames), r.display())

        // Nodes alternate thinking/text/thinking/text — four distinct nodes, each its own deltas.
        val display = r.display()
        val streamNodes = display.filter { it is TextNode || it is ThinkingNode }
        assertEquals(4, streamNodes.size)
        assertEquals((0 until 40).joinToString("") { "t$it" }, (streamNodes[0] as ThinkingNode).text)
        assertEquals((0 until 40).joinToString("") { "u$it" }, (streamNodes[1] as TextNode).text)
        assertEquals((0 until 40).joinToString("") { "v$it" }, (streamNodes[2] as ThinkingNode).text)
        assertEquals((0 until 40).joinToString("") { "w$it" }, (streamNodes[3] as TextNode).text)
    }

    // ── 6. Subagent streaming: deltas routed under a SpawnNode's children must accumulate inside
    //       that scope (not the top level), matching the oracle. ──────────────────────────────────
    @Test fun subagent_streaming_accumulates_within_spawn_children() {
        val main = (0 until 25).map { "m$it" }
        val sub = (0 until 60).map { "s$it" }
        val frames = buildList {
            add("""{"kind":"prompt","text":"go","at":1}""")
            main.forEach { add(textFrame(it)) }                                  // top-level run
            add("""{"kind":"agent","agents":[{"id":"tu_1","agentType":"Explore","description":"d"}]}""")
            sub.forEach { add(textFrame(it, parent = "tu_1")) }                   // nested run under tu_1
        }
        val r = reduce(frames)
        assertEquals(referenceDisplay(frames), r.display())

        val display = r.display()
        assertEquals(main.joinToString(""), display.filterIsInstance<TextNode>().first().text)
        val spawn = display.filterIsInstance<SpawnNode>().single()
        assertEquals(sub.joinToString(""), (spawn.children.single() as TextNode).text)
    }

    // ── 7. Interleaved main + subagent text in the SAME burst: a top-level delta after a nested run
    //       (and vice-versa) must each land in its own scope. ─────────────────────────────────────
    @Test fun interleaved_main_and_subagent_text_match_oracle() {
        val frames = buildList {
            add("""{"kind":"agent","agents":[{"id":"tu_1","agentType":"Explore","description":"d"}]}""")
            add(textFrame("main1 "))
            add(textFrame("sub1 ", parent = "tu_1"))
            add(textFrame("main2 "))
            add(textFrame("sub2 ", parent = "tu_1"))
            add(textFrame("main3 "))
        }
        val r = reduce(frames)
        assertEquals(referenceDisplay(frames), r.display())
    }

    // ── 8. Reseed after streaming: a live streamed run, then seedFromLog rebuilds from the log.
    //       The open builder must be dropped and the displayed transcript must match the oracle for
    //       the log (no stale streamed text leaking through). ─────────────────────────────────────
    @Test fun reseed_after_streaming_drops_builder_and_matches_log() {
        val r = TranscriptReducer()
        r.applyFrame("""{"kind":"prompt","text":"go","at":1}""")
        repeat(100) { r.applyFrame(textFrame("z$it")) }
        // The persisted log of the same turn (text arrives as stream_event text_delta lines).
        val log = listOf(
            """{"type":"agentic_prompt","text":"go","at":1}""",
            """{"type":"stream_event","event":{"delta":{"type":"text_delta","text":"final answer text"}}}""",
            """{"type":"result","result":"done"}""",
        )
        r.seedFromLog(log)

        val expected = run {
            val n = buildFromLog(log)
            interleaveShared(groupTools(markAnsweredAsks(dedupePrompts(n))), emptyList())
        }
        assertEquals(expected, r.display())
        // The pre-reseed streamed tokens must be gone; only the log's text remains.
        assertEquals("final answer text", r.display().filterIsInstance<TextNode>().single().text)
    }

    // ── 9. Streaming THEN reseed THEN stream again: the builder must restart cleanly against the
    //       rebuilt nodes (no carry-over of the first run's builder). ─────────────────────────────
    @Test fun stream_then_reseed_then_stream_again_restarts_cleanly() {
        val r = TranscriptReducer()
        repeat(50) { r.applyFrame(textFrame("old$it")) }
        r.seedFromLog(listOf("""{"type":"agentic_prompt","text":"go","at":1}"""))
        val newDeltas = (0 until 50).map { "new$it" }
        newDeltas.forEach { r.applyFrame(textFrame(it)) }

        assertEquals(newDeltas.joinToString(""), r.display().filterIsInstance<TextNode>().single().text)
    }

    // ── 10. raw (the other snapshot reader) also materialises the open run. ──────────────────────
    @Test fun raw_materialises_open_run() {
        val deltas = (0 until 80).map { "r$it" }
        val r = TranscriptReducer()
        deltas.forEach { r.applyFrame(textFrame(it)) }
        // No display() called yet — raw must still carry the fully accumulated text.
        val node = r.raw.filterIsInstance<TextNode>().single()
        assertEquals(deltas.joinToString(""), node.text)
    }

    // ── 11. display() mid-stream then more deltas: a snapshot must NOT freeze the run. Materialising
    //        for a display() keeps the builder open so later deltas keep accumulating. ─────────────
    @Test fun display_midstream_does_not_freeze_accumulation() {
        val r = TranscriptReducer()
        (0 until 30).forEach { r.applyFrame(textFrame("p$it")) }
        val mid = r.display().filterIsInstance<TextNode>().single().text
        assertEquals((0 until 30).joinToString("") { "p$it" }, mid)
        (30 until 60).forEach { r.applyFrame(textFrame("p$it")) }
        val full = r.display().filterIsInstance<TextNode>().single().text
        assertEquals((0 until 60).joinToString("") { "p$it" }, full)
    }

    // ── 12. applyFrameWithBusy return values for a streamed run are unchanged: every text/thinking
    //        delta yields (ended=false, busy=true), exactly as the per-frame applyEvent mapping. ───
    @Test fun applyFrameWithBusy_return_values_unchanged_for_deltas() {
        val r = TranscriptReducer()
        // First delta (starts the run) and continued deltas must both report (false, true).
        repeat(20) {
            val res = r.applyFrameWithBusy(textFrame("d$it"))
            assertEquals(TranscriptReducer.FrameResult(ended = false, busy = true), res)
        }
        repeat(20) {
            val res = r.applyFrameWithBusy(thinkingFrame("e$it"))
            assertEquals(TranscriptReducer.FrameResult(ended = false, busy = true), res)
        }
        // A result frame still reports busy=false; engineExit still reports ended=true.
        assertEquals(
            TranscriptReducer.FrameResult(ended = false, busy = false),
            r.applyFrameWithBusy("""{"kind":"result","text":"done"}""")
        )
        assertEquals(
            TranscriptReducer.FrameResult(ended = true, busy = null),
            r.applyFrameWithBusy("""{"kind":"other","raw":{"engineExit":true}}""")
        )
    }

    // ── 13. A full representative turn (prompt, thinking, text, tool, more text, result) end-to-end
    //        equals the oracle — the integration guard against any drift across all node kinds. ────
    @Test fun full_turn_sequence_matches_oracle() {
        val frames = buildList {
            add("""{"kind":"prompt","text":"please do it","at":1}""")
            (0 until 20).forEach { add(thinkingFrame("reason$it ")) }
            (0 until 40).forEach { add(textFrame("narr$it ")) }
            add("""{"kind":"tool","name":"Read","input":{"file_path":"/a/B.kt"}}""")
            add("""{"kind":"tool","name":"Read","input":{"file_path":"/a/C.kt"}}""")
            (0 until 40).forEach { add(textFrame("more$it ")) }
            add("""{"kind":"result","text":"the final answer"}""")
        }
        val r = reduce(frames)
        assertEquals(referenceDisplay(frames), r.display())
    }

    // ── 14. Empty deltas interspersed (the backend can emit empty text deltas) must not start or
    //        corrupt a run; the accumulation still equals the concatenation of the non-empty parts. ─
    @Test fun empty_deltas_do_not_corrupt_run() {
        val frames = buildList {
            add("""{"kind":"prompt","text":"go","at":1}""")
            add(textFrame(""))            // empty before any text node exists → no node
            add(textFrame("hello "))
            add(textFrame(""))            // empty mid-run → no change
            add(textFrame("world"))
        }
        val r = reduce(frames)
        assertEquals(referenceDisplay(frames), r.display())
        assertEquals("hello world", r.display().filterIsInstance<TextNode>().single().text)
    }

    // ── 15. setShared between deltas (the outbox can update mid-stream) must flush+materialise so the
    //        interleave runs over the real accumulated text, matching the oracle. ──────────────────
    @Test fun setShared_midstream_matches_oracle() {
        val deltas = (0 until 40).map { "c$it " }
        val shared = listOf(AttachmentNode("out.txt", at = 5))
        val frames = listOf("""{"kind":"prompt","text":"go","at":1}""") + deltas.map { textFrame(it) }

        val r = TranscriptReducer()
        frames.forEach { r.applyFrame(it) }
        r.setShared(shared)

        assertEquals(referenceDisplay(frames, shared), r.display())
    }
}
