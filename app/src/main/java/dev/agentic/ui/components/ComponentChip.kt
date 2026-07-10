package dev.agentic.ui.components

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
import dev.agentic.ui.PluginPurple
import dev.agentic.ui.PluginPurpleContainer
import dev.agentic.ui.SkillGreen
import dev.agentic.ui.SkillGreenContainer

/**
 * Shared chip for skill/plugin/mcp components on both New Request and Global Settings.
 *
 * Restores the original (pre-S5) "lit" filter-chip look that read well: a tonal fill with a DARK
 * container and BRIGHT same-hue text — NOT a muddy dark-on-dark slab.
 *   - ON (effective==true): filled with the kind's dark container + bright accent label
 *     (green for skill, purple for plugin/mcp). The brightness lives in the TEXT, so a row of lit
 *     chips reads as bright tags, not a solid colour block.
 *   - OFF (effective==false): the default muted FilterChip — thin neutral outline, grey label,
 *     no fill ("关就是没颜色").
 *   - enabled==false (MCP in Global Settings): rendered non-interactive at 0.5 alpha.
 *
 * [kind] is "skill", "plugin", or "mcp".
 * [effective] is the EFFECTIVE on/off state (globalEnabled resolved through the current override).
 * [onClick] is called on tap; callers compute the new override from the effective toggle.
 * [readOnlyCaption] when non-null is shown as a small caption below the chip — used by Global
 * Settings to label MCP chips "managed per-session".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentChip(
    label: String,
    kind: String,
    effective: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnlyCaption: String? = null,
) {
    // Dark container + BRIGHT same-hue text (the readable pairing the original used). Green for
    // skills, purple for plugins/mcp.
    val (containerColor, labelColor) = if (kind == "skill") {
        SkillGreenContainer to SkillGreen
    } else {
        PluginPurpleContainer to PluginPurple
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
    }

    Column(modifier = modifier.alpha(if (!enabled) 0.5f else 1f)) {
        FilterChip(
            selected = effective,
            onClick = { if (enabled) onClick() },
            label = { Text(label) },
            colors = chipColors,
            modifier = Modifier.semantics { contentDescription = accessDesc },
        )
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
