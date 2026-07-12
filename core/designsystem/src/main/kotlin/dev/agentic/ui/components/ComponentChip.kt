package dev.agentic.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agentic.ui.AccentBlueContainer
import dev.agentic.ui.AccentCyanContainer
import dev.agentic.ui.OnAccentBlueContainer
import dev.agentic.ui.OnAccentCyanContainer

/**
 * Shared chip for repo/skill/plugin/mcp components on both New Request and Settings.
 *
 * "Lit tag" look — dark same-hue container + bright accent label. ON: skill/plugin/mcp use CYAN;
 * repos use BLUE (so the repo row stays distinguishable). OFF: default muted FilterChip. enabled=false:
 * 0.5 alpha. [kind] in {"repo","skill","plugin","mcp"}.
 *
 * Input routing note (KEY INVARIANT): material3 chips have no long-press support, and NESTING the
 * chip inside a combinedClickable container does NOT work — the chip's own internal clickable consumes
 * every tap first. Instead, a transparent overlay Box sits ON TOP of the chip and owns both gestures;
 * the chip below never receives pointer input.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ComponentChip(
    label: String,
    kind: String,
    effective: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnlyCaption: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val (containerColor, labelColor) = if (kind == "repo") {
        AccentBlueContainer to OnAccentBlueContainer
    } else {
        AccentCyanContainer to OnAccentCyanContainer // skill, plugin, mcp
    }

    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = containerColor,
        selectedLabelColor = labelColor,
        selectedLeadingIconColor = labelColor,
    )

    val accessDesc = buildString {
        append(label)
        append(if (effective) ": on" else ": off")
        if (!enabled) append(", read-only") else append(" (tap to toggle)")
        if (onLongClick != null && enabled) append("; long-press to delete")
    }

    Column(modifier = modifier.alpha(if (!enabled) 0.5f else 1f)) {
        if (onLongClick != null && enabled) {
            Box {
                FilterChip(
                    selected = effective,
                    // Never reached — overlay below occludes the chip's input node.
                    onClick = {},
                    label = { Text(label) },
                    colors = chipColors,
                    // Clear the chip's own (stub) click semantics so TalkBack doesn't announce two conflicting targets.
                    modifier = Modifier.clearAndSetSemantics { },
                )
                // Overlay drawn ON TOP of the chip: as the topmost sibling it wins hit testing, so
                // this single combinedClickable owns tap (toggle) AND long-press (remove).
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(FilterChipDefaults.shape)
                        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                        .semantics { contentDescription = accessDesc },
                )
            }
        } else {
            FilterChip(
                selected = effective,
                onClick = { if (enabled) onClick() },
                label = { Text(label) },
                colors = chipColors,
                modifier = Modifier.semantics { contentDescription = accessDesc },
            )
        }
        if (readOnlyCaption != null) {
            Text(
                text = readOnlyCaption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}
