package dev.agentic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.ConcurrentHashMap

// A small, dependency-free Markdown renderer covering what the agent transcript uses: headings,
// bold, inline code, fenced code blocks, bullet/numbered lists, and paragraphs.
//
// PERF: the inline span work (bold/code -> AnnotatedString) is done ONCE here at parse time and
// stored in the Block, so a recomposition / scroll-in just hands the prebuilt AnnotatedString to
// Text — it never re-runs inline(). Parsing itself is memoized process-wide by content string (see
// [parseMarkdown]) so the SAME message text is parsed only once even as its LazyColumn slot is
// recycled across scrolls. The regexes are top-level vals (compiled once), not rebuilt per line.
internal sealed interface Block {
    data class Heading(val text: String) : Block
    data class Code(val text: String) : Block
    data class Bullet(val text: AnnotatedString) : Block
    data class Para(val text: AnnotatedString) : Block
    data class Table(val header: List<AnnotatedString>, val rows: List<List<AnnotatedString>>) : Block
}

// Compiled once. Was previously `Regex(...)` constructed inside the per-line parse loop (twice per
// matching line) — regex compilation on the scroll hot path. Hoisted to top-level vals.
private val BULLET_REGEX = Regex("^\\s*([-*]|\\d+\\.)\\s+")

private val EMPTY_ANNOTATED = AnnotatedString("")

private fun cells(line: String): List<String> =
    line.trim().trim('|').split("|").map { it.trim() }

// A GFM separator row: every cell is dashes with optional leading/trailing colons (e.g. |---|:--:|).
private fun isTableSeparator(line: String): Boolean {
    val c = cells(line)
    return c.isNotEmpty() && c.all { it.isNotEmpty() && it.all { ch -> ch == '-' || ch == ':' } }
}

private fun parseBlocks(md: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = md.split("\n")
    var i = 0
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out.add(Block.Para(inline(para.toString().trim()))); para.clear() }
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                flush(); i++
                val code = StringBuilder()
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.appendLine(lines[i]); i++ }
                out.add(Block.Code(code.toString().trimEnd())); i++
            }
            line.trimStart().startsWith("#") -> { flush(); out.add(Block.Heading(line.trimStart().trimStart('#').trim())); i++ }
            // table: a "| … |" header line immediately followed by a |---|---| separator row
            line.trimStart().startsWith("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1]) -> {
                flush()
                val header = cells(line).map { inline(it) }; i += 2
                val rows = mutableListOf<List<AnnotatedString>>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows.add(cells(lines[i]).map { inline(it) }); i++ }
                out.add(Block.Table(header, rows))
            }
            BULLET_REGEX.containsMatchIn(line) -> {
                flush(); out.add(Block.Bullet(inline(line.replaceFirst(BULLET_REGEX, "")))); i++
            }
            line.isBlank() -> { flush(); i++ }
            else -> { para.appendLine(line); i++ }
        }
    }
    flush()
    return out
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end >= 0) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }; i = end + 2 } else { append(s[i]); i++ }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end >= 0) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) { append(s.substring(i + 1, end)) }; i = end + 1 } else { append(s[i]); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}

// Process-wide memoization of parsed markdown, keyed by the raw content string. Committed transcript
// nodes have stable text, so the same string is parsed exactly once and reused as its LazyColumn slot
// is recycled across scrolls (the per-composition remember(md) below only caches within one slot).
// Bounded so streaming (each token = a new trailing string) can't grow it without limit; computeIfAbsent
// keeps it atomic. The live trailing node still re-parses per token, but that is one node, not the list.
private const val CACHE_MAX = 256
private const val CACHE_TRIM_TO = 128
private val blockCache = ConcurrentHashMap<String, List<Block>>()

internal fun parseMarkdown(md: String): List<Block> {
    if (blockCache.size > CACHE_MAX) {
        // Arbitrary eviction (ConcurrentHashMap has no order) — just cap the size; an evicted, still-
        // visible message simply re-parses once on its next scroll-in. Fine for the very oldest.
        blockCache.keys.take(blockCache.size - CACHE_TRIM_TO).forEach { blockCache.remove(it) }
    }
    return blockCache.computeIfAbsent(md) { parseBlocks(md) }
}

/**
 * Renders the transcript's markdown subset. Body text (headings, bullets, paragraphs, inline code) is
 * NOT given an explicit color — it inherits `LocalContentColor.current`, so a caller that sets a content
 * color (e.g. a Card with `contentColor`, like [dev.agentic.ui.session.PrCard]) tints the prose
 * for free. The few blocks that need their own background — fenced code and tables — take their surface
 * colors as parameters ([codeBackground] / [tableBackground] / [tableHeaderBackground] / [tableDivider])
 * so they can stay in the caller's color family instead of reverting to the neutral defaults; omit them
 * for the standalone transcript look.
 */
@Composable
fun MarkdownText(
    md: String,
    modifier: Modifier = Modifier,
    codeBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    tableBackground: Color = MaterialTheme.colorScheme.surface,
    tableHeaderBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    tableDivider: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    val blocks = remember(md) { parseMarkdown(md) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Heading -> Text(b.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                is Block.Code -> Surface(color = codeBackground, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                }
                is Block.Bullet -> Row { Text("•  "); Text(b.text, style = MaterialTheme.typography.bodyMedium) }
                is Block.Para -> Text(b.text, style = MaterialTheme.typography.bodyMedium)
                is Block.Table -> TableBlock(b, tableBackground, tableHeaderBackground, tableDivider)
            }
        }
    }
}

@Composable
private fun TableBlock(t: Block.Table, surface: Color, headerBackground: Color, divider: Color) {
    Surface(color = surface, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().background(headerBackground)) {
                t.header.forEach { c ->
                    Text(c, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp))
                }
            }
            t.rows.forEach { row ->
                HorizontalDivider(color = divider)
                Row(Modifier.fillMaxWidth()) {
                    // pad the row out to the header width so cells stay aligned
                    val n = t.header.size.coerceAtLeast(row.size)
                    for (j in 0 until n) {
                        Text(row.getOrElse(j) { EMPTY_ANNOTATED }, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}
