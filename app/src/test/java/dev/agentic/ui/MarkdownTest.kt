package dev.agentic.ui

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the markdown parser's behavior after the perf refactor that (a) pre-builds inline spans into
 * the [Block]s at parse time and (b) memoizes parsing process-wide by content string. These tests
 * guard that the prebuilt AnnotatedStrings carry the right plain text + spans and that identical
 * content is parsed once (returns the cached instance).
 */
class MarkdownTest {

    @Test
    fun parsesHeadingParagraphAndBullets() {
        val blocks = parseMarkdown("# Title\n\nhello **world**\n\n- a\n- b")
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is Block.Heading)
        assertEquals("Title", (blocks[0] as Block.Heading).text)
        assertTrue(blocks[1] is Block.Para)
        assertEquals("hello world", (blocks[1] as Block.Para).text.text) // inline bold stripped to plain text
        assertTrue(blocks[2] is Block.Bullet)
        assertEquals("a", (blocks[2] as Block.Bullet).text.text)
        assertTrue(blocks[3] is Block.Bullet)
        assertEquals("b", (blocks[3] as Block.Bullet).text.text)
    }

    @Test
    fun parsesFencedCodeBlockVerbatim() {
        val blocks = parseMarkdown("```\ncode **not bold** line\n```")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is Block.Code)
        // code is verbatim: ** is NOT interpreted inside a fence
        assertEquals("code **not bold** line", (blocks[0] as Block.Code).text)
    }

    @Test
    fun boldSpanIsPrebuiltAtParseTime() {
        val para = parseMarkdown("a **b** c").single() as Block.Para
        assertEquals("a b c", para.text.text)
        // exactly one span (the bold run), built once here — render never rebuilds it
        assertEquals(1, para.text.spanStyles.size)
        assertEquals(FontWeight.Bold, para.text.spanStyles[0].item.fontWeight)
        assertEquals("b", para.text.text.substring(para.text.spanStyles[0].start, para.text.spanStyles[0].end))
    }

    @Test
    fun parsesGfmTable() {
        val blocks = parseMarkdown("| a | b |\n|---|---|\n| 1 | 2 |")
        assertEquals(1, blocks.size)
        val table = blocks[0] as Block.Table
        assertEquals(listOf("a", "b"), table.header.map { it.text })
        assertEquals(1, table.rows.size)
        assertEquals(listOf("1", "2"), table.rows[0].map { it.text })
    }

    @Test
    fun memoizesByContentString() {
        val md = "MarkdownTest-memoization-probe: same **content** here"
        // identical input -> same cached parsed instance (parsed exactly once)
        assertSame(parseMarkdown(md), parseMarkdown(md))
    }
}
