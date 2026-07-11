package dev.agentic.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentic.domain.PrNode
import dev.agentic.ui.AccentCyanContainer
import dev.agentic.ui.MarkdownText
import dev.agentic.ui.OnAccentCyanContainer
import dev.agentic.ui.animateContentHeight
import dev.agentic.ui.fadingEdgeBottom

// Cap collapsed PR description preview by lines AND chars so a one-line 500-char body is actually shortened.
private const val PR_BODY_PREVIEW_LINES = 8
private const val PR_BODY_PREVIEW_CHARS = 400

/** Compact cyan card for a [PrNode] delivered as a `kind:pr` frame; one card per PR. */
@Composable
fun PrCard(node: PrNode, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val onContainer = OnAccentCyanContainer
    var expanded by remember(node.url) { mutableStateOf(false) }

    val bodyLines = node.body.lines()
    val isLong = bodyLines.size > PR_BODY_PREVIEW_LINES || node.body.length > PR_BODY_PREVIEW_CHARS
    // Both line- and char-cap so a single very long line is actually shortened.
    val shownBody = if (expanded || !isLong) node.body
        else bodyLines.take(PR_BODY_PREVIEW_LINES).joinToString("\n").take(PR_BODY_PREVIEW_CHARS).trimEnd()

    Card(
        colors = CardDefaults.cardColors(containerColor = AccentCyanContainer, contentColor = onContainer),
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            // Header: padding is INSIDE the clickable so the ripple covers the whole row.
            DisableSelection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Swallow ActivityNotFoundException on devices with no browser.
                        .clickable { runCatching { uriHandler.openUri(node.url) } }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CallMerge, null, tint = onContainer, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            prOverline(node),
                            style = MaterialTheme.typography.labelMedium,
                            color = onContainer.copy(alpha = 0.7f),
                        )
                        Text(
                            node.title.ifBlank { "Pull request" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.OpenInNew, "Open pull request", tint = onContainer, modifier = Modifier.size(20.dp))
                }
            }
            if (node.body.isNotBlank()) {
                HorizontalDivider(color = onContainer.copy(alpha = 0.15f))
                Column(Modifier.animateContentHeight()) {
                    MarkdownText(
                        shownBody,
                        modifier = Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (isLong) 0.dp else 12.dp)
                            .then(if (isLong && !expanded) Modifier.fadingEdgeBottom() else Modifier),
                        codeBackground = onContainer.copy(alpha = 0.12f),
                        tableBackground = onContainer.copy(alpha = 0.06f),
                        tableHeaderBackground = onContainer.copy(alpha = 0.14f),
                        tableDivider = onContainer.copy(alpha = 0.22f),
                    )
                    if (isLong) {
                        DisableSelection {
                            TextButton(
                                onClick = { expanded = !expanded },
                                colors = ButtonDefaults.textButtonColors(contentColor = onContainer),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                            ) {
                                Text(
                                    if (expanded) "Show less" else "Show more",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    null, modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** PR overline: `owner/repo #number`, state appended only when not "open". */
private fun prOverline(node: PrNode): String = buildString {
    append(node.repo.ifBlank { "pull request" })
    if (node.number > 0) append(" #${node.number}")
    if (node.state.isNotBlank() && !node.state.equals("open", ignoreCase = true)) {
        append(" · ${node.state.lowercase()}")
    }
}
