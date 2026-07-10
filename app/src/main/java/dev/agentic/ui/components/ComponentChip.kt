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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agentic.ui.AccentBlue
import dev.agentic.ui.AccentCyan
import dev.agentic.ui.PluginPurple

/**
 * Shared chip for skill/plugin/mcp components on both New Request and Global Settings.
 *
 * "Selected = bright tag" look — a light bright-outlined chip, NOT a solid colour slab (a whole
 * wrapped row of ON chips would otherwise mush into a wall of colour).
 *   - ON (effective==true): a faint same-hue wash + BRIGHT accent label + a thin accent outline
 *     (cyan for skill, purple for plugin/mcp). Reads as a light bright tag.
 *   - OFF (effective==false): the default muted FilterChip — thin neutral outline, grey label,
 *     no fill ("关就是没颜色").
 *   - enabled==false (MCP in Global Settings): rendered non-interactive at 0.5 alpha.
 *
 * [kind] is "skill", "plugin", or "mcp".
 * [effective] is the EFFECTIVE on/off state (globalEnabled resolved through the current override).
 * [onClick] is called on tap; callers compute the new override from the effective toggle.
 * [readOnlyCaption] when non-null is shown as a small caption below the chip — used by Global
 * Settings to label MCP chips "managed per-session".
 * [onLongClick] when non-null, a long-press on the chip triggers this callback — used by Global
 * Settings to show a delete confirm dialog.
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
    // "Selected = bright tag", not a solid slab: a faint same-hue wash + BRIGHT accent text + a
    // thin accent outline. Cyan skills, blue repos, purple plugins/mcp. A whole row of ON chips then
    // reads as light bright-outlined tags rather than a wall of solid colour.
    val accent = when (kind) {
        "skill" -> AccentCyan
        "repo" -> AccentBlue
        else -> PluginPurple // plugin, mcp
    }

    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = accent.copy(alpha = 0.14f),
        selectedLabelColor = accent,
        selectedLeadingIconColor = accent,
    )
    val chipBorder = FilterChipDefaults.filterChipBorder(
        enabled = enabled,
        selected = effective,
        selectedBorderColor = accent.copy(alpha = 0.9f),
        selectedBorderWidth = 1.dp,
    )

    val accessDesc = buildString {
        append(label)
        append(if (effective) ": on" else ": off")
        if (!enabled) append(", read-only") else append(" (tap to toggle)")
        if (onLongClick != null && enabled) append("; long-press to delete")
    }

    Column(modifier = modifier.alpha(if (!enabled) 0.5f else 1f)) {
        // When onLongClick is provided and the chip is enabled, wrap in a Box with
        // combinedClickable so both tap and long-press work. The FilterChip's own onClick
        // is suppressed in that case (set to no-op) because combinedClickable owns click routing.
        if (onLongClick != null && enabled) {
            Box(
                modifier = Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            ) {
                FilterChip(
                    selected = effective,
                    onClick = { /* handled by combinedClickable above */ },
                    label = { Text(label) },
                    colors = chipColors,
                    border = chipBorder,
                    modifier = Modifier.semantics { contentDescription = accessDesc },
                )
            }
        } else {
            FilterChip(
                selected = effective,
                onClick = { if (enabled) onClick() },
                label = { Text(label) },
                colors = chipColors,
                border = chipBorder,
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
