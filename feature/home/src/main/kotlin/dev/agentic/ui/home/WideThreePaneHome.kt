package dev.agentic.ui.home

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import dev.agentic.ui.AppMotion
import dev.agentic.ui.FadingText
import dev.agentic.ui.appEffectsSpec
import dev.agentic.ui.appSpatialSpec
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.data.SessionUi
import dev.agentic.di.appContainer
import dev.agentic.ui.components.clearFocusOnTap
import dev.agentic.ui.session.DownloadEffect
import dev.agentic.ui.session.ForkState
import dev.agentic.ui.session.SessionContent
import dev.agentic.ui.session.SessionDetailOverlays
import dev.agentic.ui.session.SessionViewModel
import dev.agentic.ui.session.saveToDownloads
import dev.agentic.ui.workflow.AgentRail
import dev.agentic.ui.workflow.AgentTranscriptContent
import dev.agentic.ui.workflow.WorkflowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Visual width of each PaneSplitter lane (dp). The *touch* target is the larger SplitterTouchTargetDp (lane overflows symmetrically into neighbours as an invisible grab zone). */
private const val SplitterWidthDp = 24f

/** Touch width of each PaneSplitter (dp) — draggable area measured this wide, centred over the slim lane, overflowing into each neighbour. 48dp = Material's min interactive size. */
private const val SplitterTouchTargetDp = 48f

/** Wide 3-pane home: LEFT = session list, MIDDLE = collapsible workflow rail (when selected session has runs), RIGHT = chat or picked agent's transcript. Two draggable splitters; rail auto-hides below ~140dp. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WideThreePaneHome(
    onNewRequest: () -> Unit,
    onOpenHistory: (String, Boolean) -> Unit,
    onOpenSettings: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAdoptPicker: () -> Unit = {},
    onOpenGlobalSettings: () -> Unit = {},
    initialSelectedId: String? = null,
) {
    val container = appContainer()
    val homeVm: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(container.sessionsRepo) }
        },
    )
    val homeState by homeVm.uiState.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf(initialSelectedId) }
    // Guard a programmatically-selected id (just-forked, handed over from AppNav) from the clear-on-vanish effect until it surfaces in the polled list.
    var pendingSelect by rememberSaveable { mutableStateOf<String?>(null) }
    var listWidth by rememberSaveable { mutableStateOf(340f) } // dp, drag-adjustable

    // Wide-mode "open this session" handoff from AppNav. In wide mode AppNav does NOT push a separate Session route (which would render a duplicate full-screen Home on the back stack — the "two-layer Home" bug); it writes the id here and reveals this single Home.
    val openRequest by container.homeSelectRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openRequest) {
        val id = openRequest ?: return@LaunchedEffect
        selectedId = id
        pendingSelect = id
        homeVm.refresh()
        container.homeSelectRequest.value = null
    }

    val listState = rememberLazyListState()
    var searching by rememberSaveable { mutableStateOf(false) }
    var moveSelectedOpen by rememberSaveable { mutableStateOf(false) }
    var moveTargetSid by rememberSaveable { mutableStateOf<String?>(null) }
    val fabExpanded by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 50
        }
    }

    // Drop the selection if the open session leaves the list (deleted / pruned). Guard on !loading so a deep-linked initialSelectedId isn't cleared during the first (empty) load tick.
    LaunchedEffect(homeState.sessions, homeState.loading) {
        if (pendingSelect != null && homeState.sessions.any { it.id == pendingSelect }) {
            pendingSelect = null
        }
        if (!homeState.loading && selectedId != null && selectedId != pendingSelect &&
            homeState.sessions.none { it.id == selectedId }
        ) {
            selectedId = null
        }
    }

    Scaffold(
        topBar = {
            HomeTopBar(
                selectionMode = homeState.selectionMode,
                selectedCount = homeState.selectedCount,
                totalCount = homeState.sessions.size,
                onCloseSelection = homeVm::clearSelection,
                onSelectAll = homeVm::selectAll,
                onDeleteSelected = homeVm::deleteSelected,
                onForkSelected = homeVm::forkSelected,
                onMoveSelected = { moveSelectedOpen = true },
                onLogout = container.authRepo::logout,
                searchQuery = homeState.searchQuery,
                searching = homeState.searching,
                searchResults = homeState.searchResults,
                onSearchQueryChange = homeVm::setSearchQuery,
                onOpenSession = { selectedId = it },
                onSearchExpandedChange = { searching = it },
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenAdoptPicker = onOpenAdoptPicker,
                onOpenGlobalSettings = onOpenGlobalSettings,
            )
        },
    ) { pad ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(pad).clearFocusOnTap(),
        ) {
            val maxW = maxWidth.value
            // Pane-width budget. List and rail gutters drag independently — without a shared reserve they could combine to starve the weight(1f) chat column to zero width (drag both gutters fully out at ~840dp).
            val minChat = 300f
            // Minimum rail width; doubles as the auto-hide threshold.
            val minRail = 140f
            val splittersReserve = SplitterWidthDp * 2

            // Rail geometry lives INSIDE each session's crossfade layer, keyed on that layer's id. Hoisting it here keyed on selectedId remeasured the OUTGOING layer with the INCOMING session's geometry the instant selection changed (a visible one-frame rewrap of the old rail). Only the live layer's rail footprint is mirrored out so the LIST splitter's budget can read it without reaching into per-layer state.
            val store = container.sessionUiStore
            var liveRailReserve by remember { mutableStateOf(0f) }

            Row(Modifier.fillMaxSize().imePadding()) {
                // LEFT pane: session list + New-request FAB anchored to this column.
                Box(Modifier.width(listWidth.dp).fillMaxHeight()) {
                    SessionListPane(
                        state = homeState,
                        selectedId = selectedId,
                        onOpen = { selectedId = it },
                        onDelete = homeVm::delete,
                        onRefresh = homeVm::refresh,
                        onToggleSelect = homeVm::toggleSelection,
                        onLongPress = homeVm::toggleSelection,
                        onClearSearch = { homeVm.setSearchQuery("") },
                        onSelectGroupFilter = homeVm::selectGroupFilter,
                        onRenameGroup = { id, name -> homeVm.updateGroup(id, name) },
                        onDeleteGroup = homeVm::deleteGroup,
                        onCreateGroup = { homeVm.createGroup(it) },
                        modifier = Modifier.fillMaxSize(),
                        listState = listState,
                    )
                    if (!homeState.selectionMode && !searching) {
                        ExtendedFloatingActionButton(
                            onClick = onNewRequest,
                            expanded = fabExpanded,
                            icon = { Icon(Icons.Rounded.Add, null) },
                            text = { Text("New request") },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        )
                    }
                }
                PaneSplitter(onDelta = { d ->
                    // Rail is on the far RIGHT now (not adjacent to list), so the list splitter resizes list vs chat directly — no redistribution with rail.
                    val upper = (maxW - liveRailReserve - minChat - splittersReserve).coerceAtLeast(200f)
                    val nw = listWidth + d
                    listWidth = nw.coerceIn(200f, upper)
                })

                // Everything right of the list splitter — header, chat/agent body, workflow rail — cross-fades as ONE unit on session switch (emphasized 250ms dissolve).
                // CRITICAL: everything inside derives from the lambda's [currentSid], NEVER from [selectedId]. Per-session VMs/state used to live OUTSIDE this Crossfade — both fade layers then rendered the NEW session's data, degrading the dissolve into a hard cut (old title + new id, old rail remeasured at the new session's width — visible text rewrap).
                Crossfade(
                    targetState = selectedId,
                    animationSpec = tween(durationMillis = AppMotion.DurationMedium1, easing = AppMotion.Emphasized),
                    label = "session-switch",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { currentSid ->
                    // Only the LIVE layer (the fade target) mirrors geometry out + runs side-effects;
                    // an outgoing layer keeps rendering its own session, frozen, while it fades away.
                    val isLive = currentSid == selectedId
                    if (currentSid == null) {
                        if (isLive) SideEffect { liveRailReserve = 0f }
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text(
                                "Select a session",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        val sVm: SessionViewModel = viewModel(
                            key = "s:$currentSid",
                            factory = viewModelFactory {
                                initializer {
                                    SessionViewModel(
                                        container.sessionsRepo,
                                        container.workflowsRepo,
                                        container.filesRepo,
                                        container.api,
                                        currentSid,
                                    )
                                }
                            },
                        )
                        val wVm: WorkflowViewModel = viewModel(
                            key = "w:$currentSid",
                            factory = viewModelFactory {
                                initializer { WorkflowViewModel(container.workflowsRepo, currentSid) }
                            },
                        )
                        val s by sVm.uiState.collectAsStateWithLifecycle()
                        val wf by wVm.uiState.collectAsStateWithLifecycle()
                        if (isLive) {
                            // Discord-style: selecting this session acks the current "your turn" point. Keyed on session.unreadEventId so it fires AFTER the session loads.
                            LaunchedEffect(s.session?.unreadEventId) {
                                val eid = s.session?.unreadEventId ?: return@LaunchedEffect
                                sVm.ackEvent(eid)
                            }
                            // Warm-return self-heal — PARITY with phone SessionScreen.
                            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { sVm.refresh() }
                        }
                        // Rewind confirm trigger (set from transcript long-press; shown by shared SessionDetailOverlays).
                        var pendingRewindTurn by rememberSaveable(currentSid) { mutableStateOf<Int?>(null) }

                        // Per-layer rail geometry, seeded from store once per layer; drag frames touch plain MutableState only.
                        // Coerce persisted widths up to [minRail] — older installs may have saved a narrower rail (the minimum used to be lower), and a raw value below min diverges from the rendered effectiveRailWidth (drag would then hide the rail on a visually-expanding drag).
                        var railWidth by remember(currentSid) { mutableStateOf(store.get(currentSid).railWidth.coerceAtLeast(minRail)) }
                        var railHidden by remember(currentSid) { mutableStateOf(store.get(currentSid).railHidden) }
                        var expandedRuns by remember(currentSid) { mutableStateOf(store.get(currentSid).expandedRuns) }
                        // Debounce persists: every change restarts the effect, so the put fires once values settle, not on every drag frame. Dispose flush below covers the tail case (a change made <500ms before this layer leaves composition would otherwise never be saved).
                        LaunchedEffect(currentSid, railWidth, railHidden, expandedRuns) {
                            delay(500)
                            store.put(currentSid, SessionUi(railHidden, railWidth, expandedRuns))
                        }
                        DisposableEffect(currentSid) {
                            onDispose { store.put(currentSid, SessionUi(railHidden, railWidth, expandedRuns)) }
                        }

                        val showRail = s.hasRuns && !railHidden
                        // Mirror the live rail footprint out for the list splitter's budget. Keyed on railHidden (NOT showRail): hasRuns arrives async from the VM, gating on it would leave a too-loose clamp window until the session loads.
                        if (isLive) SideEffect { liveRailReserve = if (!railHidden) railWidth else 0f }

                        // MIDDLE = chat (chrome parity with phone: history, fork, workflow toggle) + RIGHT workflow rail, in one per-session row so both fade together.
                        Row(Modifier.fillMaxSize()) {
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        // FadingText (parity with phone titles): overflow dissolves at the right edge instead of ellipsis. Full prompt, no take(40).
                                        FadingText(
                                            (s.session?.prompt
                                                ?: homeState.sessions.firstOrNull { it.id == currentSid }?.prompt
                                                ?: "").ifBlank { "Session" },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            currentSid.take(8),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = { onOpenHistory(currentSid, !s.terminal) }) {
                                        Icon(
                                            Icons.Rounded.Commit, "commit history",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = { sVm.fork() }) {
                                        Icon(
                                            Icons.Rounded.CallSplit, "fork session",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = { moveTargetSid = currentSid }) {
                                        Icon(
                                            Icons.Rounded.Folder, "move to group",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (s.hasRuns) {
                                        IconButton(onClick = { railHidden = !railHidden }) {
                                            Icon(
                                                Icons.Rounded.AccountTree,
                                                if (railHidden) "show workflows" else "hide workflows",
                                                tint = if (!railHidden) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }

                                // Fork success → jump the detail pane to the new session in-place.
                                if (isLive) {
                                    LaunchedEffect(sVm) {
                                        sVm.forkedTo.collect { id ->
                                            if (id != null) {
                                                pendingSelect = id
                                                selectedId = id
                                                homeVm.refresh()
                                                sVm.acknowledgeFork()
                                            }
                                        }
                                    }
                                }

                                // Body: picked agent's transcript when one is selected in the rail, else chat. No inner per-session animation — the whole detail row already lives inside the session Crossfade.
                                val sel = wf.selectedAgent
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    if (showRail && sel != null) {
                                        AgentTranscriptContent(
                                            agent = sel.second,
                                            transcript = wf.agentTranscript,
                                            loading = wf.loadingTranscript,
                                            effort = s.session?.effort,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        SessionContent(
                                            s = s,
                                            realVm = sVm,
                                            onOpenWorkflow = { wfNode ->
                                                val rid = wVm.selectRun(wfNode.runId, wfNode.name)
                                                if (rid != null) { railHidden = false; expandedRuns = expandedRuns + rid }
                                            },
                                            onOpenParent = { selectedId = it },
                                            onOpenSettings = { onOpenSettings(currentSid) },
                                            onRewind = { turn -> pendingRewindTurn = turn },
                                            applyImePadding = false,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }

                            }
                            // RIGHT = workflow rail (only when session has runs and not hidden). Lives INSIDE the session's crossfade layer: on session switch it fades with the rest of the layer instead of playing this slide against the incoming session's geometry. Slide still animates hide/show and the drag-below-minimum dismissal within a session.
                            // expand/shrink ride with the slide so the CHAT column grows/shrinks in lockstep with the rail's motion. Without them AnimatedVisibility holds the rail's full layout width until exit finishes (vacated space sat empty, then chat snapped wider) and claims it instantly on enter (chat snapped narrower before the rail slid in). Shrink/expand CLIP the content (stays measured at full width) so rail text never rewraps mid-animation.
                            AnimatedVisibility(
                                visible = showRail,
                                enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = appSpatialSpec()) +
                                    expandHorizontally(animationSpec = appSpatialSpec()) + fadeIn(appEffectsSpec()),
                                exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = appSpatialSpec()) +
                                    shrinkHorizontally(animationSpec = appSpatialSpec()) + fadeOut(appEffectsSpec()),
                            ) {
                                Row(Modifier.fillMaxHeight()) {
                                    // Splitter on the LEFT side of the rail (between chat and rail). Drag right (d>0) → rail shrinks, so negate delta.
                                    PaneSplitter(onDelta = { d ->
                                        // Once the rail is exiting (drag crossed auto-hide, or session's runs vanished) the gesture is DONE: ignore the rest. Letting it keep writing railWidth retargeted the exit spring's slide distance every frame — the spring never settled, the rail's (invisible) layout box squatted on the vacated space until the finger lifted, and dragging back left "revived" a zombie rail. (!showRail, not railHidden: also covers hasRuns-driven exit.)
                                        if (!showRail) return@PaneSplitter
                                        val nw = railWidth - d
                                        if (nw < minRail) {
                                            railHidden = true
                                        } else {
                                            railWidth = nw.coerceIn(minRail, (maxW - listWidth - minChat - splittersReserve).coerceAtLeast(minRail))
                                        }
                                    })
                                    val effectiveRailWidth = railWidth
                                        .coerceIn(minRail, (maxW - listWidth - minChat - splittersReserve).coerceAtLeast(minRail))
                                    AgentRail(
                                        runs = wf.runs,
                                        selected = wf.selectedAgent,
                                        onSelect = { (rid, a) -> wVm.selectAgent(rid, a) },
                                        onSelectMain = { wVm.selectMain() },
                                        expandedRuns = expandedRuns,
                                        onToggleRun = { rid ->
                                            expandedRuns = if (rid in expandedRuns) expandedRuns - rid else expandedRuns + rid
                                        },
                                        modifier = Modifier.width(effectiveRailWidth.dp).fillMaxHeight(),
                                    )
                                }
                            }
                        }

                        // Toasts + shared chrome only for the live layer (an outgoing layer must not keep toasting or stack a second dialog host while it fades out).
                        if (isLive) {
                            val context = LocalContext.current
                            // Download SAVE effect.
                            LaunchedEffect(sVm) {
                                sVm.downloads.collect { eff ->
                                    when (eff) {
                                        is DownloadEffect.Started ->
                                            Toast.makeText(context, "Downloading ${eff.name}…", Toast.LENGTH_SHORT).show()
                                        is DownloadEffect.Ready -> {
                                            val ok = withContext(Dispatchers.IO) {
                                                try { saveToDownloads(context, eff.name, eff.file) } finally { eff.file.delete() }
                                            }
                                            Toast.makeText(
                                                context,
                                                if (ok) "Saved to Downloads: ${eff.name}" else "Couldn't save ${eff.name}",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                        is DownloadEffect.Failed ->
                                            Toast.makeText(context, "Download failed: ${eff.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            // Fork failures.
                            LaunchedEffect(sVm) {
                                sVm.forkState.collect { st ->
                                    if (st is ForkState.Failed) {
                                        Toast.makeText(context, "Couldn't fork: ${st.message}", Toast.LENGTH_LONG).show()
                                        sVm.acknowledgeForkError()
                                    }
                                }
                            }
                            // Shared chrome: rewind dialog + rewind toast — same wiring as the phone SessionScreen (single source of truth in SessionDetailOverlays).
                            SessionDetailOverlays(
                                vm = sVm,
                                pendingRewindTurn = pendingRewindTurn,
                                onDismissRewind = { pendingRewindTurn = null },
                            )
                        }
                    }
                }
            }
        }

        // ── Group picker sheet (multi-select move) ──────────────────────────
        if (moveSelectedOpen) {
            GroupPickerSheet(
                groups = homeState.groups,
                onDismiss = { moveSelectedOpen = false },
                onGroupPicked = { groupId ->
                    moveSelectedOpen = false
                    homeState.selectedIds.forEach { homeVm.moveSessionToGroup(it, groupId) }
                    homeVm.clearSelection()
                },
            )
        }

        // ── Group picker sheet (per-session move from chrome) ──────────────
        val tsid = moveTargetSid
        if (tsid != null) {
            GroupPickerSheet(
                groups = homeState.groups,
                onDismiss = { moveTargetSid = null },
                onGroupPicked = { groupId ->
                    moveTargetSid = null
                    homeVm.moveSessionToGroup(tsid, groupId)
                },
            )
        }
    }
}

/** MD3-expressive pane separator: draggable gutter with a short rounded grip. Slim visually ([SplitterWidthDp]) but grabs wide ([SplitterTouchTargetDp]) — layout reports slim width to the parent Row while the draggable area is measured at the larger touch width, centred so it overflows symmetrically into neighbours. */
@Composable
private fun PaneSplitter(onDelta: (Float) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    // rememberUpdatedState keeps onDelta fresh across recompositions so DraggableState always calls the latest (with current listWidth/railWidth captures) — otherwise a stale lambda fights the finger ("sticky" splitter).
    val currentOnDelta by rememberUpdatedState(onDelta)
    Box(
        modifier
            .fillMaxHeight()
            .layout { measurable, constraints ->
                val laneWidth = SplitterWidthDp.dp.roundToPx()
                val touchWidth = SplitterTouchTargetDp.dp.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(minWidth = touchWidth, maxWidth = touchWidth),
                )
                layout(laneWidth, placeable.height) {
                    placeable.place((laneWidth - touchWidth) / 2, 0) // centre the wide touch area on the lane
                }
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { px -> currentOnDelta(px / density) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(4.dp).height(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
    }
}
