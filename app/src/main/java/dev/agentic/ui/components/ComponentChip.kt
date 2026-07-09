package dev.agentic.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agentic.ui.PluginPurple
import dev.agentic.ui.SkillGreen

/**
 * Shared chip for skill/plugin/mcp components on both New Request and Global Settings.
 *
 * Visual contract — "selected = bright accent", NOT a solid colour fill:
 *   - ON (effective==true): outlined chip lit with the kind's BRIGHT accent — accent border,
 *     accent label, a leading check, and only a faint (~16% alpha) accent wash behind it.
 *     Green accent for skill; purple accent for plugin/mcp.
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
    // Bright, high-chroma accent used for the selected border + label + check.
    val accent = if (kind == "skill") SkillGreen else PluginPurple

    // Selected: a faint accent wash (not a solid slab) + bright accent label/icon.
    // Unselected: FilterChip defaults (transparent container, onSurfaceVariant label).
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = accent.copy(alpha = 0.16f),
        selectedLabelColor = accent,
        selectedLeadingIconColor = accent,
    )

    // Selected: 1.5dp bright accent outline. Unselected: default neutral outline.
    val chipBorder = FilterChipDefaults.filterChipBorder(
        enabled = enabled,
        selected = effective,
        selectedBorderColor = accent,
        selectedBorderWidth = 1.5.dp,
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
            leadingIcon = if (effective) {
                {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                }
            } else null,
            colors = chipColors,
            border = chipBorder,
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
