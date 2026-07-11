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

/** How many cards ahead of the OLDEST loaded node the scroll-back prefetch fires. Loading a few
 *  items early (rather than at the very edge) means the next page is stitched in before the user
 *  reaches the top, so scroll-back feels continuous instead of hitting a wall. */
private const val LOAD_EARLIER_PREFETCH = 8

/**
 * The chat transcript: a [LazyColumn] in `reverseLayout` mode. This is the chat-rendering best
 * practice that replaces the old screen's pin/alpha/scrollToItem machinery (old SessionScreen.kt
 * 651-754):
 *
 * - **reverseLayout = true**: index 0 is the BOTTOM (the newest node). Opening lands at the bottom
 *   for free — there is NO `pinnedOnce` flag, NO `alpha` gate hiding the list until a pin lands, and
 *   NO multi-frame `scrollToItem(..., Int.MAX_VALUE)` loop guessing off-screen item heights. The
 *   LazyColumn simply starts at offset 0 (the newest item).
 * - **stable keys = natural index** (`nodes.size - 1 - revI`): the transcript is append-only, so a
 *   node's natural (chronological) index never changes as the list grows — only NEW indices appear at
 *   the high end (= reverse-index 0, the bottom). Stable keys let the LazyColumn keep item state
 *   (expanded chips, scroll position) correct across recompositions while text streams in.
 * - **autoscroll only when already at bottom**: `atBottom` = `firstVisibleItemIndex == 0` (in reverse
 *   layout, index 0 is the newest/bottom item). When new content arrives we glue to the newest node
 *   ONLY if the user is already there; if they scrolled up to read, they stay put.
 *
 * The list is wrapped in a [SelectionContainer] so transcript text is selectable, matching the old
 * screen. All per-node card looks are ported from old SessionScreen.kt (892-1026) into the private
 * composables below.
 *
 * Note: [nodes] arrives already grouped/interleaved/answer-marked — the repo's TranscriptReducer
 * applies `groupTools ∘ interleaveShared ∘ markAnsweredAsks` and the VM adds optimistic overlays, so
 * this composable is a pure renderer with no transcript logic.
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
    /** Long-press a user prompt bubble → rewind the code to just before that turn (0-based index).
     *  Default no-op (e.g. the wide layout doesn't wire it yet). */
    onRewind: (turnIndex: Int) -> Unit = {},
    hasMore: Boolean = false,
    loadingEarlier: Boolean = false,
    onLoadEarlier: () -> Unit = {},
    /** Bounded-window (ended sessions): newer pages were evicted off the newest end and can be paged back
     *  in as the user scrolls DOWN. Default off, so live sessions and the wide layout are unaffected. */
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
    // 1-based ordinal of each SpawnNode among all spawned-agent cards, so the chip can show a clean
    // "#N" instead of the opaque tool_use id. Indexed by chronological position; 0 = not a spawn.
    // Re-key off a cheap structural signature instead of the whole `nodes` instance: the transcript is
    // append-only, so this array only changes when the node count or the number of SpawnNodes changes
    // (a trailing TextNode/ThinkingNode growing a token at a time keeps both — and so the cached array).
    // Without this it recomputed O(n) on every streamed token, since `nodes` is a fresh list each frame.
    val spawnSig = nodes.size to nodes.count { it is SpawnNode }
    val spawnOrdinals = remember(spawnSig) {
        var n = 0
        IntArray(nodes.size) { idx -> if (nodes[idx] is SpawnNode) ++n else 0 }
    }
    // In reverse layout, firstVisibleItemIndex == 0 means the newest (bottom) item is showing.
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    // Keep glued to the newest node as the transcript grows — but only when the user is already at
    // the bottom, so we never yank them down while they scroll up to read.
    // ui-1: also key on the trailing node's text length so the effect re-fires on each token
    // streamed into the final TextNode or ThinkingNode, not only when a new node is appended.
    val trailingTextLen = when (val last = nodes.lastOrNull()) {
        is TextNode -> last.text.length
        is ThinkingNode -> last.text.length
        else -> 0
    }
    // First settle (session open / initial load) is instant — no scroll velocity → no overscroll/
    // rebound. Once settled, streaming updates animate smoothly (animateScrollToItem), so the chat
    // glides down token-by-token instead of snapping. The flag resets when the Transcript recreates
    // (new session via key(realVm.sessionId) in SessionContent).
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

    // Infinite scroll-back (WeChat/Telegram-style): auto-fetch older history as the user scrolls up,
    // no "load more" button. The trigger is the number of not-yet-visible OLDER items above the
    // viewport: in reverseLayout the oldest node sits at the highest index, so that count is
    // `totalItemsCount - 1 - lastVisible`. We fire while it's below LOAD_EARLIER_PREFETCH (prefetch a
    // few cards early) and hasMore. Emitting the COUNT (not a bool) through distinctUntilChanged is
    // what makes this robust:
    //  - a coalesced page that adds < PREFETCH items still CHANGES the count, so it re-fires and keeps
    //    filling instead of stalling with the user stranded at the top (a bool would suppress true->true);
    //  - a FAILED page leaves the count unchanged -> suppressed -> no storm-retry;
    //  - a live append at the NEWEST end shifts lastVisible and total together -> count invariant ->
    //    no spurious fetch while a turn streams in.
    // hasMore is read INSIDE snapshotFlow so a false->true flip after the initial load re-evaluates;
    // the -1 sentinel (also emitted pre-layout, when nothing is measured yet) is filtered out.
    // loadEarlier is idempotent in the repo as a second guard.
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

    // Symmetric bottom trigger: page NEWER history back in (content the bounded window evicted off the
    // newest end) as the user scrolls DOWN. In reverseLayout the newest node is index 0, so the count of
    // not-yet-visible newer items BELOW the viewport is firstVisibleItemIndex — same count-through-
    // distinctUntilChanged robustness as the top. Only active for an ended, windowed session (hasNewer);
    // live sessions never set it, so this is inert there.
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

    // Content-based LazyColumn keys (see [transcriptNodeKeys]): stable across a sliding-window reseed for
    // distinct-content nodes, so Compose re-anchors the viewport and preserves item state on its own — no
    // manual scroll compensation. Recomputed only on a STRUCTURAL change (count / oldest node), not on
    // every streamed token (the live trailing node keeps its own "live:0" key below).
    val nodeKeys = remember(nodes.size, nodes.firstOrNull()?.let { nodeFingerprint(it) }) { transcriptNodeKeys(nodes) }

    // User-message snap targets for the fast-scroll slider, recomputed only when the transcript grows.
    // Keyed on a cheap signature, not the fresh-each-frame `nodes` instance: anchors are derived from
    // PromptNode positions/previews and each anchor's fraction from nodes.size — all of which change
    // only when the node count or PromptNode count changes (PromptNode text is fixed at creation, never
    // streamed into), so this signature shifts exactly when the anchor list would.
    val anchorSig = nodes.size to nodes.count { it is PromptNode }
    val anchors = remember(anchorSig) { userMessageAnchors(nodes) }
    // 0-based turn index of each PromptNode (its position among prompts), -1 for non-prompts. Used to
    // map a long-pressed prompt bubble to the rewind turn index. Same cheap-signature memo pattern.
    val promptTurns = remember(anchorSig) { promptTurnIndices(nodes) }
    Box(modifier) {
        SelectionContainer {
        // Scroll-back is automatic (see the infinite-scroll effect above) — no button. While a page
        // of older history is in flight, pin a small spinner at the top as the only affordance.
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
            // Newest-first so reverse-index 0 sits at the bottom.
            // ui-3: derive a stable key from node type+content fingerprint for all completed
            // (non-live) nodes so that an interleaved AttachmentNode mid-list doesn't shift
            // the keys of earlier items and lose tool/step expand state.
            // The live trailing node (revI==0) keeps a position-based key because its content
            // mutates in-place as tokens stream in (a content-based key would thrash on every token).
            items(
                count = nodes.size,
                key = { revI ->
                    if (revI == 0) "live:0"  // trailing (newest) node — position-stable key
                    else nodeKeys[nodes.size - 1 - revI]
                },
                // contentType groups the lazy-reuse pool by card SHAPE so a scrapped slot is only
                // handed to a node that renders the same composable tree (Compose can only rebind a
                // reused slot when contentType matches; with the default null type it would hand a
                // text-card slot to a tool-chip and tear the whole subtree down + rebuild). Nodes that
                // share a renderer share a tag (ToolNode/ThinkingNode -> StepCard "step";
                // Skill/Workflow/Retry -> EventChip "chip"; Text/Answer -> MarkdownText "md";
                // PrNode -> PrCard "pr").
                contentType = { revI -> nodeContentType(nodes[nodes.size - 1 - revI]) },
            ) { revI ->
                val i = nodes.size - 1 - revI
                when (val node = nodes[i]) {
                    is PromptNode -> if (node.text.isNotBlank()) {
                        val turn = promptTurns.getOrElse(i) { -1 }
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.large,
                            // Long-press → rewind the code to just before this turn. combinedClickable
                            // (with the inner text in DisableSelection) makes the long-press fire
                            // reliably inside the transcript's SelectionContainer.
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

                    // Assistant prose (the streamed `text` block) and the turn-end answer both render in
                    // a "Text" card, EXPANDED by default (mirrors the collapsed Thinking card) — the
                    // markdown streams in token-by-token inside it. A PR the session opens arrives as its
                    // OWN `kind:pr` frame → PrNode below (a standalone card), so prose is never a PR card.
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

                    // A run of consecutive same-name tool calls: one chip "name · N+" that expands
                    // to the individual calls (e.g. "Edit · 3+" for three Edits in a row).
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

                    // Transient API-retry notice (errorContainer-toned). Consecutive retries are
                    // collapsed upstream into the latest attempt, so at most one shows per stall.
                    is RetryNode -> EventChip(
                        Icons.Rounded.Refresh,
                        "Retrying… ${node.attempt}/${node.maxRetries}" +
                            if (node.category.isNotBlank()) " · ${node.category}" else "",
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer,
                    )

                    is AskNode -> {
                        // Fold answered questions and superseded ones (anything the conversation has
                        // moved past). Only the current open question — the trailing run of unanswered
                        // asks — stays expanded. `i+1` onward are the chronologically-later nodes.
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
        // Newer-history load spinner (ended-session bounded window) — pinned to the visual bottom, the
        // mirror of the top scroll-back spinner. Only ever shows while loadNewer is in flight.
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

/**
 * For each node, the 0-based turn index of the PromptNode at that chronological position (its ordinal
 * among all PromptNodes), or -1 for non-prompt nodes. `nodes` is chronological, so the K-th prompt is
 * turn K — matching the backend's `count_user_turns` rewind index.
 */
internal fun promptTurnIndices(nodes: List<Node>): IntArray {
    var n = -1
    return IntArray(nodes.size) { idx -> if (nodes[idx] is PromptNode) ++n else -1 }
}

/**
 * Content fingerprint for a node — a type tag + short content signature, with NO list position. This is
 * what makes the bounded window slide smoothly: when pages are evicted/prepended/appended the visible
 * middle nodes' content is unchanged, so their key (below) is unchanged, so Compose re-anchors the
 * viewport and preserves item state (expanded chips) on its own — no manual scroll compensation. The old
 * key embedded the chronological index, which shifted on every prepend/evict and forced that compensation.
 */
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

/**
 * Per-node LazyColumn keys: each node's [nodeFingerprint] plus a running per-fingerprint occurrence index
 * (`fp#0`, `fp#1`, …). This keeps keys UNIQUE (Compose crashes on duplicates) while STABLE under windowing:
 * a distinct-content node is `fp#0` regardless of how many pages sit above or below it. Only a node that
 * shares its fingerprint with an EARLIER node shifts occurrence when that earlier twin is added/evicted —
 * rare (identical adjacent content), and the worst case is a one-item anchor nudge for that twin, never a
 * crash. A fully position-independent id for even identical twins would need a server-assigned per-node id.
 */
internal fun transcriptNodeKeys(nodes: List<Node>): List<String> {
    val seen = HashMap<String, Int>()
    return nodes.map { node ->
        val fp = nodeFingerprint(node)
        val occ = seen.getOrDefault(fp, 0)
        seen[fp] = occ + 1
        "$fp#$occ"
    }
}

/**
 * The LazyColumn reuse-pool tag for a node, grouped by the COMPOSABLE it renders (not 1:1 with the
 * node class) so structurally-identical cards pool together: ToolNode/ThinkingNode both render
 * StepCard; SkillNode/WorkflowNode/RetryNode all render EventChip; TextNode/AnswerNode both render
 * MarkdownText; PrNode renders its own PrCard. A reused slot is only rebound when its tag matches the
 * incoming node's tag, so a "md" slot is never handed to a "chip" (which would tear down + rebuild the
 * whole item subtree).
 */
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

// Accent pairs for inline event chips on the blue base (from Theme.kt): skill = CYAN
// (AccentCyanContainer / OnAccentCyanContainer); agent + workflow = VIOLET (AccentVioletContainer /
// OnAccentVioletContainer); retry keeps the semantic error red; tool chips stay neutral.

/** Full-width inline transcript marker for a skill / spawned agent / workflow / retry call. The row
 *  fills the transcript width so these markers line up with the other inline cards (Collapsible /
 *  SpawnCard / Ask / Perm / Plan) instead of sitting as ragged left-aligned chips. */
@Composable
private fun EventChip(
    icon: ImageVector,
    label: String,
    container: Color,
    onColor: Color,
    onClick: (() -> Unit)? = null,
) {
    // Inline cards are markers, not copyable prose — disable text selection so a long-press taps to
    // open/expand instead of being hijacked by the transcript's SelectionContainer.
    DisableSelection {
        Surface(
            color = container,
            shape = MaterialTheme.shapes.small,
            // fillMaxWidth before clip+clickable so the bar spans the transcript and the tap ripple
            // (clipped to the rounded shape) covers the whole row — same order as Collapsible/SpawnCard.
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
                // weight(1f) + FadingText: the label fills the remaining width on one line and fades at
                // the right edge if it overflows — exactly like the other full-width inline cards.
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

/** A collapsible chip (tool call / thinking / notes / step group) that expands to show [content].
 *  Defaults to [expandedDefault] and re-syncs whenever it changes; the user can still tap to toggle. */
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
    // animateContentHeight (height-only; not AnimatedVisibility, which reserves layout height even
    // when hidden) so a collapsed chip takes only the header's height — keeps inter-chip spacing tight
    // — and the card never grows short→long in width when the window is resized.
    // Whole card (header + expanded body) is non-selectable — see EventChip.
    DisableSelection {
        Column {
            Surface(
                color = container,
                shape = MaterialTheme.shapes.small,
                // Clip before clickable so the tap indication follows the rounded chip, not a rectangle.
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
 * A spawned-subagent inline card. Renders the SAME full-width violet card whether or not the agent's
 * result has arrived yet, so it does NOT jump from a tiny chip to a wide card when the result lands —
 * it just grows the result in (animateContentHeight). While the result is pending it is a plain,
 * non-expandable header; once the result arrives an expand chevron + body appear. (Previously a pending
 * agent was an [EventChip] and a finished one a [Collapsible] — two different composables, so the swap
 * was a visible jump.)
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
                    // Collapsed step-count hint so the agent advertises it has nested work to reveal.
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
                // The subagent's own events as secondary cards, inset behind a violet rule so they read
                // as belonging to THIS agent rather than the main conversation. The result (its final
                // returned text) sits last, after the steps that produced it.
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

/** Renders one of a subagent's nested events. Reuses the same card composables as the top level so a
 *  nested tool/text/thinking looks identical to its top-level counterpart; skill/workflow/agent
 *  children fall back to their inline chips (a nested agent recurses, though the model nests one level
 *  so its own children are empty). */
@Composable
private fun AgentChildCard(n: Node, onOpenWorkflow: (WorkflowNode) -> Unit) {
    when (n) {
        is SkillNode -> EventChip(Icons.Rounded.AutoAwesome, skillChipLabel(n), AccentCyanContainer, OnAccentCyanContainer)
        is SpawnNode -> SpawnCard(agentChipLabel(n), n.result, n.children, onOpenWorkflow)
        // Keep the nested workflow chip tappable, same as the top-level render — a subagent can invoke
        // the Workflow tool, and that nested chip must still open the run.
        is WorkflowNode -> EventChip(Icons.Rounded.AccountTree, workflowChipLabel(n), AccentVioletContainer, OnAccentVioletContainer, onClick = { onOpenWorkflow(n) })
        else -> StepCard(n)  // ToolNode / ThinkingNode / TextNode (no-ops for anything else)
    }
}

/** One ordinary card — a tool call or thinking/assistant text. Used both at the top level and as
 *  the expanded contents of a [ToolGroupNode], so a grouped run reveals exactly the same cards. */
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
 * Renders ONE AskUserQuestion as a single card holding ALL its questions, answered together and
 * submitted once — so an answer can never bleed across questions. Each question shows its option
 * chips (single- or multi-select per [AskQuestion.multiSelect]) plus a free-text field; Submit
 * combines the per-question answers and calls [onAnswer]. An answered card shows the submitted text;
 * when [canAnswer] is false the inputs are disabled. An informational 60s countdown (mirroring
 * Claude Code's answer window) shows while it is unanswered and answerable.
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
    // Grey the Submit button out the instant it is tapped. A fast double-tap (or a tap during a
    // recomposition hitch) would otherwise fire onAnswer twice, and each answer is POSTed as its own
    // follow-up turn stamped with a distinct server `at` — which the transcript's prompt-dedup keys on
    // and so cannot merge, making the answer render as two identical user bubbles. Keyed to `node` so it
    // resets if the optimistic answer is rolled back (send failed → card returns to unanswered → retry).
    var submitting by remember(node) { mutableStateOf(false) }
    // Per-question state, pre-populated and keyed to this node so it resets when the node changes
    // (e.g. an optimistic answer is rolled back) and never leaks across questions/recycled cards.
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

    // Whole card non-selectable, like the other inline cards (EventChip/Collapsible). The transcript is
    // wrapped in a SelectionContainer, so without this a press gets hijacked into selecting the title
    // text ("only the font highlights") instead of toggling — and the header text would be copyable.
    DisableSelection {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // Header — tap to fold/unfold. Clickable is full-bleed with its padding INSIDE it, so the
            // press ripple covers the whole card width (clipped to the card shape), not just the text.
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

/** Combine per-question answers into one follow-up message. Single question → just the answer;
 *  multiple → "question: answer" lines so the agent can map each answer to its question. */
private fun buildCombinedAnswer(questions: List<AskQuestion>, answerFor: (Int) -> String): String =
    if (questions.size <= 1) answerFor(0)
    else questions.indices.joinToString("\n") { q ->
        "${questions[q].text.ifBlank { "Q${q + 1}" }}: ${answerFor(q)}"
    }

/**
 * Permission card: a tool the agent wants to run in `default`/`acceptEdits` mode. Shows the tool call
 * summary + Allow / Deny, with an optional reason field that is sent as the deny feedback. Once decided
 * it collapses to a status line. Mirrors AskCardView's non-selectable, full-width card style.
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
 * Approve sends allow (the backend switches the session to `default`, so subsequent tools prompt);
 * Keep planning sends deny with the typed feedback so Claude revises without leaving plan mode.
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
 * Attachment card: a per-type icon + filename, an inline image thumbnail for images, and a tappable
 * download affordance. The card body's primary tap [onPrimary] opens a fullscreen preview for images
 * (else downloads); the trailing icon always [onDownload]s. All network work is routed through the VM
 * ([loadImageBytes] / the download event) per the stateless-screen rule.
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
                // Branch on `downloading` FIRST: once a download starts the tap-to-download icon goes away
                // entirely, so it can't be tapped again (the VM also guards, but this removes the affordance
                // and the flash-back the icon showed while the size was momentarily unknown). Show the %
                // when known; while it's not yet known the determinate/indeterminate bar below is the cue.
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
            // Inline image PREVIEW — fetch + decode (downsampled) without saving. Tap still downloads
            // the full file. The bytes load off the main thread; the thumbnail pops in when ready.
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
                // Live pace under the bar: a frozen bar with no words reads as a hang; "stalled —
                // auto-resuming" is what is actually happening (the downloader retries with Range).
                val hint = when {
                    progress.stalled -> "Stalled — auto-resuming…"
                    progress.bytesPerSec != null -> formatBytesPerSec(progress.bytesPerSec)
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

/** Decode [bytes] to a Bitmap downsampled so its larger side is <= [maxPx] (bounds memory for a
 *  thumbnail). Returns null if the bytes aren't a decodable image. */
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

/** Fullscreen, pinch-to-zoom + pan preview of an image attachment — fetched + decoded (at a larger
 *  resolution than the inline thumbnail) without saving. Tap the download icon to save the full file. */
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
