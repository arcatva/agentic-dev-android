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
import androidx.compose.material.icons.rounded.MoveToInbox
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

/** Chip kind — drives icon, tint, and (for ULTRA) animation. */
internal enum class TagKind { ULTRA, FORK, ADOPTED, REPO, SKILL, MODEL, EFFORT }

/** A single annotation chip for a session. */
internal data class SessionTag(val label: String, val kind: TagKind)

/** Annotation tags in display order. Effort suppressed on ultracode (xhigh is implied); model/effort use the slider-friendly labels. */
internal fun sessionTags(session: Session): List<SessionTag> {
    val isUltra = session.mode == "ultracode"
    val ultra = if (isUltra) listOf(SessionTag("ultracode", TagKind.ULTRA)) else emptyList()
    // Blank parent id must not show a "fork" chip with no matching Forked-from card.
    val fork = if (!session.parentSessionId.isNullOrBlank()) listOf(SessionTag("Fork", TagKind.FORK)) else emptyList()
    val adopted = if (session.origin == "adopted") listOf(SessionTag("Adopted", TagKind.ADOPTED)) else emptyList()
    val repos = session.repos.filter { it.isNotBlank() }.distinct().map { SessionTag(it, TagKind.REPO) }
    val skills = session.skills.filter { it.isNotBlank() }.distinct().map { SessionTag(it, TagKind.SKILL) }
    val model = session.model?.takeIf { it.isNotBlank() }
        ?.let { SessionTag(modelLabel(it), TagKind.MODEL) }
    val effort = session.effort?.takeIf { it.isNotBlank() && !isUltra }
        ?.let { SessionTag(effortLabel(it), TagKind.EFFORT) }
    return ultra + fork + adopted + repos + skills + listOfNotNull(model, effort)
}

/** Annotation chip row under the title (scrolls horizontally with edge fades). Outlined chips are display-only with the 48dp touch target dropped to stay compact. */
@Composable
internal fun SessionTagRow(
    session: Session,
    modifier: Modifier = Modifier,
    onOpenParent: (String) -> Unit = {},
) {
    val tags = remember(session) { sessionTags(session) }
    if (tags.isEmpty()) return
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
        TagKind.ADOPTED -> DisplayChip(tag.label, Icons.Rounded.MoveToInbox, scheme.primary, "adopted")
        TagKind.REPO -> DisplayChip(tag.label, Icons.Rounded.FolderOpen, scheme.onSurfaceVariant, "repo")
        TagKind.SKILL -> DisplayChip(tag.label, Icons.Rounded.AutoAwesome, AccentCyan, "skill")
        TagKind.MODEL -> DisplayChip(tag.label, Icons.Rounded.SmartToy, scheme.tertiary, "model")
        TagKind.EFFORT -> DisplayChip(tag.label, Icons.Rounded.Bolt, scheme.tertiary, "effort")
    }
}

/** `enabled=false` keeps the chip border + look of a normal AssistChip without ripple/role; pass [onClick] to make it tappable. */
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

/** Ultracode pill: dark violet container + bright ripple bloom (shared with the Effort slider). */
@Composable
internal fun UltracodeChip(modifier: Modifier = Modifier) {
    val phase = rememberUltracodeRipplePhase()
    Row(
        modifier
            .height(32.dp)
            .clip(MaterialTheme.shapes.small)
            .drawBehind {
                // Reading phase.value in the draw phase keeps frames to a redraw, not a recomposition.
                drawRect(AccentVioletContainer)
                drawUltracodeRipple(phase.value, color = AccentViolet)
            }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Decorative icon — label already says "Ultracode" (no TalkBack stutter).
        Icon(
            Icons.Rounded.Whatshot,
            contentDescription = null,
            tint = OnAccentVioletContainer,
            modifier = Modifier.size(AssistChipDefaults.IconSize),
        )
        Text("Ultracode", style = MaterialTheme.typography.labelSmall, color = OnAccentVioletContainer, maxLines = 1)
    }
}
