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

/** Sort key for ordering agents active-first within a phase. Terminal states → inactive. */
private fun WorkflowAgent.activeRank(): Int =
    if (state in setOf("done", "complete", "completed", "failed")) 1 else 0


/**
 * Workflow detail screen. Stateless: state lives in [WorkflowViewModel].
 *   Wide (≥640 dp): AgentRail (320 dp) | VerticalDivider | AgentTranscriptPane
 *   Narrow: AgentRail or transcript (BackHandler returns to rail)
 *
 * VM creation: [appContainer] is @Composable so resolved in body first, captured into the
 * non-composable initializer. Nullable [vm] allows injection in tests/previews.
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
                        // Spinner while any run is active or none loaded (journal may still be initialising);
                        // fall through to empty message once all known runs have settled.
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

/** Wraps the agent header (label/state/model) above [AgentTranscriptPane]. Internal so the header
 *  info stays co-located with the pane without polluting [AgentTranscriptPane]'s pure-display signature. */
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
        // in the workflow accent.
        if (statusVisual(agent.state, null) == StatusVisual.RUNNING) {
            LinearWavyProgressIndicator(color = AccentViolet, modifier = Modifier.fillMaxWidth())
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            Text(agent.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            // model + effort as session annotation chips, in the workflow accent. Per-agent effort
            // isn't in the workflow payload — [effort] is the session-level value the caller supplies
            // (the 3-pane host passes the session's effort; null → no effort chip).
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

/** Secondary lines under a workflow agent's label in the [AgentRail] card: short id (monospace,
 *  truncated if long) on its own line, then the model. Either line dropped when blank — no empty gap.
 *  [style] lets a caller override the label size. */
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

/** One agent row in the [AgentRail]'s expanded run: OutlinedCard with violet selection border.
 *  Pulled out of the rail's LazyColumn `items {}` so the expanded block lives inside one
 *  [AnimatedVisibility] (the accordion) — a lazy `items` can't be wrapped in one. */
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
            // Reserve the indicator slot + spacer so label/subtitle sit at the same x whether or not
            // a dot/spinner is showing — a done/idle agent paints nothing but still occupies the box.
            // Sizing matches the session list's SessionRow (start 8 · indicator 16 · gap 6 → 30.dp).
            StatusIndicator(agent.state, size = 16.dp, accent = AccentViolet)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                FadingText(
                    agent.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
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

/** The workflow/agent rail. Cards use a distinct surface treatment from the session list: run
 *  headers are filled tonal cards (surfaceContainerHighest) with a tertiary accent; each agent is an
 *  OutlinedCard with a tertiary selection border.
 *
 *  When [onSelectMain] is provided, a "Main conversation" ElevatedCard sits at the top and is
 *  highlighted while no agent is selected; tapping it clears the selection. */
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
    // Per-run collapse state. Runs default COLLAPSED — user opens the one they want.
    // When [expandedRuns] provided, host owns this state; otherwise local.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    if (expandedRuns == null) {
        runs.forEach { run -> if (run.runId !in expanded) expanded[run.runId] = false }
    }
    fun isOpen(runId: String) = expandedRuns?.contains(runId) ?: (expanded[runId] ?: false)

    // Newest first: backend's run creation time when present; else insertion-order-reversed.
    val ordered = remember(runs) {
        if (runs.any { it.createdAt > 0 }) runs.sortedByDescending { it.createdAt } else runs.asReversed()
    }

    // Read motion specs in composable scope for use in LazyColumn builder (not a composable scope).
    // Same spring family the rail toggle in AdaptiveHome uses.
    val motion = MaterialTheme.motionScheme
    val sizeSpec = motion.defaultSpatialSpec<IntSize>()
    val fadeSpec = motion.defaultEffectsSpec<Float>()

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(12.dp),
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
            // One item per run: header Card + agent block, wrapped so the expand is a single
            // coordinated accordion. AnimatedVisibility grows the block in with expandVertically + fade;
            // agents slide DOWN out of the header and runs below reflow.
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
                                    // SemiBold matches the app-wide card-header emphasis (SectionCard headers, "Main").
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
                            // Line 1: agents · phases · how many done (+ failures if any), so a
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
                            if (run.createdAt > 0) Text(
                                "created ${relativeAge(run.createdAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
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
                        // expandFrom/shrinkTowards Top: reveal top→bottom anchored under the header.
                        enter = expandVertically(animationSpec = sizeSpec, expandFrom = Alignment.Top) +
                            fadeIn(animationSpec = fadeSpec),
                        exit = shrinkVertically(animationSpec = sizeSpec, shrinkTowards = Alignment.Top) +
                            fadeOut(animationSpec = fadeSpec),
                    ) {
                        // animateContentHeight (height-only): agents streaming in/re-sorting active-first
                        // grow the block smoothly; WIDTH snaps to the rail width when the pane splitter
                        // is dragged so agent cards stay flush with the full-width run header card (plain
                        // animateContentSize springs the whole IntSize so fillMaxWidth cards animated their
                        // width on resize, reading narrower than the header).
                        Column(
                            Modifier
                                .animateContentHeight()
                                .padding(top = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (run.agents.isEmpty()) {
                                Text(
                                    "waiting for agents…",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            } else {
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
