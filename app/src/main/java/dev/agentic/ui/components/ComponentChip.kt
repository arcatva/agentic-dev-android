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
import dev.agentic.ui.OnPluginPurple
import dev.agentic.ui.OnSkillGreen
import dev.agentic.ui.PluginPurpleContainer
import dev.agentic.ui.SkillGreenContainer

/**
 * Shared chip for skill/plugin/mcp components on both New Request and Global Settings.
 *
 * Visual contract:
 *   - ON (effective==true): filled with kind color (green for skill, purple for plugin/mcp).
 *   - OFF (effective==false): outlined/unselected default FilterChip look (no fill).
 *   - enabled==false (MCP in Global Settings): chip rendered non-interactive + 0.5 alpha.
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
    val (containerColor, contentColor) = when (kind) {
        "skill" -> SkillGreenContainer to OnSkillGreen
        else    -> PluginPurpleContainer to OnPluginPurple   // plugin or mcp
    }

    val chipColors = if (effective) {
        FilterChipDefaults.filterChipColors(
            selectedContainerColor = containerColor,
            selectedLabelColor = contentColor,
            selectedLeadingIconColor = contentColor,
        )
    } else {
        FilterChipDefaults.filterChipColors()
    }

    val accessDesc = buildString {
        append(label)
        append(if (effective) ": on" else ": off")
        if (!enabled) append(", read-only") else append(" (tap to toggle)")
    }

    val alphaVal = if (!enabled) 0.5f else 1f

    Column(modifier = modifier.alpha(alphaVal)) {
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
