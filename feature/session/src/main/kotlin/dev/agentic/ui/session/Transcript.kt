package dev.agentic.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentic.domain.AnswerNode
import dev.agentic.domain.AskNode
import dev.agentic.domain.AskQuestion
import dev.agentic.domain.AttachmentNode
import dev.agentic.domain.DownloadUi
import dev.agentic.domain.formatBytesPerSec
import dev.agentic.domain.Node
import dev.agentic.domain.agentChipLabel
import dev.agentic.domain.PermNode
import dev.agentic.domain.PlanNode
import dev.agentic.domain.PrNode
import dev.agentic.domain.PromptNode
import dev.agentic.domain.RetryNode
import dev.agentic.domain.SkillNode
import dev.agentic.domain.skillChipLabel
import dev.agentic.domain.SpawnNode
import dev.agentic.domain.TextNode
import dev.agentic.domain.ThinkingNode
import dev.agentic.domain.ToolGroupNode
import dev.agentic.domain.ToolNode
import dev.agentic.domain.WorkflowNode
import dev.agentic.domain.workflowChipLabel
import dev.agentic.ui.AccentViolet
import dev.agentic.ui.AccentCyanContainer
import dev.agentic.ui.AccentVioletContainer
import dev.agentic.ui.OnAccentCyanContainer
import dev.agentic.ui.OnAccentVioletContainer
import dev.agentic.ui.FadingText
import dev.agentic.ui.MarkdownText
import dev.agentic.ui.components.AppTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Cards ahead of the oldest loaded node at which scroll-back prefetch fires (early = seamless). */
private const val LOAD_EARLIER_PREFETCH = 8

/**
 * Chat transcript: a [LazyColumn] in `reverseLayout` mode where index 0 is the bottom/newest node, so
 * opening lands at the bottom for free. Keys are the append-only natural index and autoscroll glues to
 * the newest node only when the user is already at the bottom. [nodes] arrives already
 * grouped/interleaved/answer-marked, so this composable is a pure renderer with no transcript logic.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Transcript(
    nodes: List<Node>,
    onAnswerAsk: (AskNode, String) -> Unit,
    onRespondPermission: (id: String, decision: String, feedback: String?) -> Unit,
    onDownloadAttachment: (AttachmentNode) -> Unit,
    downloadProgress: Map<String, DownloadUi>,
    canAnswer: Boolean,
    onOpenWorkflow: (WorkflowNode) -> Unit = {},
    loadImageBytes: suspend (AttachmentNode) -> ByteArray? = { null },
    /** Long-press a prompt bubble → rewind to just before that turn (0-based); default no-op. */
    onRewind: (turnIndex: Int) -> Unit = {},
    hasMore: Boolean = false,
    loadingEarlier: Boolean = false,
    onLoadEarlier: () -> Unit = {},
    /** Bounded-window (ended sessions): newer pages evicted off the newest end, paged back in on scroll down. */
    hasNewer: Boolean = false,
    loadingNewer: Boolean = false,
    onLoadNewer: () -> Unit = {},
    latestEventId: Long = 0,
    firstEventLine: Long = 0,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // The image attachment (if any) the user tapped to open in the fullscreen zoomable preview.
    var previewImage by remember { mutableStateOf<AttachmentNode?>(null) }
    // 1-based ordinal of each SpawnNode for a clean "#N" chip label; 0 = not a spawn. Re-keyed off a
    // cheap structural signature so it isn't recomputed O(n) on every streamed token.
    val spawnSig = nodes.size to nodes.count { it is SpawnNode }
    val spawnOrdinals = remember(spawnSig) {
        var n = 0
        IntArray(nodes.size) { idx -> if (nodes[idx] is SpawnNode) ++n else 0 }
    }
    // In reverse layout, firstVisibleItemIndex == 0 means the newest (bottom) item is showing.
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    // Also key on the trailing node's text length so autoscroll re-fires on each streamed token, not
    // only when a new node is appended.
    val trailingTextLen = when (val last = nodes.lastOrNull()) {
        is TextNode -> last.text.length
        is ThinkingNode -> last.text.length
        else -> 0
    }
    // First settle (session open) is instant (no overscroll rebound); later streaming updates animate.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(nodes.size, trailingTextLen) {
        if (atBottom) {
            if (settled) {
                listState.animateScrollToItem(0)
            } else {
                listState.scrollToItem(0)
                settled = true
            }
        }
    }

    // Infinite scroll-back: auto-fetch older history as the user scrolls up. Emitting the COUNT of
    // not-yet-visible older items (not a bool) through distinctUntilChanged is the key invariant — a
    // coalesced page still changes the count so it keeps filling, a failed page leaves it unchanged so
    // no storm-retry, and a live append at the newest end keeps it invariant so no spurious fetch.
    val hasMoreState = rememberUpdatedState(hasMore)
    val onLoadEarlierState = rememberUpdatedState(onLoadEarlier)
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val itemsAboveTop = if (lastVisible < 0) Int.MAX_VALUE else info.totalItemsCount - 1 - lastVisible
            if (hasMoreState.value && itemsAboveTop < LOAD_EARLIER_PREFETCH) itemsAboveTop else -1
        }
            .distinctUntilChanged()
            .filter { it >= 0 }
            .collect { onLoadEarlierState.value() }
    }

    // Symmetric bottom trigger: page newer history back in on scroll down (count is firstVisibleItemIndex).
    // Only active for an ended, windowed session (hasNewer); inert for live sessions.
    val hasNewerState = rememberUpdatedState(hasNewer)
    val onLoadNewerState = rememberUpdatedState(onLoadNewer)
    LaunchedEffect(listState) {
        snapshotFlow {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1
            if (hasNewerState.value && first in 0 until LOAD_EARLIER_PREFETCH) first else -1
        }
            .distinctUntilChanged()
            .filter { it >= 0 }
            .collect { onLoadNewerState.value() }
    }

    // Content-based keys (see [transcriptNodeKeys]) stay stable across a window reseed, so Compose
    // re-anchors the viewport on its own. Recomputed only on a structural change, not per token.
    val nodeKeys = remember(nodes.size, nodes.firstOrNull()?.let { nodeFingerprint(it) }) { transcriptNodeKeys(nodes) }

    // User-message snap targets for the fast-scroll slider, keyed on a cheap signature so they
    // recompute only when the transcript or PromptNode count grows.
    val anchorSig = nodes.size to nodes.count { it is PromptNode }
    val anchors = remember(anchorSig) { userMessageAnchors(nodes) }
    // 0-based turn index of each PromptNode (-1 for non-prompts), to map a long-pressed bubble to a rewind.
    val promptTurns = remember(anchorSig) { promptTurnIndices(nodes) }
    Box(modifier) {
        SelectionContainer {
        // While a page of older history is in flight, pin a small spinner at the top.
        if (loadingEarlier) {
            Row(
                modifier = Modifier.fillMaxWidth().zIndex(1f).padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Newest-first so reverse-index 0 sits at the bottom. Completed nodes get a content-based
            // key so an interleaved node mid-list doesn't shift earlier keys; the live trailing node
            // (revI==0) keeps a position-based key because its content mutates in-place as tokens stream.
            items(
                count = nodes.size,
                key = { revI ->
                    if (revI == 0) "live:0"  // trailing (newest) node — position-stable key
                    else nodeKeys[nodes.size - 1 - revI]
                },
                // contentType groups the lazy-reuse pool by card SHAPE so a scrapped slot is only
                // rebound to a node rendering the same composable tree (else Compose tears down + rebuilds).
                contentType = { revI -> nodeContentType(nodes[nodes.size - 1 - revI]) },
            ) { revI ->
                val i = nodes.size - 1 - revI
                when (val node = nodes[i]) {
                    is PromptNode -> if (node.text.isNotBlank()) {
                        val turn = promptTurns.getOrElse(i) { -1 }
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.large,
                            // Long-press → rewind to just before this turn. combinedClickable + inner
                            // DisableSelection makes the long-press fire inside the SelectionContainer.
                            modifier = Modifier.fillMaxWidth().combinedClickable(
                                onClick = {},
                                onLongClick = if (turn >= 0) ({ onRewind(turn) }) else null,
                            ),
                        ) {
                            DisableSelection {
                                Text(
                                    node.text,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }

                    // Assistant prose and the turn-end answer both render in an expanded "Text" card.
                    is TextNode -> if (node.text.isNotBlank()) Collapsible(
                        Icons.Rounded.Description, "Text",
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        expandedDefault = true,
                    ) { MarkdownText(node.text) }

                    is AnswerNode -> if (node.text.isNotBlank()) Collapsible(
                        Icons.Rounded.Description, "Text",
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        expandedDefault = true,
                    ) { MarkdownText(node.text) }

                    // A pull request the session created — backend-detected, its own compact card.
                    is PrNode -> PrCard(node)

                    is ThinkingNode -> StepCard(node)
                    is ToolNode -> StepCard(node)

                    // A run of consecutive same-name tool calls: one chip "name · N+" that expands.
                    is ToolGroupNode -> Collapsible(
                        Icons.Rounded.Code,
                        "${node.name.replaceFirstChar { it.uppercase() }} · ${node.items.size}+",
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            node.items.forEach { StepCard(it) }
                        }
                    }

                    is SkillNode -> EventChip(
                        Icons.Rounded.AutoAwesome,
                        skillChipLabel(node),
                        AccentCyanContainer,
                        OnAccentCyanContainer,
                    )

                    is SpawnNode -> SpawnCard(
                        label = agentChipLabel(node, spawnOrdinals.getOrNull(i)?.takeIf { it > 0 }),
                        result = node.result,
                        children = node.children,
                        onOpenWorkflow = onOpenWorkflow,
                    )

                    is WorkflowNode -> EventChip(
                        Icons.Rounded.AccountTree,
                        workflowChipLabel(node),
                        AccentVioletContainer,
                        OnAccentVioletContainer,
                        onClick = { onOpenWorkflow(node) },
                    )

                    // Transient API-retry notice; consecutive retries collapse into the latest attempt.
                    is RetryNode -> EventChip(
                        Icons.Rounded.Refresh,
                        "Retrying… ${node.attempt}/${node.maxRetries}" +
                            if (node.category.isNotBlank()) " · ${node.category}" else "",
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer,
                    )

                    is AskNode -> {
                        // Fold answered and superseded asks; only the trailing run of unanswered asks
                        // stays expanded. `i+1` onward are the chronologically-later nodes.
                        val collapsed =
                            node.answered || nodes.drop(i + 1).any { it !is AskNode }
                        AskCardView(
                            node = node,
                            collapsedDefault = collapsed,
                            canAnswer = canAnswer,
                            onAnswer = { answerText -> onAnswerAsk(node, answerText) },
                        )
                    }

                    is PermNode -> PermCardView(
                        node = node,
                        canAnswer = canAnswer && !node.decided,
                        onRespond = { decision, feedback -> onRespondPermission(node.id, decision, feedback) },
                    )

                    is PlanNode -> PlanCardView(
                        node = node,
                        canAnswer = canAnswer && !node.decided,
                        onRespond = { decision, feedback -> onRespondPermission(node.id, decision, feedback) },
                    )

                    is AttachmentNode -> AttachmentCard(
                        node,
                        onPrimary = { if (isImageFile(node.path)) previewImage = node else onDownloadAttachment(node) },
                        onDownload = { onDownloadAttachment(node) },
                        progress = downloadProgress[node.path],
                        loadImageBytes = loadImageBytes,
                    )
                }
            }
        }
        }
        MessageFastScrollbar(
            state = listState,
            anchors = anchors,
            modifier = Modifier.matchParentSize(),
        )
        // Newer-history load spinner (ended-session bounded window), pinned to the visual bottom.
        if (loadingNewer) {
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).zIndex(1f).padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
    // Tapping an image attachment opens a fullscreen, pinch-to-zoom preview (no download needed).
    previewImage?.let { node ->
        ImagePreviewDialog(
            node = node,
            loadImageBytes = loadImageBytes,
            onDownload = { onDownloadAttachment(node) },
            onDismiss = { previewImage = null },
        )
    }
}

// ── Stable key helpers ────────────────────────────────────────────────────────────────────────────

/** 0-based turn index of each PromptNode (its ordinal among prompts), -1 for non-prompt nodes. */
internal fun promptTurnIndices(nodes: List<Node>): IntArray {
    var n = -1
    return IntArray(nodes.size) { idx -> if (nodes[idx] is PromptNode) ++n else -1 }
}

/** Content fingerprint for a node — type tag + short content signature, with NO list position, so a
 *  node's key is unchanged when pages are evicted/prepended and Compose re-anchors the viewport itself. */
private fun nodeFingerprint(node: Node): String = when (node) {
    is PromptNode     -> "p:${node.text.take(40)}"
    is TextNode       -> "t:${node.text.take(40)}"
    is AnswerNode     -> "a:${node.text.take(40)}"
    is ThinkingNode   -> "th:${node.text.take(40)}"
    is ToolNode       -> "tool:${node.name}:${node.summary.take(30)}"
    is ToolGroupNode  -> "tg:${node.name}:${node.items.size}"
    is SkillNode      -> "sk:${node.name}"
    is SpawnNode      -> "sp:${node.type}:${node.desc.take(20)}"
    is WorkflowNode   -> "wf:${node.name}:${node.id.take(8)}"
    is RetryNode      -> "retry:${node.attempt}"
    is AskNode        -> "ask:${node.questions.size}:${node.questions.firstOrNull()?.text?.take(40).orEmpty()}"
    is PermNode       -> "perm:${node.id}:${node.decided}"
    is PlanNode       -> "plan:${node.id}:${node.decided}"
    is AttachmentNode -> "att:${node.path}"
    is PrNode         -> "pr:${node.repo}:${node.number}"
}

/** Per-node LazyColumn keys: [nodeFingerprint] plus a running per-fingerprint occurrence index, which
 *  keeps keys unique (Compose crashes on duplicates) while stable under windowing. */
internal fun transcriptNodeKeys(nodes: List<Node>): List<String> {
    val seen = HashMap<String, Int>()
    return nodes.map { node ->
        val fp = nodeFingerprint(node)
        val occ = seen.getOrDefault(fp, 0)
        seen[fp] = occ + 1
        "$fp#$occ"
    }
}

/** LazyColumn reuse-pool tag grouped by the composable a node renders, so structurally-identical cards
 *  pool together (Tool/Thinking→StepCard, Skill/Workflow/Retry→EventChip, Text/Answer→MarkdownText). */
private fun nodeContentType(node: Node): String = when (node) {
    is PromptNode                              -> "prompt"
    is TextNode, is AnswerNode                 -> "md"
    is ThinkingNode, is ToolNode              -> "step"
    is ToolGroupNode                           -> "group"
    is SkillNode, is WorkflowNode, is RetryNode -> "chip"
    is SpawnNode                               -> "spawn"
    is AskNode                                 -> "ask"
    is PermNode                                -> "perm"
    is PlanNode                                -> "plan"
    is AttachmentNode                          -> "attach"
    is PrNode                                  -> "pr"
}

// ── Per-node card composables (ported from old SessionScreen.kt 892-1026, made private) ──────────

// Accent pairs (from Theme.kt): skill = cyan; agent + workflow = violet; retry = error red; tool = neutral.

/** Full-width inline marker for a skill / spawned agent / workflow / retry call. */
@Composable
private fun EventChip(
    icon: ImageVector,
    label: String,
    container: Color,
    onColor: Color,
    onClick: (() -> Unit)? = null,
) {
    // Markers, not copyable prose — disable selection so a long-press taps instead of selecting text.
    DisableSelection {
        Surface(
            color = container,
            shape = MaterialTheme.shapes.small,
            // fillMaxWidth before clip+clickable so the row spans full width and the ripple is clipped to shape.
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(enabled = onClick != null) { onClick?.invoke() },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(icon, null, tint = onColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                // weight(1f) + FadingText: label fills the row on one line and fades if it overflows.
                FadingText(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = onColor,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** A collapsible chip (tool call / thinking / notes / step group) that expands to show [content],
 *  defaulting to [expandedDefault] and re-syncing when it changes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Collapsible(
    icon: ImageVector,
    label: String,
    container: Color,
    onColor: Color,
    expandedDefault: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(expandedDefault) }
    LaunchedEffect(expandedDefault) { expanded = expandedDefault }
    // animateContentHeight (not AnimatedVisibility, which reserves layout height when hidden) so a
    // collapsed chip takes only the header's height. Whole card is non-selectable — see EventChip.
    DisableSelection {
        Column {
            Surface(
                color = container,
                shape = MaterialTheme.shapes.small,
                // Clip before clickable so the indication follows the rounded chip, not a rectangle.
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { expanded = !expanded },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(icon, null, tint = onColor, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    FadingText(label, style = MaterialTheme.typography.labelMedium, color = onColor, modifier = Modifier.weight(1f))
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        null, tint = onColor, modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (expanded) Box(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp)) { content() }
        }
    }
}

/**
 * A spawned-subagent inline card. Renders the same full-width violet card whether or not the result
 * has arrived, so it grows the result in (animateContentHeight) rather than jumping from chip to card.
 */
@Composable
private fun SpawnCard(
    label: String,
    result: String?,
    children: List<Node> = emptyList(),
    onOpenWorkflow: (WorkflowNode) -> Unit = {},
) {
    // Expandable once the agent has anything to reveal: its nested steps and/or its returned result.
    val hasBody = result != null || children.isNotEmpty()
    var expanded by remember { mutableStateOf(false) }
    DisableSelection {
        Column {
            Surface(
                color = AccentVioletContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(enabled = hasBody) { expanded = !expanded },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Rounded.Hub, null, tint = OnAccentVioletContainer, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    FadingText(label, style = MaterialTheme.typography.labelMedium, color = OnAccentVioletContainer, modifier = Modifier.weight(1f))
                    // Collapsed step-count hint.
                    if (children.isNotEmpty()) {
                        Text(
                            "${children.size} step${if (children.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnAccentVioletContainer.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (hasBody) {
                        Icon(
                            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            null, tint = OnAccentVioletContainer, modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (hasBody && expanded) {
                // Subagent's events inset behind a violet rule so they read as belonging to this agent;
                // its returned result sits last, after the steps that produced it.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 4.dp)
                        .drawBehind {
                            drawLine(
                                color = AccentViolet,
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = 2.dp.toPx(),
                            )
                        }
                        .padding(start = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    children.forEach { AgentChildCard(it, onOpenWorkflow) }
                    result?.let { MarkdownText(it) }
                }
            }
        }
    }
}

/** Renders one of a subagent's nested events, reusing the top-level card composables (skill/workflow/
 *  agent children fall back to their inline chips; a nested agent recurses). */
@Composable
private fun AgentChildCard(n: Node, onOpenWorkflow: (WorkflowNode) -> Unit) {
    when (n) {
        is SkillNode -> EventChip(Icons.Rounded.AutoAwesome, skillChipLabel(n), AccentCyanContainer, OnAccentCyanContainer)
        is SpawnNode -> SpawnCard(agentChipLabel(n), n.result, n.children, onOpenWorkflow)
        // Keep the nested workflow chip tappable — a subagent can invoke the Workflow tool.
        is WorkflowNode -> EventChip(Icons.Rounded.AccountTree, workflowChipLabel(n), AccentVioletContainer, OnAccentVioletContainer, onClick = { onOpenWorkflow(n) })
        else -> StepCard(n)  // ToolNode / ThinkingNode / TextNode (no-ops for anything else)
    }
}

/** One ordinary card — a tool call or thinking/assistant text. Also the expanded contents of a [ToolGroupNode]. */
@Composable
private fun StepCard(n: Node) {
    when (n) {
        is ToolNode -> Collapsible(
            Icons.Rounded.Code,
            n.name.replaceFirstChar { it.uppercase() } + (if (n.summary.isNotBlank()) " · ${n.summary}" else ""),
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                n.detail.ifBlank { n.summary.ifBlank { "(no details)" } },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is ThinkingNode -> if (n.text.isNotBlank()) Collapsible(
            Icons.Rounded.Notes, "Thinking",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
        ) { MarkdownText(n.text) }

        is TextNode -> if (n.text.isNotBlank()) MarkdownText(n.text)

        else -> {}
    }
}

/**
 * Renders ONE AskUserQuestion as a single card holding all its questions, answered together and
 * submitted once so an answer can't bleed across questions. Answered cards show the submitted text;
 * when [canAnswer] is false the inputs are disabled.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AskCardView(
    node: AskNode,
    collapsedDefault: Boolean,
    canAnswer: Boolean,
    onAnswer: (String) -> Unit,
) {
    val answered = node.answered
    var expanded by remember(node.answered, collapsedDefault) { mutableStateOf(!collapsedDefault) }
    // Grey out Submit the instant it's tapped: a double-tap would otherwise POST two follow-up turns
    // that the transcript's prompt-dedup can't merge. Keyed to `node` so it resets on rollback.
    var submitting by remember(node) { mutableStateOf(false) }
    // Per-question state, keyed to this node so it resets on node change and never leaks across questions.
    val picks = remember(node) {
        mutableStateMapOf<Int, SnapshotStateList<String>>().apply {
            node.questions.indices.forEach { put(it, mutableStateListOf()) }
        }
    }
    val other = remember(node) {
        mutableStateMapOf<Int, String>().apply { node.questions.indices.forEach { put(it, "") } }
    }
    fun answerFor(q: Int): String {
        val o = (other[q] ?: "").trim()
        return if (o.isNotEmpty()) o else (picks[q] ?: emptyList()).joinToString(", ")
    }
    val allAnswered = node.questions.isNotEmpty() && node.questions.indices.all { answerFor(it).isNotBlank() }
    val title = (node.questions.firstOrNull()?.text ?: "").ifBlank { "Question" }

    // Whole card non-selectable (like EventChip/Collapsible), else a press selects the title text
    // instead of toggling inside the transcript's SelectionContainer.
    DisableSelection {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // Header — tap to fold/unfold; clickable is full-bleed so the ripple covers the whole card.
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    title + if (node.questions.size > 1) " (+${node.questions.size - 1} more)" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (answered) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!expanded) Text(
                    if (answered) "answered" else "unanswered",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (answered) {
                    Text(
                        "Answered: ${node.answer}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    node.questions.forEachIndexed { q, question ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (node.questions.size > 1 && question.text.isNotBlank()) Text(
                                question.text,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (question.multiSelect) Text(
                                "choose any",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (question.options.isNotEmpty()) FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                question.options.forEach { opt ->
                                    val isSel = opt in (picks[q] ?: emptyList())
                                    FilterChip(
                                        selected = isSel,
                                        enabled = canAnswer,
                                        onClick = {
                                            val s = picks.getValue(q)
                                            if (question.multiSelect) { if (isSel) s.remove(opt) else s.add(opt) }
                                            else { s.clear(); s.add(opt) }
                                            other[q] = ""
                                        },
                                        label = { Text(opt) },
                                    )
                                }
                            }
                            AppTextField(
                                value = other[q] ?: "",
                                onValueChange = { other[q] = it; if (it.isNotBlank()) picks.getValue(q).clear() },
                                label = if (question.options.isEmpty()) "Your answer…" else "Other…",
                                singleLine = true,
                                enabled = canAnswer,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Button(
                        onClick = {
                            submitting = true
                            onAnswer(buildCombinedAnswer(node.questions) { answerFor(it) })
                        },
                        enabled = canAnswer && allAnswered && !submitting,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Submit")
                    }
                    if (!canAnswer) Text(
                        "This session can't take an answer right now.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    }
}

/** Combine per-question answers into one follow-up: single → the answer; multiple → "question: answer" lines. */
private fun buildCombinedAnswer(questions: List<AskQuestion>, answerFor: (Int) -> String): String =
    if (questions.size <= 1) answerFor(0)
    else questions.indices.joinToString("\n") { q ->
        "${questions[q].text.ifBlank { "Q${q + 1}" }}: ${answerFor(q)}"
    }

/**
 * Permission card: a tool the agent wants to run in `default`/`acceptEdits` mode. Shows the call
 * summary + Allow / Deny (Deny sends the optional reason as feedback); collapses to a status line once decided.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermCardView(
    node: PermNode,
    canAnswer: Boolean,
    onRespond: (decision: String, feedback: String?) -> Unit,
) {
    var reason by remember(node.id) { mutableStateOf("") }
    DisableSelection {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Code, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(
                        node.summary.ifBlank { node.tool },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (node.decided) {
                    Text(
                        if (node.decision == "allow") "Allowed" else "Denied",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AppTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = "Reason (sent on Deny)…",
                        singleLine = true,
                        enabled = canAnswer,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                        androidx.compose.material3.TextButton(
                            onClick = { onRespond("deny", reason.ifBlank { null }) },
                            enabled = canAnswer,
                        ) { Text("Deny") }
                        Button(
                            onClick = { onRespond("allow", null) },
                            enabled = canAnswer,
                        ) { Text("Allow") }
                    }
                    if (!canAnswer) Text(
                        "This session can't take a response right now.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Plan-approval card (plan mode): renders the proposed plan markdown with Approve & run / Keep planning.
 * Approve sends allow; Keep planning sends deny with feedback so Claude revises without leaving plan mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanCardView(
    node: PlanNode,
    canAnswer: Boolean,
    onRespond: (decision: String, feedback: String?) -> Unit,
) {
    var feedback by remember(node.id) { mutableStateOf("") }
    DisableSelection {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Plan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                // Plan body is selectable prose — wrap only the markdown back into a SelectionContainer.
                SelectionContainer { MarkdownText(node.plan) }
                if (node.decided) {
                    Text(
                        if (node.decision == "allow") "Approved — executing" else "Sent back for changes",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AppTextField(
                        value = feedback,
                        onValueChange = { feedback = it },
                        label = "Feedback (sent on Keep planning)…",
                        singleLine = false,
                        enabled = canAnswer,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                        androidx.compose.material3.TextButton(
                            onClick = { onRespond("deny", feedback.ifBlank { null }) },
                            enabled = canAnswer,
                        ) { Text("Keep planning") }
                        Button(
                            onClick = { onRespond("allow", null) },
                            enabled = canAnswer,
                        ) { Text("Approve & run") }
                    }
                    if (!canAnswer) Text(
                        "This session can't take a response right now.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Attachment card: per-type icon + filename, an inline thumbnail for images, and a download affordance.
 * Primary tap [onPrimary] previews images (else downloads); the trailing icon always [onDownload]s.
 */
@Composable
private fun AttachmentCard(
    node: AttachmentNode,
    onPrimary: () -> Unit,
    onDownload: () -> Unit,
    /** Non-null while this file is downloading — fraction + live speed + stall flag. */
    progress: DownloadUi?,
    loadImageBytes: suspend (AttachmentNode) -> ByteArray? = { null },
) {
    val downloading = progress != null
    val fraction = progress?.fraction
    val name = node.path.substringAfterLast('/')
    Surface(
        onClick = onPrimary,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    attachmentIcon(node.path), null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                FadingText(
                    name.ifBlank { node.path },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // Branch on `downloading` FIRST: once a download starts the tap-to-download icon goes
                // away so it can't be re-tapped. Show % when known; else the bar below is the cue.
                if (downloading) {
                    if (fraction != null) {
                        Text(
                            "${(fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    Icon(
                        Icons.Rounded.Download, "download",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onDownload).size(24.dp).padding(2.dp),
                    )
                }
            }
            // Inline image preview — fetch + decode (downsampled) off the main thread, no save.
            if (isImageFile(node.path)) {
                val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, node.path) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { loadImageBytes(node)?.let { decodeSampledImage(it, 1280)?.asImageBitmap() } }.getOrNull()
                    }
                }
                bmp?.let { img ->
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.Image(
                        bitmap = img,
                        contentDescription = name,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(MaterialTheme.shapes.medium),
                    )
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // Size unknown — indeterminate bar until bytes start arriving.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                // Live pace under the bar: a frozen bar reads as a hang; show the actual state instead.
                val bytesPerSec = progress.bytesPerSec
                val hint = when {
                    progress.stalled -> "Stalled — auto-resuming…"
                    bytesPerSec != null -> formatBytesPerSec(bytesPerSec)
                    else -> null
                }
                if (hint != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (progress.stalled) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A distinct MD3 icon per file type so attachments carry a unique indicator (image / apk / pdf / …). */
private fun attachmentIcon(path: String): ImageVector = when (path.substringAfterLast('.', "").lowercase()) {
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> Icons.Rounded.Image
    "apk", "aab" -> Icons.Rounded.Android
    "pdf" -> Icons.Rounded.PictureAsPdf
    "zip", "tar", "gz", "tgz", "rar", "7z", "jar" -> Icons.Rounded.FolderZip
    "mp4", "webm", "mov", "mkv", "avi" -> Icons.Rounded.Movie
    "mp3", "wav", "ogg", "m4a", "flac", "aac" -> Icons.Rounded.AudioFile
    "txt", "md", "log", "json", "csv", "yaml", "yml", "xml", "html" -> Icons.Rounded.Description
    "kt", "java", "js", "ts", "py", "go", "rs", "c", "cpp", "h", "sh", "rb", "swift" -> Icons.Rounded.Code
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

/** Whether a path is a raster image we can decode to an inline thumbnail. */
private fun isImageFile(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

/** Decode [bytes] downsampled so the larger side is <= [maxPx]; null if not a decodable image. */
private fun decodeSampledImage(bytes: ByteArray, maxPx: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    if (maxDim <= 0) return null
    var sample = 1
    while (maxDim / sample > maxPx) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

/** Fullscreen pinch-to-zoom + pan preview of an image attachment; tap the download icon to save it. */
@Composable
private fun ImagePreviewDialog(
    node: AttachmentNode,
    loadImageBytes: suspend (AttachmentNode) -> ByteArray?,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val name = node.path.substringAfterLast('/')
        val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, node.path) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { loadImageBytes(node)?.let { decodeSampledImage(it, 2560)?.asImageBitmap() } }.getOrNull()
            }
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f)),
            contentAlignment = Alignment.Center,
        ) {
            val img = bmp
            if (img != null) {
                var scale by remember { mutableStateOf(1f) }
                var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                androidx.compose.foundation.Image(
                    bitmap = img,
                    contentDescription = name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offset = if (scale > 1f) offset + pan else androidx.compose.ui.geometry.Offset.Zero
                            }
                        }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
            } else {
                CircularProgressIndicator(color = Color.White)
            }
            // Top bar over the image: filename + download + close.
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name, color = Color.White, maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = onDownload) { Icon(Icons.Rounded.Download, "download", tint = Color.White) }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "close", tint = Color.White) }
            }
        }
    }
}
