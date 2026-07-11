package dev.agentic.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.data.net.DiffHunk
import dev.agentic.data.net.DiffLine
import dev.agentic.di.appContainer

// Line backgrounds + foregrounds, tuned for the dark theme (low-alpha fills so text stays readable).
private val AddBg = Color(0x3327AE60)
private val DelBg = Color(0x33E05656)
private val AddFg = Color(0xFF8FE3AD)
private val DelFg = Color(0xFFEDA3A3)

/** A flattened render item: hunk header OR single diff line. [text] is display-ready (tabs expanded),
 *  precomputed once when the diff is flattened. */
private sealed interface DiffRow {
    data class Header(val hunk: DiffHunk) : DiffRow
    data class Line(val line: DiffLine, val text: String) : DiffRow
}

/**
 * Full-screen line-level diff for ONE file. Stateless: state lives in [FileDiffViewModel], created
 * inline from the type-safe route args (id/repo/sha/path) via [SavedStateHandle] — same pattern as
 * [CommitGraphScreen].
 *
 * Renders each line monospace with a fixed gutter (old no | new no | +/-/space marker) + per-kind
 * background. Long lines wrap (gutter stays top-aligned); syntax highlighting intentionally deferred.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FileDiffScreen(
    onBack: () -> Unit,
    vm: FileDiffViewModel? = null,
) {
    val container = appContainer()
    val realVm = vm ?: viewModel(factory = viewModelFactory {
        initializer { FileDiffViewModel(container.filesRepo, createSavedStateHandle()) }
    })
    val s by realVm.uiState.collectAsStateWithLifecycle()

    val fileName = remember(s.path) { s.path.substringAfterLast('/').ifBlank { s.path } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "back")
                    }
                },
                title = {
                    Column {
                        Text(
                            fileName.ifBlank { "Diff" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val sub = listOfNotNull(
                            s.diff?.status?.takeIf { it.isNotBlank() },
                            s.sha.takeIf { it.isNotBlank() }?.let { if (it == "working") "working tree" else it.take(7) },
                        ).joinToString(" · ")
                        if (sub.isNotBlank()) {
                            Text(
                                sub,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            val diff = s.diff
            when {
                s.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
                s.error != null -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Failed to load diff: ${s.error}", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { realVm.load() }) { Text("Retry") }
                    }
                }
                diff == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("No diff.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                diff.binary -> CenteredNote("Binary file — no text diff to show.")
                diff.hunks.isEmpty() -> CenteredNote("No changes in this file.")
                else -> {
                    if (diff.truncated) TruncatedBanner()
                    // Flatten + pre-process (tab→spaces) ONCE per diff, not on every row recomposition.
                    val rows = remember(diff) {
                        diff.hunks.flatMap { h ->
                            buildList {
                                add(DiffRow.Header(h))
                                h.lines.forEach { add(DiffRow.Line(it, it.content.replace("\t", "    "))) }
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(rows) { _, row ->
                            when (row) {
                                is DiffRow.Header -> HunkHeaderRow(row.hunk)
                                is DiffRow.Line -> DiffLineRow(row.line, row.text)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TruncatedBanner() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Diff is large and was truncated — showing the first part only.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** "@@ -a,b +c,d @@ heading" separator between hunks. */
@Composable
private fun HunkHeaderRow(hunk: DiffHunk) {
    val range = "@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@"
    val label = if (hunk.header.isBlank()) range else "$range ${hunk.header}"
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private val GUTTER_NUM_WIDTH = 38.dp
private val GUTTER_MARK_WIDTH = 14.dp

/** One diff line: [old no][new no][marker] content, monospace, tinted by kind. Long lines wrap;
 *  gutter top-aligned so numbers sit at first visual row of a wrapped line. [text] is display-ready
 *  (tabs expanded by the flatten step). Individual vals (no Triple) so scrolling a large diff
 *  doesn't allocate per row. */
@Composable
private fun DiffLineRow(line: DiffLine, text: String) {
    val isAdd = line.kind == "add"
    val isDel = line.kind == "del"
    val bg = when { isAdd -> AddBg; isDel -> DelBg; else -> Color.Transparent }
    val fg = when { isAdd -> AddFg; isDel -> DelFg; else -> MaterialTheme.colorScheme.onSurface }
    val mark = when { isAdd -> "+"; isDel -> "-"; else -> " " }
    val numColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().background(bg).padding(vertical = 1.dp),
    ) {
        GutterNum(line.oldLine, numColor)
        GutterNum(line.newLine, numColor)
        Text(
            mark,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = fg,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(GUTTER_MARK_WIDTH),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = fg,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
    }
}

@Composable
private fun GutterNum(n: Int?, color: Color) {
    Text(
        n?.toString().orEmpty(),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(GUTTER_NUM_WIDTH).padding(end = 4.dp),
    )
}
