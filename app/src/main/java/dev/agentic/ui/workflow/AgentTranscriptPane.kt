package dev.agentic.ui.workflow

import dev.agentic.ui.animateContentHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.agentic.ui.MarkdownText

/**
 * The two sections an agent transcript carries. The server ([readWorkflowAgent]) emits a single
 * markdown string of the form `**Task**\n\n<task>\n\n---\n\n**Output**\n\n<output>` (either section
 * may be absent). [splitAgentTranscript] turns that back into structured parts so the UI can show
 * the Output as the focus and tuck the Task away — instead of one undifferentiated wall of text.
 */
data class AgentTranscriptParts(val task: String?, val output: String?)

/** Markers the server uses; kept here so the parser and any test share one definition. */
private const val TASK_MARKER = "**Task**"
private const val OUTPUT_MARKER = "**Output**"
private const val SECTION_SEP = "\n\n---\n\n$OUTPUT_MARKER\n\n"

/**
 * Split the server's combined transcript markdown into its Task / Output parts. Deterministic and
 * pure (unit-tested). Falls back to treating an unrecognised blob as Output so nothing is ever lost.
 */
fun splitAgentTranscript(raw: String): AgentTranscriptParts {
    val s = raw.trim()
    if (s.isEmpty()) return AgentTranscriptParts(null, null)

    val sepIdx = s.indexOf(SECTION_SEP)
    if (sepIdx >= 0) {
        val task = s.substring(0, sepIdx).removePrefix(TASK_MARKER).trim()
        val output = s.substring(sepIdx + SECTION_SEP.length).trim()
        return AgentTranscriptParts(task.ifBlank { null }, output.ifBlank { null })
    }
    return when {
        s.startsWith(TASK_MARKER) -> AgentTranscriptParts(s.removePrefix(TASK_MARKER).trim().ifBlank { null }, null)
        s.startsWith(OUTPUT_MARKER) -> AgentTranscriptParts(null, s.removePrefix(OUTPUT_MARKER).trim().ifBlank { null })
        // Unknown shape (older/odd payload): show the whole thing as Output rather than drop it.
        else -> AgentTranscriptParts(null, s)
    }
}

/**
 * Pure-display composable for an agent's transcript.
 *
 * States:
 *  - [loading] = true (or [transcript] == null)  → centred [LoadingIndicator]
 *  - [transcript] is blank                        → "(no transcript captured)" placeholder
 *  - otherwise                                    → a collapsed **Task** disclosure (you open an
 *    agent to see what it produced, not to re-read its prompt) above the agent's **Output**.
 *
 * Stateless: no network/repo calls. The [WorkflowViewModel] owns the fetch.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentTranscriptPane(
    transcript: String?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
    ) {
        when {
            loading || transcript == null -> {
                Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                    LoadingIndicator()
                }
            }
            transcript.isBlank() -> {
                Text(
                    "(no transcript captured)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                val parts = remember(transcript) { splitAgentTranscript(transcript) }
                parts.task?.let { task ->
                    TaskDisclosure(task, transcriptKey = transcript)
                    Spacer(Modifier.height(if (parts.output != null) 14.dp else 0.dp))
                }
                parts.output?.let { output ->
                    SelectionContainer { MarkdownText(output) }
                }
            }
        }
    }
}

/**
 * Collapsed-by-default "Task" disclosure: a tap-to-expand header showing the prompt the agent ran.
 * Reset per agent via [transcriptKey] so switching agents starts collapsed again.
 */
@Composable
private fun TaskDisclosure(task: String, transcriptKey: String) {
    var expanded by rememberSaveable(transcriptKey) { mutableStateOf(false) }
    // Same inline-card style as the session transcript's tool/notes chips (Transcript.Collapsible):
    // a rounded Surface header — leading icon, label, trailing chevron — that expands to the prompt.
    Column(Modifier.animateContentHeight()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(
                    Icons.Rounded.Assignment,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Task",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "hide task" else "show task",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        // Expanded body — indented like the inline card's content block, kept selectable since it's
        // the agent's prompt (worth copying).
        if (expanded) Box(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp)) {
            SelectionContainer { MarkdownText(task) }
        }
    }
}
