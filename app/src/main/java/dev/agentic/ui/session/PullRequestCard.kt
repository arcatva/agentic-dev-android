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

// Collapse a PR description longer than this (lines OR chars) behind a Show-more toggle, so a long PR
// body can never bloat the card — the "again long and ugly" complaint was the whole message being
// crammed in; here the focused PR description still shows, but capped by default.
private const val PR_BODY_PREVIEW_LINES = 8
private const val PR_BODY_PREVIEW_CHARS = 400

/**
 * Renders a [PrNode] — a pull request the session created, detected and enriched (title + description +
 * state) by the backend and delivered as its own `kind:pr` frame — as a compact, cyan-accent card. One
 * card per PR; it stands on its own in the transcript, never merged into the assistant's prose.
 *
 * Layout: a tap-to-open header (PR title prominent, `owner/repo #number` as the overline, a merge +
 * open-in-new icon) over the PR description as markdown. The description is capped to a short preview by
 * default and reveals the rest with Show more. Cyan family ([AccentCyanContainer] /
 * [OnAccentCyanContainer]) matches the skill chips so PRs read as their own kind of event. The header is
 * non-selectable (a tap opens the PR instead of starting a transcript selection); the body stays selectable.
 */
@Composable
fun PrCard(node: PrNode, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val onContainer = OnAccentCyanContainer
    var expanded by remember(node.url) { mutableStateOf(false) }

    val bodyLines = node.body.lines()
    val isLong = bodyLines.size > PR_BODY_PREVIEW_LINES || node.body.length > PR_BODY_PREVIEW_CHARS
    // Collapsed preview is capped by BOTH lines and chars, so a body that is long only by character
    // count (e.g. one 500-char line) is actually shortened — otherwise "Show more" would show but
    // reveal nothing new.
    val shownBody = if (expanded || !isLong) node.body
        else bodyLines.take(PR_BODY_PREVIEW_LINES).joinToString("\n").take(PR_BODY_PREVIEW_CHARS).trimEnd()

    Card(
        colors = CardDefaults.cardColors(containerColor = AccentCyanContainer, contentColor = onContainer),
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            // Header — tap anywhere opens the PR. Padding is INSIDE the clickable so the press ripple
            // covers the whole row (clipped to the card shape), matching the other inline cards.
            DisableSelection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // runCatching: a device with no browser / restricted intent handling would
                        // otherwise crash on openUri (ActivityNotFoundException) — swallow it.
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
                // Description + toggle animate their height together on expand/collapse (the app's
                // spring spec, via animateContentHeight). While collapsed, the preview dissolves at the
                // bottom (fadingEdgeBottom) instead of ending on a hard cut, hinting there's more.
                Column(Modifier.animateContentHeight()) {
                    // Keep code blocks / tables in the card's cyan accent family instead of the neutral
                    // surface defaults — a light veil of the on-container color reads as an inset.
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
                        // A real M3 text button (ripple, shape, touch target) instead of a bare Row.
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

/** The card overline: `owner/repo #number`, plus the PR state when it isn't the default "open"
 *  (so a merged/closed PR reads clearly; freshly-created PRs are open and stay uncluttered). */
private fun prOverline(node: PrNode): String = buildString {
    append(node.repo.ifBlank { "pull request" })
    if (node.number > 0) append(" #${node.number}")
    if (node.state.isNotBlank() && !node.state.equals("open", ignoreCase = true)) {
        append(" · ${node.state.lowercase()}")
    }
}
