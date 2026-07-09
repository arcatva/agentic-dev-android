package dev.agentic.ui.session

import dev.agentic.domain.AnswerNode
import dev.agentic.domain.Node
import dev.agentic.domain.PromptNode
import dev.agentic.domain.ToolNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure math behind the message fast-scroll slider. No Compose/Android types here —
 * these are plain JVM tests over [TranscriptScrollbarMath.kt].
 *
 * The transcript LazyColumn is `reverseLayout = true`, so the trickiest parts are the
 * chronological-index ↔ reverse-index inversion and the shared "from-top fraction" position axis
 * (so the resting thumb and the snap targets stay consistent). These tests pin both down.
 */
class TranscriptScrollbarTest {

    // ── userMessageAnchors ──────────────────────────────────────────────────────────────────────

    @Test fun empty_transcript_has_no_anchors() {
        assertEquals(emptyList<MsgAnchor>(), userMessageAnchors(emptyList()))
    }

    @Test fun transcript_without_prompts_has_no_anchors() {
        val nodes = listOf<Node>(AnswerNode("hi"), ToolNode("Read", "f", ""))
        assertTrue(userMessageAnchors(nodes).isEmpty())
    }

    @Test fun a_single_prompt_yields_one_anchor_at_the_top() {
        val anchors = userMessageAnchors(listOf(PromptNode("hello")))
        assertEquals(1, anchors.size)
        assertEquals(0, anchors[0].chronoIndex)
        assertEquals(0, anchors[0].revIndex) // size 1 → rev = 0
        assertEquals(0f, anchors[0].fraction, 1e-4f)
        assertEquals("hello", anchors[0].preview)
    }

    @Test fun anchors_are_oldest_first_with_reverse_indices_and_fractions() {
        // chrono: 0=Prompt, 1=Answer, 2=Prompt, 3=Tool  (size 4, span = 3)
        val nodes = listOf<Node>(
            PromptNode("first"),
            AnswerNode("a"),
            PromptNode("second"),
            ToolNode("Read", "f", ""),
        )
        val anchors = userMessageAnchors(nodes)
        assertEquals(2, anchors.size)
        assertEquals(0, anchors[0].chronoIndex)
        assertEquals(3, anchors[0].revIndex) // 4 - 1 - 0
        assertEquals(0f, anchors[0].fraction, 1e-4f) // oldest → top
        assertEquals("first", anchors[0].preview)
        assertEquals(2, anchors[1].chronoIndex)
        assertEquals(1, anchors[1].revIndex) // 4 - 1 - 2
        assertEquals(2f / 3f, anchors[1].fraction, 1e-4f) // chrono 2 of span 3
        assertEquals("second", anchors[1].preview)
    }

    @Test fun newest_prompt_gets_fraction_one() {
        val nodes = listOf<Node>(AnswerNode("a"), AnswerNode("b"), PromptNode("newest"))
        val anchors = userMessageAnchors(nodes)
        assertEquals(1f, anchors.single().fraction, 1e-4f) // chrono 2 of span 2 → bottom
    }

    @Test fun blank_prompts_are_skipped_matching_the_render_condition() {
        val nodes = listOf<Node>(PromptNode("   "), PromptNode("real"))
        val anchors = userMessageAnchors(nodes)
        assertEquals(1, anchors.size)
        assertEquals(1, anchors[0].chronoIndex)
        assertEquals("real", anchors[0].preview)
    }

    @Test fun preview_is_single_lined_and_trimmed() {
        val anchors = userMessageAnchors(listOf(PromptNode("  line1\nline2  ")))
        assertEquals("line1 line2", anchors[0].preview)
    }

    @Test fun preview_is_capped_to_eighty_characters() {
        val long = "x".repeat(100)
        val preview = userMessageAnchors(listOf(PromptNode(long))).single().preview
        assertEquals(80, preview.length)
        assertEquals("x".repeat(80), preview)
    }

    // ── nearestAnchorIndex: drag fraction (0=top/oldest) → nearest anchor by real position ───────

    private fun anchorsAt(vararg fractions: Float): List<MsgAnchor> =
        fractions.mapIndexed { i, f -> MsgAnchor(chronoIndex = i, revIndex = i, fraction = f, preview = "m$i") }

    @Test fun nearest_anchor_picks_closest_by_fraction() {
        val anchors = anchorsAt(0f, 0.5f, 1f)
        assertEquals(0, nearestAnchorIndex(0f, anchors))
        assertEquals(1, nearestAnchorIndex(0.4f, anchors)) // closest to 0.5
        assertEquals(2, nearestAnchorIndex(0.9f, anchors))
    }

    @Test fun nearest_anchor_to_the_top_hits_the_first_message() {
        // Even when anchors are clustered (real positions, not evenly spread), dragging to the very
        // top selects the first message.
        val anchors = anchorsAt(0f, 0.02f, 0.5f, 0.51f)
        assertEquals(0, nearestAnchorIndex(0f, anchors))
        assertEquals(0, nearestAnchorIndex(-0.3f, anchors)) // over-drag past the top
    }

    @Test fun nearest_anchor_to_the_bottom_hits_the_last_message() {
        val anchors = anchorsAt(0f, 0.02f, 0.5f, 0.51f)
        assertEquals(3, nearestAnchorIndex(1f, anchors))
        assertEquals(3, nearestAnchorIndex(1.4f, anchors)) // over-drag past the bottom
    }

    @Test fun nearest_anchor_on_empty_list_is_zero() {
        assertEquals(0, nearestAnchorIndex(0.5f, emptyList()))
    }

    // ── restingThumbFraction: leading-edge item + intra-item offset → from-top position ──────────

    @Test fun resting_thumb_at_bottom_when_newest_is_at_the_leading_edge() {
        // first = 0, offset = 0 → newest at the bottom → thumb pinned to the bottom.
        assertEquals(1f, restingThumbFraction(0, 0, 50, 10), 1e-4f)
    }

    @Test fun resting_thumb_moves_up_toward_older_content() {
        // first = 3 of span 9 → towardTop 3/9 → fromTop 6/9.
        assertEquals(6f / 9f, restingThumbFraction(3, 0, 50, 10), 1e-4f)
    }

    @Test fun resting_thumb_uses_intra_item_offset_for_smooth_motion() {
        // Half-scrolled into the bottom item (offset 25 of size 50) → scrolled 0.5 → fromTop 1 - 0.5/9.
        assertEquals(1f - (0.5f / 9f), restingThumbFraction(0, 25, 50, 10), 1e-4f)
    }

    @Test fun resting_thumb_is_safe_for_tiny_or_zero_item_size() {
        assertEquals(1f, restingThumbFraction(0, 0, 0, 1), 1e-4f)   // not scrollable
        assertEquals(1f, restingThumbFraction(0, 10, 0, 10), 1e-4f) // size 0 → within 0 → bottom
    }

    @Test fun resting_thumb_matches_anchor_fraction_when_that_message_is_the_leading_item() {
        // Consistency: when message X (rev R) is the bottom/leading visible item at offset 0, the
        // resting thumb fraction equals X's anchor fraction.
        val nodes = (0 until 10).map { if (it == 2) PromptNode("p") as Node else AnswerNode("a") }
        val anchor = userMessageAnchors(nodes).single() // chrono 2, rev 7, fraction 2/9
        assertEquals(anchor.fraction, restingThumbFraction(anchor.revIndex, 0, 40, 10), 1e-4f)
    }
}
