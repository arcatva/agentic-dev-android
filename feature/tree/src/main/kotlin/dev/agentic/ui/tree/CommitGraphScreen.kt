package dev.agentic.ui.tree

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.data.net.CommitFile
import dev.agentic.data.net.CommitNode
import dev.agentic.data.net.GitRef
import dev.agentic.data.net.RepoCommits
import dev.agentic.data.net.Uncommitted
import dev.agentic.di.appContainer
import dev.agentic.domain.GraphNodeKind
import dev.agentic.domain.GraphRow
import dev.agentic.domain.buildCommitGraph
import dev.agentic.domain.relativeAge

// Status colours for the changed-file sheet — desaturated so they don't overwhelm text.
private val AddFg = Color(0xFF7EC891)
private val DelFg = Color(0xFFE07070)

/** Normalise the backend status (full word or letter) to a single display letter. */
private fun statusLetter(status: String): String = when (status.lowercase()) {
    "added", "a" -> "A"
    "deleted", "d" -> "D"
    "renamed", "r" -> "R"
    "modified", "m" -> "M"
    "" -> ""
    else -> status.take(1).uppercase()
}

private const val WORKING_SHA = "working"

/**
 * Full-screen per-repo commit-graph view. Stateless: state lives in [CommitGraphViewModel].
 *
 *   TopAppBar (back + title)
 *   Loading spinner / error + Retry / per-repo sections
 *   Each repo: header + LazyColumn of rows (optional "Uncommitted changes" node, then commits
 *     newest-first). Each commit row draws a single-lane graph gutter (dot + line; session commits
 *     accent-coloured + "session" chip; 2-parent merges a small curve; complex merges a labelled dot).
 *   Tap row → ModalBottomSheet listing that commit's changed files.
 *
 * VM creation: [appContainer] is @Composable so resolved in body first, captured into non-composable
 * initializer. Nullable [vm] allows injection in tests/previews.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CommitGraphScreen(
    onBack: () -> Unit,
    live: Boolean = false,
    /** Open full-screen line-level diff for one (repo, sha, path). Default no-op for previews/tests. */
    onOpenDiff: (repo: String, sha: String, path: String) -> Unit = { _, _, _ -> },
    vm: CommitGraphViewModel? = null,
) {
    val container = appContainer()
    val realVm = vm ?: viewModel(factory = viewModelFactory {
        initializer {
            CommitGraphViewModel(container.filesRepo, createSavedStateHandle())
        }
    })
    val s by realVm.uiState.collectAsStateWithLifecycle()

    var showDiscard by remember { mutableStateOf(false) }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            icon = { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Discard all changes?") },
            text = {
                Text(
                    "This will run git checkout / git clean in every repo in this session's " +
                        "worktrees. The changes cannot be recovered.",
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = { realVm.discard(); showDiscard = false },
                ) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "back")
                    }
                },
                title = { Text("History") },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (live) LiveWorktreeBanner()
            // Precompute multi-lane graph layout once per repo set (cheap; ≤30 commits each).
            val graphsByRepo = remember(s.repos) {
                s.repos.associate { it.repo to buildCommitGraph(it.commits, it.uncommitted != null) }
            }
            when {
            s.loading -> Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                LoadingIndicator()
            }
            s.error != null -> Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Failed to load history: ${s.error}",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { realVm.load() }) { Text("Retry") }
                }
            }
            s.repos.isEmpty() -> Box(Modifier.padding(16.dp)) {
                Text("No commits.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                s.repos.forEach { repo ->
                    val rows = graphsByRepo[repo.repo].orEmpty()
                    val commitRows = rows.filter { it.kind == GraphNodeKind.Commit }
                    item(key = "repo-header:${repo.repo}") { RepoHeader(repo.repo) }
                    repo.uncommitted?.let { unc ->
                        val uncRow = rows.firstOrNull { it.kind == GraphNodeKind.Uncommitted }
                        item(key = "uncommitted:${repo.repo}") {
                            UncommittedRow(
                                unc = unc,
                                graphRow = uncRow,
                                onClick = {
                                    realVm.loadFilesFor(repo.repo, WORKING_SHA, "Uncommitted changes")
                                },
                                onDiscard = { showDiscard = true },
                            )
                        }
                    }
                    itemsIndexed(repo.commits, key = { _, c -> "${repo.repo}:${c.sha}" }) { idx, commit ->
                        CommitRow(
                            commit = commit,
                            graphRow = commitRows.getOrNull(idx),
                            onClick = { realVm.loadFilesFor(repo.repo, commit.sha, commit.shortSha) },
                        )
                    }
                    item(key = "repo-div:${repo.repo}") {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
        }

        // Changed-file detail sheet — open whenever the VM has a detail.
        val detail = s.detail
        if (detail != null) {
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = { realVm.closeDetail() },
                sheetState = sheetState,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                    Text(
                        detail.title.ifBlank { "Changed files" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        detail.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                            LoadingIndicator()
                        }
                        detail.error != null -> Text(
                            detail.error,
                            color = MaterialTheme.colorScheme.error,
                        )
                        detail.files.isEmpty() -> Text(
                            "No changed files.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            detail.files.forEach { file ->
                                CommitFileRow(
                                    file = file,
                                    // Close the changed-files sheet BEFORE opening the diff. The sheet's
                                    // open state (detail) lives in the VM, scoped to this History back-stack
                                    // entry and SURVIVES navigating to the diff. If left set, popping back
                                    // recomposes this screen with detail still non-null, so the sheet
                                    // re-animates open over the commit list ("返回出现 ui bug"). `detail`
                                    // here is a captured local, so reading .repo/.sha after closeDetail()
                                    // nulls the flow is still safe.
                                    onClick = {
                                        realVm.closeDetail()
                                        onOpenDiff(detail.repo, detail.sha, file.path)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Info banner while the session is still running: the worktree (and therefore the graph below)
 *  is still changing, so what's displayed may not be the final state. */
@Composable
private fun LiveWorktreeBanner() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Session still running — the worktree is still changing, so the commits and " +
                    "changes below may not be up to date yet.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RepoHeader(repo: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            repo,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** Top "Uncommitted changes (N)" node — opens the working-tree file list and hosts Discard. */
@Composable
private fun UncommittedRow(
    unc: Uncommitted,
    graphRow: GraphRow<CommitNode>?,
    onClick: () -> Unit,
    onDiscard: () -> Unit,
) {
    val n = unc.added + unc.modified + unc.deleted
    val accent = MaterialTheme.colorScheme.tertiary
    val trunk = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.onSurface
    val lanes = ((graphRow?.maxColumn ?: 0) + 1).coerceIn(1, MAX_LANES)
    val gutterWidth = LANE_INSET + LANE_SPACING * lanes
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(end = 8.dp),
    ) {
        // Gutter: working-tree tip — hollow tertiary dot + trunk line down to HEAD.
        Box(
            Modifier
                .width(gutterWidth)
                .height(ROW_HEIGHT)
                .drawBehind {
                    graphRow?.let { drawGraphRow(it, trunk = trunk, sessionRing = ring, tipColor = accent) }
                        ?: run {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            drawLine(accent, Offset(cx, cy), Offset(cx, size.height), strokeWidth = LANE_STROKE)
                            drawCircle(accent, DOT_RADIUS, Offset(cx, cy), style = Stroke(LANE_STROKE))
                        }
                },
        )
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Uncommitted changes ($n)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
            Text(
                "+${unc.added} ~${unc.modified} -${unc.deleted}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onDiscard,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Icon(
                Icons.Rounded.DeleteForever, null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("Discard", color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/** One commit row: graph gutter + short SHA (mono) + subject + author + relative time. */
@Composable
private fun CommitRow(
    commit: CommitNode,
    graphRow: GraphRow<CommitNode>?,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.onSurface
    val tip = MaterialTheme.colorScheme.tertiary
    val fallbackLane = MaterialTheme.colorScheme.outline
    val lanes = ((graphRow?.maxColumn ?: 0) + 1).coerceIn(1, MAX_LANES)
    val gutterWidth = LANE_INSET + LANE_SPACING * lanes

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(end = 8.dp),
    ) {
        // Multi-lane gutter: each branch is a coloured lane; merges/branches curve between columns;
        // >2-parent (octopus) merges keep the hollow dot + "M" label.
        Box(
            Modifier
                .width(gutterWidth)
                .height(ROW_HEIGHT)
                .drawBehind {
                    graphRow?.let { drawGraphRow(it, trunk = accent, sessionRing = ring, tipColor = tip) }
                        ?: run {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            drawLine(fallbackLane, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = LANE_STROKE)
                            drawCircle(fallbackLane, DOT_RADIUS, Offset(cx, cy))
                        }
                },
        ) {
            if (graphRow?.isOctopus == true) {
                Text(
                    "M",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = laneColor(graphRow.nodeLaneId, accent),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = LANE_INSET + LANE_SPACING * graphRow.nodeColumn),
                )
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    commit.shortSha,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (commit.isSession) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                // Refs + subject share the space left AFTER the trailing "session" chip is reserved.
                // Keeping this a single weighted child means the outer row's only non-weighted
                // trailing item is the session chip, so it always measures at its full width instead
                // of being starved to a near-zero constraint (which used to wrap "session" one char
                // per line). Inside, the subject flexes and ellipsizes; ref chips clamp on one line.
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Cap ref-chip count so a heavily-decorated commit can't push the subject off this
                    // single-line row.
                    commit.refs.take(MAX_REF_CHIPS).forEach { ref ->
                        RefChip(ref)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (commit.refs.size > MAX_REF_CHIPS) {
                        Text(
                            "+${commit.refs.size - MAX_REF_CHIPS}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    Text(
                        commit.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (commit.isSession) {
                    Spacer(Modifier.width(6.dp))
                    SessionChip(accent)
                }
            }
            Row {
                Text(
                    commit.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (commit.at > 0) {
                    Text(
                        " · ${relativeAge(commit.at)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionChip(accent: Color) {
    Surface(color = accent.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp)) {
        Text(
            "session",
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** Branch/tag/HEAD/remote label pointing at a commit (git %D). HEAD reads as the brand trunk colour
 *  (matching lane 0), tags gold, remotes muted, plain branches the tertiary container. */
@Composable
private fun RefChip(ref: GitRef) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (ref.kind) {
        "head" -> scheme.primary to scheme.onPrimary
        "tag" -> Color(0xFFE4C77E) to Color(0xFF2A2310)
        "remote" -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        else -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
    val label = if (ref.kind == "tag") "⌖ ${ref.name}" else ref.name
    Surface(color = bg, shape = RoundedCornerShape(6.dp), modifier = Modifier.widthIn(max = 120.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** One changed-file row: status letter + path (mono) + "+a -d". Tapping opens the full-screen
 *  line-level diff for this file. */
@Composable
private fun CommitFileRow(file: CommitFile, onClick: () -> Unit = {}) {
    val letter = statusLetter(file.status)
    val statusColor = when (letter) {
        "A" -> AddFg
        "D" -> DelFg
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
    ) {
        Text(
            letter.ifBlank { "?" },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = statusColor,
            modifier = Modifier.width(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            file.path,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "+${file.additions} -${file.deletions}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Gutter sizing (dp values converted to px inside drawBehind via DrawScope density).
private val ROW_HEIGHT = 56.dp
private val LANE_SPACING = 18.dp   // horizontal distance between adjacent lane centres
private val LANE_INSET = 6.dp      // left padding before lane 0's centre
private const val MAX_LANES = 6    // columns drawn before overflow clamps to the last lane (dimmed)
private const val MAX_REF_CHIPS = 3 // ref labels per row before collapsing the rest into "+N"
private const val LANE_STROKE = 3f
private const val DOT_RADIUS = 9f

/**
 * Draw one row's slice of the multi-lane graph: lane edges first (verticals, curved column transitions,
 * faded off-window stubs), then the node on top. Adjacent rows line up seamlessly because the layout
 * engine guarantees a lane's bottom column on one row equals its top column on the next.
 * [trunk] colours lane 0 (HEAD/first-parent); [tipColor] is the hollow working-tree dot;
 * [sessionRing] rings session commits so emphasis is independent of lane hue.
 */
private fun DrawScope.drawGraphRow(
    row: GraphRow<CommitNode>,
    trunk: Color,
    sessionRing: Color,
    tipColor: Color,
) {
    val spacing = LANE_SPACING.toPx()
    val inset = LANE_INSET.toPx()
    val h = size.height
    val cy = h / 2f
    fun clampCol(c: Int) = c.coerceIn(0, MAX_LANES - 1)
    fun laneX(c: Int) = inset + spacing * (clampCol(c) + 0.5f)
    fun overflow(c: Int) = c >= MAX_LANES

    for (e in row.edges) {
        val x0 = laneX(e.fromColumn)
        val x1 = laneX(e.toColumn)
        val y0 = if (e.fromTop) 0f else cy
        val y1 = if (e.toBottom) h else cy
        val alpha = if (overflow(e.fromColumn) || overflow(e.toColumn)) 0.5f else 1f
        val color = laneColor(e.laneId, trunk).copy(alpha = alpha)
        // Off-window edges fade toward the bottom; gradient clamps above startY so the upper half
        // (and the seam with the row above) stays solid.
        val brush = if (e.fadeBottom)
            Brush.verticalGradient(0f to color, 1f to color.copy(alpha = 0f), startY = cy, endY = h)
        else null
        if (x0 == x1) {
            if (brush != null) drawLine(brush, Offset(x0, y0), Offset(x0, y1), LANE_STROKE, cap = StrokeCap.Round)
            else drawLine(color, Offset(x0, y0), Offset(x0, y1), LANE_STROKE, cap = StrokeCap.Round)
        } else {
            val mid = (y0 + y1) / 2f
            val path = Path().apply { moveTo(x0, y0); cubicTo(x0, mid, x1, mid, x1, y1) }
            if (brush != null) drawPath(path, brush, style = Stroke(LANE_STROKE))
            else drawPath(path, color, style = Stroke(LANE_STROKE))
        }
    }

    val nodeX = laneX(row.nodeColumn)
    val nodeColor = laneColor(row.nodeLaneId, trunk)
    when {
        row.kind == GraphNodeKind.Uncommitted ->
            drawCircle(tipColor, DOT_RADIUS, Offset(nodeX, cy), style = Stroke(LANE_STROKE))
        row.isOctopus ->
            drawCircle(nodeColor, DOT_RADIUS, Offset(nodeX, cy), style = Stroke(LANE_STROKE))
        else ->
            drawCircle(nodeColor, DOT_RADIUS, Offset(nodeX, cy))
    }
    if (row.commit?.isSession == true) {
        drawCircle(sessionRing.copy(alpha = 0.9f), DOT_RADIUS + 3.5f, Offset(nodeX, cy), style = Stroke(2f))
    }
}
