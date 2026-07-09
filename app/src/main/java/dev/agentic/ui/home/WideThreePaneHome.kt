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

/** *Visual* width of each [PaneSplitter] lane, in dp — the gap the gutter occupies between panes, and
 *  the width it reports to the layout. Kept thin so the gutters don't look bulky. The *touch* target is
 *  the larger [SplitterTouchTargetDp] (the lane overflows symmetrically into the neighbours as an
 *  invisible grab zone), so a slim look doesn't cost drag reliability. The pane-width clamps reserve
 *  `2 ×` this so the two gutters can never starve the chat column. */
private const val SplitterWidthDp = 24f

/** *Touch* width of each [PaneSplitter], in dp. The draggable area is measured this wide and centred over
 *  the slim [SplitterWidthDp] lane, overflowing ~12dp into each neighbouring pane, so finger presses land
 *  reliably even though the visible gutter is thin. 48dp = Material's minimum interactive size. The
 *  overflow can't steal the neighbours' taps/scrolls: [draggable] only consumes after the horizontal
 *  touch-slop, so taps (no slop) and vertical scrolls (wrong orientation) fall through to the pane below. */
private const val SplitterTouchTargetDp = 48f

/**
 * Wide 3-pane home (the classic layout): LEFT = session list, MIDDLE = collapsible workflow rail
 * (only when the selected session has runs), RIGHT = the session's chat — or, when an agent is picked
 * in the rail, that agent's transcript. Two draggable [PaneSplitter] gutters resize the list and the
 * rail; dragging the rail splitter below ~140dp hides it (reopen it from the right-pane chrome).
 *
 * Restored from the pre-scaffold layout, with one deliberate IMPROVEMENT over the original: the
 * right-pane chrome now reaches **parity with the phone `SessionScreen`** — it hosts the long-press
 * Rewind dialog/toast (via the shared [SessionDetailOverlays] + the session VM's rewind state). The
 * old wide pane was missing those, so the wide chat used to silently lag the phone every time a
 * session action was added. Used by [HomeAdaptive] only when the window is wide; narrower windows use
 * the pane scaffold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WideThreePaneHome(
    onNewRequest: () -> Unit,
    onOpenHistory: (String, Boolean) -> Unit,
    onOpenSettings: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenProviders: () -> Unit = {},
    onOpenAdoptPicker: () -> Unit = {},
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
    // An id selected programmatically (just-forked, or handed over from AppNav for a new request /
    // deep link / notification) is selected immediately; protect it from the clear-on-vanish effect
    // until it surfaces in the polled list.
    var pendingSelect by rememberSaveable { mutableStateOf<String?>(null) }
    var listWidth by rememberSaveable { mutableStateOf(340f) } // dp, drag-adjustable

    // Wide-mode "open this session" handoff from AppNav. In wide mode AppNav does NOT push a separate
    // Session route (which would render a duplicate full-screen Home and stack it on the back stack —
    // the "two-layer Home" bug); instead it writes the id here and reveals this single Home. We apply
    // it to the selection, guard it from clear-on-vanish via [pendingSelect], kick a refresh so the
    // (possibly just-created) session surfaces in the list quickly, then consume the channel.
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

    // Drop the selection if the open session leaves the list (deleted / pruned). Guard on !loading so
    // a deep-linked [initialSelectedId] isn't cleared during the first (empty) load tick.
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
                onOpenProviders = onOpenProviders,
                onOpenAdoptPicker = onOpenAdoptPicker,
            )
        },
    ) { pad ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(pad).clearFocusOnTap(),
        ) {
            val maxW = maxWidth.value
            // Pane-width budget. The list and rail gutters are dragged independently, so without a
            // shared reserve they could combine to starve the weight(1f) chat column to zero width
            // (drag both gutters fully out at ~840dp). Reserve a minimum chat + rail so list + rail
            // can never consume the whole row.
            val minChat = 300f
            // Minimum rail width; doubles as the auto-hide threshold — dragging the rail splitter
            // below this hides the rail entirely.
            val minRail = 140f
            // Two gutters, each [SplitterWidthDp] wide, sit between the three panes; reserve their full
            // footprint so list + rail can never combine to push the chat column below [minChat].
            val splittersReserve = SplitterWidthDp * 2

            // Rail geometry (width/hidden/expanded) lives INSIDE each session's crossfade layer
            // below, keyed on that layer's id. Hoisting it here keyed on selectedId was what made
            // the OUTGOING layer remeasure with the INCOMING session's geometry the instant the
            // selection changed (a visible one-frame rewrap of the old rail). Only the live layer's
            // rail footprint is mirrored out here so the LIST splitter's width budget can read it
            // without reaching into per-layer state.
            val store = container.sessionUiStore
            var liveRailReserve by remember { mutableStateOf(0f) }

            Row(Modifier.fillMaxSize().imePadding()) {
                // LEFT pane: the session list + New-request FAB anchored to this column.
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
                    // Rail is on the far RIGHT now (not adjacent to list), so the list splitter
                    // just resizes list vs chat directly — no redistribution with rail.
                    val upper = (maxW - liveRailReserve - minChat - splittersReserve).coerceAtLeast(200f)
                    val nw = listWidth + d
                    listWidth = nw.coerceIn(200f, upper)
                })

                // Everything right of the list splitter — header, chat/agent body, and the workflow
                // rail — cross-fades as ONE unit on session switch (emphasized 250ms dissolve).
                // CRITICAL: everything inside derives from the lambda's [currentSid], NEVER from
                // [selectedId]. The per-session ViewModels/state used to live OUTSIDE this Crossfade;
                // both fade layers then rendered the NEW session's data, degrading the dissolve into
                // a hard cut with one mixed-state frame (old title + new id, and the old rail
                // remeasured at the new session's width — a visible text rewrap).
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
                            // Discord-style: selecting this session acknowledges its current "your
                            // turn" point. Keyed on session.unreadEventId so it fires AFTER the
                            // session is loaded.
                            LaunchedEffect(s.session?.unreadEventId) {
                                val eid = s.session?.unreadEventId ?: return@LaunchedEffect
                                sVm.ackEvent(eid)
                            }
                            // Warm-return self-heal — PARITY with the phone SessionScreen.
                            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { sVm.refresh() }
                        }
                        // Rewind confirm trigger (set from the transcript long-press; shown by the shared
                        // SessionDetailOverlays). Per-session saveable.
                        var pendingRewindTurn by rememberSaveable(currentSid) { mutableStateOf<Int?>(null) }

                        // Per-layer rail geometry, seeded from the store once per layer (a single prefs
                        // read when the session opens — drag frames only touch plain MutableState).
                        // Coerce persisted widths up to [minRail]: older installs may have saved a
                        // narrower rail (the minimum used to be lower), and a raw value below the
                        // minimum diverges from the rendered [effectiveRailWidth] — the drag handler
                        // would then hide the rail on a drag that visually *expands* it, and the list
                        // splitter would budget too little space.
                        var railWidth by remember(currentSid) { mutableStateOf(store.get(currentSid).railWidth.coerceAtLeast(minRail)) }
                        var railHidden by remember(currentSid) { mutableStateOf(store.get(currentSid).railHidden) }
                        var expandedRuns by remember(currentSid) { mutableStateOf(store.get(currentSid).expandedRuns) }
                        // Persist geometry DEBOUNCED: every change restarts the effect, so the put
                        // (a prefs write + JSON encode) fires once the values settle, not on every
                        // drag frame (Gemini review). The dispose flush below covers the tail case —
                        // a change made <500ms before this layer leaves composition (e.g. drag the
                        // rail, then immediately switch sessions) would otherwise never be saved.
                        LaunchedEffect(currentSid, railWidth, railHidden, expandedRuns) {
                            delay(500)
                            store.put(currentSid, SessionUi(railHidden, railWidth, expandedRuns))
                        }
                        DisposableEffect(currentSid) {
                            onDispose { store.put(currentSid, SessionUi(railHidden, railWidth, expandedRuns)) }
                        }

                        val showRail = s.hasRuns && !railHidden
                        // Mirror the live rail footprint out for the list splitter's width budget.
                        // Keyed on railHidden (NOT showRail): hasRuns arrives async from the VM, and
                        // gating on it would leave a too-loose clamp window until the session loads.
                        // Matches the old synchronous `if (selectedId != null && !railHidden)` budget.
                        if (isLive) SideEffect { liveRailReserve = if (!railHidden) railWidth else 0f }

                        // MIDDLE pane = chat (chrome parity with the phone: history, fork, workflow
                        // toggle) + RIGHT workflow rail, in one per-session row so both fade together.
                        Row(Modifier.fillMaxSize()) {
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        // FadingText (parity with the phone SessionScreen / Home / Workflows
                                        // titles): overflow dissolves at the right edge instead of an ellipsis.
                                        // Full prompt, no take(40) — the fade handles overflow visually.
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

                                // Body: the picked agent's transcript when one is selected in the rail,
                                // else chat. No inner per-session animation needed — this whole detail
                                // row (header + body + rail) already lives inside the session Crossfade.
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
                            // RIGHT pane = workflow rail (only when the session has runs and is not
                            // hidden). Lives INSIDE the session's crossfade layer: on session switch it
                            // now fades with the rest of the layer instead of playing this slide against
                            // the incoming session's geometry. The slide still animates hide/show and the
                            // drag-below-minimum dismissal within a session.
                            //
                            // expand/shrink ride along with the slide so the CHAT column grows/shrinks in
                            // lockstep with the rail's motion. Without them AnimatedVisibility holds the
                            // rail's full layout width until the exit finishes (the vacated space sat
                            // empty, then the chat snapped wider) and claims it instantly on enter (the
                            // chat snapped narrower before the rail had slid in). Shrink/expand CLIP the
                            // content — it stays measured at full width — so the rail's text never
                            // rewraps mid-animation.
                            AnimatedVisibility(
                                visible = showRail,
                                enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = appSpatialSpec()) +
                                    expandHorizontally(animationSpec = appSpatialSpec()) + fadeIn(appEffectsSpec()),
                                exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = appSpatialSpec()) +
                                    shrinkHorizontally(animationSpec = appSpatialSpec()) + fadeOut(appEffectsSpec()),
                            ) {
                                Row(Modifier.fillMaxHeight()) {
                                    // Splitter on the LEFT side of the rail (between chat and rail).
                                    // Drag right (d>0) → rail shrinks (chat expands), so negate delta.
                                    PaneSplitter(onDelta = { d ->
                                        // Once the rail is exiting (drag crossed the auto-hide threshold,
                                        // or the session's runs vanished) the gesture is DONE: ignore the
                                        // rest of it. The splitter stays composed (and under the finger)
                                        // while the exit animation plays, and letting it keep writing
                                        // railWidth retargeted the exit spring's slide distance every
                                        // frame — the spring never settled, so the rail's (invisible)
                                        // layout box squatted on the vacated space until the finger
                                        // lifted, and dragging back left "revived" a zombie rail that
                                        // was already hidden. (!showRail, not railHidden: it also covers
                                        // the hasRuns-driven exit — Gemini review.)
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

                        // Toasts + shared chrome only for the live layer (an outgoing layer must not
                        // keep toasting or stack a second dialog host while it fades out).
                        if (isLive) {
                            val context = LocalContext.current
                            // Download SAVE effect.
                            LaunchedEffect(sVm) {
                                sVm.downloads.collect { eff ->
                                    when (eff) {
                                        is DownloadEffect.Started ->
                                            Toast.makeText(context, "Downloading ${eff.name}…", Toast.LENGTH_SHORT).show()
                                        is DownloadEffect.Ready -> {
                                            val ok = withContext(Dispatchers.IO) { saveToDownloads(context, eff.name, eff.bytes) }
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
                            // Shared chrome: rewind dialog + rewind toast — same wiring as the phone
                            // SessionScreen (single source of truth in SessionDetailOverlays).
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

/** MD3-expressive pane separator: a draggable gutter with a short rounded grip in the middle that
 *  doubles as the resize affordance. It looks slim ([SplitterWidthDp]) but grabs wide
 *  ([SplitterTouchTargetDp]): the [layout] modifier reports the slim width to the parent [Row] while
 *  measuring and placing the draggable area at the larger touch width, centred so it overflows
 *  symmetrically into the neighbouring panes. That kills the trade-off between "too thin to grab
 *  reliably" (the original 20dp) and "too wide a gap" (a fat visible lane). */
@Composable
private fun PaneSplitter(onDelta: (Float) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    // Keep the lambda fresh across recompositions so the DraggableState always calls
    // the latest onDelta (with current listWidth/railWidth captures). Without this,
    // a stale lambda would fight the finger — the splitter feels "sticky".
    val currentOnDelta by rememberUpdatedState(onDelta)
    Box(
        modifier
            .fillMaxHeight()
            .layout { measurable, constraints ->
                // Report the slim lane width to the Row, but measure/place the draggable area wider.
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
