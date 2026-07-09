package dev.agentic.ui.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentic.data.net.Session
import dev.agentic.ui.AccentCyan
import dev.agentic.ui.AccentViolet
import dev.agentic.ui.AccentVioletContainer
import dev.agentic.ui.OnAccentVioletContainer
import dev.agentic.ui.drawUltracodeRipple
import dev.agentic.ui.effortLabel
import dev.agentic.ui.fadingEdgeHorizontal
import dev.agentic.ui.modelLabel
import dev.agentic.ui.rememberUltracodeRipplePhase

/** What a session annotation chip stands for — drives the chip's icon, tint, and (for ULTRA) look. */
internal enum class TagKind { ULTRA, FORK, REPO, SKILL, MODEL, EFFORT }

/** A single annotation chip for a session. */
internal data class SessionTag(val label: String, val kind: TagKind)

/**
 * The annotation tags for a session, in display order: an ultracode marker (only when the session ran
 * in ultracode mode), then a "fork" marker (only when the session branched off a parent), then repos,
 * skills, and the model + effort it ran with. Repo/skill blanks and duplicates are dropped; model and
 * effort are shown with the SAME friendly labels as the New request screen's sliders (e.g. "Sonnet 4.6",
 * "High" — see [modelLabel]/[effortLabel]); a null/blank model or effort contributes no tag. The effort
 * tag is suppressed for ultracode sessions, where effort is locked to xhigh and the ultracode pill
 * already conveys it.
 *
 * Empty `repos` (a skill-only task) and empty `skills` ("load all", no curation) each contribute no tag.
 */
internal fun sessionTags(session: Session): List<SessionTag> {
    // The server normalizes legacy "ultra" to "ultracode" before serializing (store.ts normalizeMode),
    // so the client only ever sees "ultracode" or null — matching NewRequestScreen's own check.
    val isUltra = session.mode == "ultracode"
    val ultra = if (isUltra) listOf(SessionTag("ultracode", TagKind.ULTRA)) else emptyList()
    // A fork carries the id of the session it branched from; show it as a plain "fork" chip sitting with
    // the other run markers (the Forked-from card lower in the screen still links to the parent).
    // Blank-safe to match SessionViewModel.parentPreview's isNullOrBlank() gate — an empty parent id must
    // not show a "fork" chip with no matching Forked-from card.
    val fork = if (!session.parentSessionId.isNullOrBlank()) listOf(SessionTag("Fork", TagKind.FORK)) else emptyList()
    val repos = session.repos.filter { it.isNotBlank() }.distinct().map { SessionTag(it, TagKind.REPO) }
    val skills = session.skills.filter { it.isNotBlank() }.distinct().map { SessionTag(it, TagKind.SKILL) }
    val model = session.model?.takeIf { it.isNotBlank() }
        ?.let { SessionTag(modelLabel(it), TagKind.MODEL) }
    val effort = session.effort?.takeIf { it.isNotBlank() && !isUltra }
        ?.let { SessionTag(effortLabel(it), TagKind.EFFORT) }
    return ultra + fork + repos + skills + listOfNotNull(model, effort)
}

/**
 * Fork/repo/skill/model/effort annotation chips shown under the conversation title, plus an animated
 * "ultracode" pill for an ultracode session. The outlined chips each have their own leading icon and
 * tint — fork (branch, primary), repo (folder, neutral), skill (✦, primary), model (🤖, tertiary),
 * effort (⚡, tertiary); ultracode is the blue→violet [UltracodeChip] with a looping ripple highlight.
 *
 * The chips sit on a SINGLE line that scrolls horizontally and fades at whichever edge has more
 * off-screen content (rather than wrapping to a second row). The outlined chips are display-only:
 * disabled (no ripple / button role) and wrapped in a provider that drops the 48dp interactive touch
 * target each would otherwise reserve, so the row stays compact.
 */
@Composable
internal fun SessionTagRow(
    session: Session,
    modifier: Modifier = Modifier,
    onOpenParent: (String) -> Unit = {},
) {
    val tags = remember(session) { sessionTags(session) }
    if (tags.isEmpty()) return
    // The fork chip is tappable and opens the session it branched off — same destination the old
    // standalone "Forked from …" chip had. Gated identically to the fork tag (non-blank parent id).
    val onForkClick: (() -> Unit)? = remember(session.parentSessionId, onOpenParent) {
        session.parentSessionId?.takeIf { it.isNotBlank() }?.let { pid -> { onOpenParent(pid) } }
    }
    val scroll = rememberScrollState()
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(
            modifier
                .fillMaxWidth()
                .fadingEdgeHorizontal(
                    fadeStart = { scroll.value > 0 },
                    fadeEnd = { scroll.value < scroll.maxValue },
                )
                .horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tags.forEach { tag ->
                SessionTagChip(tag, onClick = if (tag.kind == TagKind.FORK) onForkClick else null)
            }
        }
    }
}

@Composable
private fun SessionTagChip(tag: SessionTag, onClick: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    when (tag.kind) {
        TagKind.ULTRA -> UltracodeChip()
        TagKind.FORK -> DisplayChip(tag.label, Icons.Rounded.CallSplit, scheme.primary, "fork", onClick)
        TagKind.REPO -> DisplayChip(tag.label, Icons.Rounded.FolderOpen, scheme.onSurfaceVariant, "repo")
        TagKind.SKILL -> DisplayChip(tag.label, Icons.Rounded.AutoAwesome, AccentCyan, "skill")
        TagKind.MODEL -> DisplayChip(tag.label, Icons.Rounded.SmartToy, scheme.tertiary, "model")
        TagKind.EFFORT -> DisplayChip(tag.label, Icons.Rounded.Bolt, scheme.tertiary, "effort")
    }
}

// Without an [onClick] the chip is a pure label: `enabled = false` means no ripple and it isn't
// announced as a button, while the overridden colours + the normal (enabled) border keep it looking
// like a regular AssistChip. Pass an [onClick] (e.g. the fork chip) to make it a real, tappable chip;
// the enabled/disabled label+icon colours are both pinned to `accent` so the look doesn't change.
@Composable
internal fun DisplayChip(
    label: String,
    icon: ImageVector?,
    accent: Color,
    contentDescription: String,
    onClick: (() -> Unit)? = null,
) {
    AssistChip(
        onClick = onClick ?: {},
        enabled = onClick != null,
        label = {
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingIcon = if (icon == null) null else {
            { Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(AssistChipDefaults.IconSize)) }
        },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = accent,
            leadingIconContentColor = accent,
            disabledLabelColor = accent,
            disabledLeadingIconContentColor = accent,
        ),
        border = AssistChipDefaults.assistChipBorder(enabled = true),
    )
}

// Ultracode pill in the workflow accent violet — the dark container + light content the workflow
// inline cards use — with a bright-violet highlight that ripples out from the centre to the edges,
// forever.
@Composable
internal fun UltracodeChip(modifier: Modifier = Modifier) {
    // Ripple clock + drawing are shared with the Effort slider — see [dev.agentic.ui.UltracodeRipple] —
    // so the pill and the slider animate identically.
    val phase = rememberUltracodeRipplePhase()
    Row(
        modifier
            .height(32.dp)
            .clip(MaterialTheme.shapes.small)
            .drawBehind {
                // Dark violet container, then the brighter violet ripple bloom on top of it. Reading
                // phase.value here (draw phase) keeps frames to a redraw, not a recomposition.
                drawRect(AccentVioletContainer)
                drawUltracodeRipple(phase.value, color = AccentViolet)
            }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Label already says "Ultracode", so the icon is decorative (no TalkBack stutter).
        Icon(
            Icons.Rounded.Whatshot,
            contentDescription = null,
            tint = OnAccentVioletContainer,
            modifier = Modifier.size(AssistChipDefaults.IconSize),
        )
        Text("Ultracode", style = MaterialTheme.typography.labelSmall, color = OnAccentVioletContainer, maxLines = 1)
    }
}
