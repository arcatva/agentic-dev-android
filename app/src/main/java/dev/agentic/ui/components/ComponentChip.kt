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
 * "Lit tag" look — the restored pre-redesign pairing (dark same-hue container + BRIGHT accent
 * label) that read well; the interim "faint wash + thin outline" tag was visually thin:
 *   - ON (effective==true): dark container fill + bright same-hue label. Skill / plugin / mcp all
 *     use the theme CYAN family (one hue for every toggleable component kind); repos use the BLUE
 *     family so the repo row stays distinguishable.
 *   - OFF (effective==false): the default muted FilterChip — thin neutral outline, grey label,
 *     no fill ("关就是没颜色").
 *   - enabled==false: rendered non-interactive at 0.5 alpha.
 *
 * [kind] is "repo", "skill", "plugin", or "mcp".
 * [effective] is the EFFECTIVE on/off state (globalEnabled resolved through the current override).
 * [onClick] is called on tap; callers compute the new override from the effective toggle.
 * [readOnlyCaption] when non-null is shown as a small caption below the chip — used by Settings
 * to label MCP chips "managed per-session".
 * [onLongClick] when non-null, a long-press on the chip triggers this callback — used by Settings
 * to show a delete confirm dialog.
 *
 * Input routing note: material3 chips have no long-press support, and NESTING the chip inside a
 * combinedClickable container does NOT work — the chip's own internal clickable consumes every
 * tap first (with its onClick stubbed out, tapping was a dead zone: Settings chips could not be
 * toggled at all). Instead a transparent overlay Box sits ON TOP of the chip and owns both
 * gestures; the chip below never receives pointer input.
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
    // Dark container + BRIGHT same-hue label (the readable "lit" pairing). One cyan family for
    // skill/plugin/mcp per the app theme; blue for repos.
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
                    // Never reached — the overlay below occludes the chip's input node.
                    onClick = {},
                    label = { Text(label) },
                    colors = chipColors,
                    // The overlay carries the real semantics; clear the chip's own (stub) click
                    // node so TalkBack doesn't announce two conflicting targets.
                    modifier = Modifier.clearAndSetSemantics { },
                )
                // Transparent overlay drawn ON TOP of the chip: as the topmost sibling it wins
                // hit testing, so this single combinedClickable reliably owns BOTH tap (toggle)
                // and long-press (remove). Clipped to the chip shape so the ripple matches.
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
