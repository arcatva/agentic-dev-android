package dev.agentic.ui.workflow

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.data.net.WorkflowAgent
import dev.agentic.data.net.WorkflowRun
import dev.agentic.di.appContainer
import dev.agentic.domain.StatusVisual
import dev.agentic.domain.isActive
import dev.agentic.domain.relativeAge
import dev.agentic.domain.statusVisual
import dev.agentic.ui.AccentViolet
import dev.agentic.ui.FadingText
import dev.agentic.ui.animateContentHeight
import dev.agentic.ui.components.StatusIndicator
import dev.agentic.ui.effortLabel
import dev.agentic.ui.modelLabel
import dev.agentic.ui.session.DisplayChip

/** Sort key for ordering agents active-first within a phase. WorkflowAgent has no isActive()
 *  extension (only WorkflowRun does), so we treat the terminal states as inactive directly. */
private fun WorkflowAgent.activeRank(): Int =
    if (state in setOf("done", "complete", "completed", "failed")) 1 else 0


/**
 * Workflow detail screen. Stateless: all state lives in [WorkflowViewModel].
 *
 * Wide layout (≥640 dp): AgentRail (320 dp) | VerticalDivider | AgentTranscriptPane
 * Narrow layout: AgentRail or transcript (BackHandler returns to rail)
 *
 * VM creation note: [appContainer] is @Composable so it is resolved in the body first, then
 * captured into the non-composable initializer. The nullable [vm] parameter allows injection
 * in tests/previews.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkflowScreen(
    onBack: () -> Unit,
    vm: WorkflowViewModel? = null,
) {
    val container = appContainer()
    val realVm = vm ?: viewModel(factory = viewModelFactory {
        initializer {
            WorkflowViewModel(container.workflowsRepo, createSavedStateHandle())
        }
    })
    val s by realVm.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "back")
                }
            },
            title = { Text("Workflows") },
        )
    }) { pad ->
        val runs = s.runs
        when {
            runs.isEmpty() && s.selectedAgent == null -> {
                Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Show a spinner while at least one run is active or while there are no
                        // runs yet (journal may still be initialising); fall through to the empty
                        // message once all known runs have settled.
                        if (runs.any { it.isActive() } || runs.isEmpty()) {
                            LoadingIndicator(color = AccentViolet)
                            Text(
                                "Workflow starting…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                "No workflows for this session",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
                val wide = maxWidth >= 640.dp
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        AgentRail(
                            runs = runs,
                            selected = s.selectedAgent,
                            onSelect = { (runId, agent) -> realVm.selectAgent(runId, agent) },
                            modifier = Modifier.width(320.dp),
                            onSelectMain = { realVm.selectMain() },
                        )
                        VerticalDivider()
                        Box(Modifier.weight(1f)) {
                            val sel = s.selectedAgent
                            if (sel == null) {
                                Box(Modifier.fillMaxSize(), Alignment.Center) {
                                    Text(
                                        "Select an agent to view its transcript",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                AgentTranscriptContent(
                                    agent = sel.second,
                                    transcript = s.agentTranscript,
                                    loading = s.loadingTranscript,
                                )
                            }
                        }
                    }
                } else {
                    val sel = s.selectedAgent
                    if (sel == null) {
                        AgentRail(
                            runs = runs,
                            selected = null,
                            onSelect = { (runId, agent) -> realVm.selectAgent(runId, agent) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        BackHandler { realVm.selectMain() }
                        AgentTranscriptContent(
                            agent = sel.second,
                            transcript = s.agentTranscript,
                            loading = s.loadingTranscript,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Wraps the agent header (label / state / model) above [AgentTranscriptPane].
 * Kept internal so the header info stays co-located with the pane without polluting
 * [AgentTranscriptPane]'s pure-display signature.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentTranscriptContent(
    agent: WorkflowAgent,
    transcript: String?,
    loading: Boolean,
    modifier: Modifier = Modifier,
    effort: String? = null,
) {
    Column(modifier.fillMaxSize()) {
        // Live indicator — the same expressive wavy bar the session screen runs while a turn generates,
        // dyed in the workflow accent. It replaces the per-agent status icon that used to sit in the
        // header chip row; an agent's terminal (done/failed) status still shows in the AgentRail card.
        if (statusVisual(agent.state, null) == StatusVisual.RUNNING) {
            LinearWavyProgressIndicator(color = AccentViolet, modifier = Modifier.fillMaxWidth())
        }
        // Header sits close to the Task card below (the agent pane uses a small top inset).
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            Text(agent.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            // model + effort as the session's annotation chips, dyed in the workflow accent. Per-agent
            // effort isn't in the workflow payload, so [effort] is the session-level value the caller
            // supplies (the 3-pane host passes the session's effort; null → no effort chip).
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (agent.model.isNotBlank()) {
                        DisplayChip(modelLabel(agent.model), Icons.Rounded.SmartToy, AccentViolet, "model")
                    }
                    effort?.takeIf { it.isNotBlank() }?.let {
                        DisplayChip(effortLabel(it), Icons.Rounded.Bolt, AccentViolet, "effort")
                    }
                }
            }
        }
        AgentTranscriptPane(
            transcript = transcript,
            loading = loading,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The secondary lines under a workflow agent's label in the [AgentRail] card: the agent's short id
 * (monospace, truncated if long) on its own line, then the model on the line below it. Either line
 * is dropped when its value is blank, so a card with no id or no model shows just the remaining line
 * with no empty gap. [style] lets a caller override the label size.
 */
@Composable
private fun AgentSubtitle(
    agentId: String,
    model: String,
    idColor: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
) {
    Column(modifier) {
        if (agentId.isNotBlank()) {
            Text(
                agentId,
                style = style,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = idColor,
            )
        }
        if (model.isNotBlank()) {
            Text(
                modelLabel(model),
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One agent row in the [AgentRail]'s expanded run: an OutlinedCard with a violet selection border.
 * Pulled out of the rail's LazyColumn `items {}` so the expanded block can live inside a single
 * [AnimatedVisibility] (the accordion) — a lazy `items` call can't be wrapped in one.
 */
@Composable
private fun AgentCard(
    agent: WorkflowAgent,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) AccentViolet
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 8.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Always reserve the indicator slot (fixed footprint) + spacer so the label and subtitle
            // sit at the same x whether or not a dot/spinner is showing — a done/idle agent paints
            // nothing but still occupies the box. Sizing matches the session list's SessionRow
            // (start 8 · indicator 16 · gap 6 → text at 30.dp) so the slot is a tight inset, not a
            // wide blank, and the text never shifts when it clears.
            StatusIndicator(agent.state, size = 16.dp, accent = AccentViolet)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                FadingText(
                    agent.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                // Secondary line: the agent's short id (monospace) so agents are identifiable even
                // when labels collide, then the model.
                AgentSubtitle(
                    agentId = agent.agentId,
                    model = agent.model,
                    idColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The workflow/agent rail. Cards use a distinct surface treatment from the session list:
 * run headers are filled tonal cards (surfaceContainerHighest) with a tertiary accent,
 * and each agent is an OutlinedCard with a tertiary selection border.
 *
 * When [onSelectMain] is provided, a "Main conversation" ElevatedCard sits at the top and
 * is highlighted while no agent is selected; tapping it clears the selection.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentRail(
    runs: List<WorkflowRun>,
    selected: Pair<String, WorkflowAgent>?,
    onSelect: (Pair<String, WorkflowAgent>) -> Unit,
    modifier: Modifier = Modifier,
    onSelectMain: (() -> Unit)? = null,
    expandedRuns: Set<String>? = null,
    onToggleRun: ((String) -> Unit)? = null,
) {
    // Per-run collapse state. Runs default COLLAPSED — the user opens the one they want; avoids a wall
    // of agent cards when a session has several workflow runs.
    // When [expandedRuns] is provided the host owns this state; otherwise we keep it local.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    if (expandedRuns == null) {
        runs.forEach { run -> if (run.runId !in expanded) expanded[run.runId] = false }
    }
    fun isOpen(runId: String) = expandedRuns?.contains(runId) ?: (expanded[runId] ?: false)

    // Newest first: sort by the backend's run creation time when present; otherwise fall back to the
    // prior insertion-order-reversed behaviour (an older backend sends no createdAt → all 0).
    val ordered = remember(runs) {
        if (runs.any { it.createdAt > 0 }) runs.sortedByDescending { it.createdAt } else runs.asReversed()
    }

    // Expressive motion specs for the per-run expand. Read here (composable scope) and captured into
    // the LazyColumn builder below, which is NOT a composable scope and so can't read MaterialTheme.
    // Same spring family the rail toggle in AdaptiveHome uses, so the two expansions feel consistent.
    val motion = MaterialTheme.motionScheme
    val sizeSpec = motion.defaultSpatialSpec<IntSize>()
    val fadeSpec = motion.defaultEffectsSpec<Float>()

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(12.dp),
        // Match the session list's inter-card gap (its cards use vertical=5.dp → ~10.dp apart).
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onSelectMain != null) item(key = "main-conv") {
            val sel = selected == null
            ElevatedCard(
                onClick = onSelectMain,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().animateItem(),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Forum, null,
                        modifier = Modifier.size(20.dp),
                        tint = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    FadingText(
                        "Main",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        ordered.forEach { run ->
            val open = isOpen(run.runId)
            // One item per run: the header Card plus the agent block, wrapped together so the expand
            // is a single coordinated accordion. AnimatedVisibility grows the block in with
            // expandVertically + fade (Expressive spring) — the agents slide DOWN out of the header and
            // the runs below reflow — instead of the old burst of separately-fading lazy items.
            item(key = "run-${run.runId}") {
                Column(Modifier.fillMaxWidth().animateItem()) {
                    Card(
                        onClick = {
                            if (onToggleRun != null) onToggleRun(run.runId)
                            else expanded[run.runId] = !open
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.AccountTree, null,
                                    modifier = Modifier.size(18.dp),
                                    tint = AccentViolet,
                                )
                                Spacer(Modifier.width(8.dp))
                                FadingText(
                                    run.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    // SemiBold matches the app-wide card-header emphasis
                                    // (SectionCard headers, "Main" in the rail).
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                // Finished runs need no status affordance; failed/running/etc. show dot + label.
                                val finished = run.status.trim().lowercase() in
                                    setOf("done", "complete", "completed")
                                if (!finished) {
                                    StatusIndicator(run.status, modifier = Modifier.size(18.dp), accent = AccentViolet)
                                }
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    if (open) "collapse" else "expand",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Line 1: agents · phases · how many of them are done (+ failures if any), so a
                            // collapsed run still shows its size and progress at a glance.
                            val total = run.agentCount ?: run.agents.size
                            val doneCount = run.agents.count { it.activeRank() == 1 && it.state != "failed" }
                            val failed = run.agents.count { it.state == "failed" }
                            FadingText(
                                "$total agents · ${run.phases.size} phases · $doneCount/$total done" +
                                    (if (failed > 0) " · $failed failed" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                            // Line 2: creation time (hidden when an older backend supplies none).
                            if (run.createdAt > 0) Text(
                                "created ${relativeAge(run.createdAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            // Line 3: the run id — small, single line, ellipsised if long.
                            Text(
                                "id: ${run.runId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = open,
                        // expandFrom/shrinkTowards Top: reveal top→bottom (in phase/agent order),
                        // anchored under the header, so the block unrolls downward.
                        enter = expandVertically(animationSpec = sizeSpec, expandFrom = Alignment.Top) +
                            fadeIn(animationSpec = fadeSpec),
                        exit = shrinkVertically(animationSpec = sizeSpec, shrinkTowards = Alignment.Top) +
                            fadeOut(animationSpec = fadeSpec),
                    ) {
                        // animateContentHeight (height-only) so agents streaming in live (or
                        // re-sorting active-first) grow the block smoothly instead of snapping —
                        // while the block's WIDTH snaps instantly to the rail width when the pane
                        // splitter is dragged, so the agent cards stay flush with the full-width run
                        // header card instead of lagging behind it through a width spring. (Plain
                        // animateContentSize springs the whole IntSize, so on a live resize the
                        // fillMaxWidth agent cards animated their width and read as narrower than the
                        // header.) The 10dp lead padding lives INSIDE the animated block so a
                        // collapsed run shows no phantom gap under its header; the inner spacedBy
                        // keeps the prior 10dp rhythm between rows.
                        Column(
                            Modifier
                                .animateContentHeight()
                                .padding(top = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (run.agents.isEmpty()) {
                                // Expanded-but-empty run: a placeholder so the section isn't blank.
                                Text(
                                    "waiting for agents…",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            } else {
                                // Group agents under their phase title, preserving order.
                                run.agents.groupBy { it.phaseTitle ?: "" }.forEach { (phase, ags) ->
                                    if (phase.isNotBlank()) {
                                        Text(
                                            phase,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = AccentViolet,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    ags.sortedBy { it.activeRank() }.forEach { ag ->
                                        key(run.runId, ag.agentId) {
                                            AgentCard(
                                                agent = ag,
                                                selected = selected?.second?.agentId == ag.agentId,
                                                onClick = { onSelect(run.runId to ag) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
