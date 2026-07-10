package dev.agentic.ui.home

import android.content.res.Configuration
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.di.appContainer
import dev.agentic.ui.AppMotion
import dev.agentic.ui.session.SessionScreen
import dev.agentic.ui.session.SessionViewModel
import dev.agentic.ui.workflow.WorkflowScreen
import dev.agentic.ui.workflow.WorkflowViewModel
import kotlinx.coroutines.launch

/** Window width (dp) at/above which we use the classic resizable 3-pane home (list │ workflow rail │
 *  session). Below it we use the Material3 list-detail pane scaffold (single pane on a phone, 2-pane
 *  with a native drag handle on a medium tablet / folded device). */
private const val THREE_PANE_MIN_WIDTH_DP = 840

/** A short window (landscape phone) also gets the 3-pane: it has the width for it even below 840dp. */
private const val THREE_PANE_MAX_HEIGHT_DP = 600

/** Pane open/close bounds animation with NO overshoot. The adaptive library's default
 *  (`PaneMotionDefaults.AnimationSpec` = spring dampingRatio 0.8) visibly "rebounds" when a session
 *  opens into the detail pane; critical damping (1.0) keeps the same timing (stiffness 380) without
 *  the bounce. */
private val PaneBoundsSpec: FiniteAnimationSpec<IntRect> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 380f,
    // Terminate as soon as the integer bounds are within 1px instead of running extra background
    // frames at the default float threshold. (There's no IntRect.VisibilityThreshold in this version,
    // so use the literal IntRect(1,1,1,1) — the value that extension would return.)
    visibilityThreshold = IntRect(1, 1, 1, 1),
)

/** Pane enter/exit with NO slide overshoot. The adaptive default enter SLIDES via
 *  `PaneMotionDefaults.OffsetAnimationSpec` (also spring dampingRatio 0.8) — that slide is the visible
 *  "rebound" when a session opens. A plain emphasized-tween cross-fade replaces the bouncy slide (a
 *  single fixed directional slide can't be correct for both the list and detail panes, so fade it is). */
private val PaneEnter: EnterTransition = fadeIn(tween(AppMotion.DurationMedium1, easing = AppMotion.Emphasized))
private val PaneExit: ExitTransition = fadeOut(tween(AppMotion.DurationMedium1, easing = AppMotion.Emphasized))

/**
 * True when [config] should use the wide 3-pane [WideThreePaneHome] (≥ [THREE_PANE_MIN_WIDTH_DP]
 * wide, OR a short landscape-phone window < [THREE_PANE_MAX_HEIGHT_DP] tall) rather than the narrow
 * [NavigableListDetailPaneScaffold]. The single source of truth for the arrangement decision — shared
 * with [dev.agentic.ui.nav.AppNav] so navigation can avoid stacking a second Home when wide.
 */
fun isWideHome(config: Configuration): Boolean =
    config.screenWidthDp >= THREE_PANE_MIN_WIDTH_DP || config.screenHeightDp < THREE_PANE_MAX_HEIGHT_DP

/**
 * The adaptive home entry point. One job: pick the arrangement by window size. Both arrangements
 * render the SAME `SessionScreen`/`HomeScreen`/`WorkflowScreen` content, so the detail UI never drifts
 * between them.
 *   - wide (≥ [THREE_PANE_MIN_WIDTH_DP]) OR short (height < [THREE_PANE_MAX_HEIGHT_DP], i.e. a
 *     landscape phone) → [WideThreePaneHome]: list │ rail │ session, draggable splitters (the
 *     classic layout). This is the exact rule the pre-scaffold layout used (`maxWidth >= 840.dp ||
 *     maxHeight < 600.dp`), so an unfolded foldable lands here again instead of falling to 2 panes.
 *   - otherwise → [NarrowScaffoldHome]: Material3 [NavigableListDetailPaneScaffold].
 */
@Composable
fun HomeAdaptive(
    onNewRequest: () -> Unit,
    onOpenHistory: (String, Boolean) -> Unit,
    onOpenSettings: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenProviders: () -> Unit = {},
    onOpenAdoptPicker: () -> Unit = {},
    onOpenGlobalSettings: () -> Unit = {},
    initialSelectedId: String? = null,
    // Non-null when hosted by the SESSION route: invoked once the selection this route was opened
    // for has been shown and then cleared — at that point the narrow scaffold is an exact duplicate
    // of the Home route's, and the host pops the duplicate entry (narrow leg of "two-layer Home").
    onDeselected: (() -> Unit)? = null,
) {
    val config = LocalConfiguration.current
    if (isWideHome(config)) {
        // Wide never needs [onDeselected]: AppNav normalizes the Session route away at render
        // time regardless of selection (the 3-pane Home is a duplicate even while selected).
        WideThreePaneHome(
            onNewRequest = onNewRequest,
            onOpenHistory = onOpenHistory,
            onOpenSettings = onOpenSettings,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenProviders = onOpenProviders,
            onOpenAdoptPicker = onOpenAdoptPicker,
            onOpenGlobalSettings = onOpenGlobalSettings,
            initialSelectedId = initialSelectedId,
        )
    } else {
        NarrowScaffoldHome(
            onNewRequest = onNewRequest,
            onOpenHistory = onOpenHistory,
            onOpenSettings = onOpenSettings,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenProviders = onOpenProviders,
            onOpenAdoptPicker = onOpenAdoptPicker,
            onOpenGlobalSettings = onOpenGlobalSettings,
            initialSelectedId = initialSelectedId,
            onDeselected = onDeselected,
        )
    }
}

/**
 * Phone / medium-width arrangement: a [NavigableListDetailPaneScaffold] — single pane with built-in
 * Back on a phone, 2-pane (list+detail) with a native draggable handle on a medium tablet/folded
 * device. The detail pane is [SessionScreen]; its workflow button opens [WorkflowScreen] as the
 * scaffold's extra pane. The navigator's content key is the single source of truth for the selection.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun NarrowScaffoldHome(
    onNewRequest: () -> Unit,
    onOpenHistory: (String, Boolean) -> Unit,
    onOpenSettings: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenAdoptPicker: () -> Unit,
    onOpenGlobalSettings: () -> Unit = {},
    initialSelectedId: String?,
    onDeselected: (() -> Unit)? = null,
) {
    val container = appContainer()
    val homeVm: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(container.sessionsRepo) }
        },
    )
    val homeState by homeVm.uiState.collectAsStateWithLifecycle()
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val selectedSid = navigator.currentDestination?.contentKey
    // Per-session move-to-group: non-null sid opens GroupPickerSheet.
    var moveTargetSid by rememberSaveable { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(initialSelectedId) {
        val id = initialSelectedId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (navigator.currentDestination?.contentKey != id) {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id)
        }
    }

    // Session-route host only (onDeselected != null): once a selection has been shown and is then
    // cleared (scaffold back / predictive back landing on the list), this scaffold renders exactly
    // what the Home route's does — tell the host so it can pop the duplicate nav entry. Without
    // this, backing out of a pushed Session shows ITS list first and the real Home's list second:
    // the narrow "back goes home, then home again" bug.
    //
    // hadSelection guards the first composition (selection is applied by the effect above, so
    // selectedSid starts null — that must NOT count as "deselected") and is rememberSaveable so a
    // process-restored, already-deselected duplicate normalizes immediately instead of lingering.
    if (onDeselected != null) {
        // rememberUpdatedState so the effect (keyed only on selectedSid) always invokes the
        // LATEST host lambda even if it was recreated by recomposition since launch.
        val currentOnDeselected by rememberUpdatedState(onDeselected)
        var hadSelection by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(selectedSid) {
            if (selectedSid != null) hadSelection = true
            else if (hadSelection) currentOnDeselected()
        }
    }

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        paneExpansionDragHandle = { state ->
            val interaction = remember { MutableInteractionSource() }
            VerticalDragHandle(
                modifier = Modifier.paneExpansionDraggable(
                    state,
                    LocalMinimumInteractiveComponentSize.current,
                    interaction,
                ),
                interactionSource = interaction,
            )
        },
        listPane = {
            AnimatedPane(enterTransition = PaneEnter, exitTransition = PaneExit, boundsAnimationSpec = PaneBoundsSpec) {
                HomeScreen(
                    onOpenSession = { id -> scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) } },
                    onNewRequest = onNewRequest,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onOpenProviders = onOpenProviders,
                    onOpenAdoptPicker = onOpenAdoptPicker,
                    onOpenGlobalSettings = onOpenGlobalSettings,
                    vm = homeVm,
                )
            }
        },
        detailPane = {
            AnimatedPane(enterTransition = PaneEnter, exitTransition = PaneExit, boundsAnimationSpec = PaneBoundsSpec) {
                if (selectedSid == null) {
                    PanePlaceholder("Select a session")
                } else {
                    val sVm: SessionViewModel = viewModel(
                        key = "s:$selectedSid",
                        factory = viewModelFactory {
                            initializer {
                                SessionViewModel(
                                    container.sessionsRepo,
                                    container.workflowsRepo,
                                    container.filesRepo,
                                    container.api,
                                    selectedSid,
                                )
                            }
                        },
                    )
                    SessionScreen(
                        onBack = {
                            keyboard?.hide()
                            focusManager.clearFocus(force = true)
                            scope.launch { navigator.navigateBack() }
                        },
                        onOpenWorkflows = { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Extra, selectedSid) } },
                        onOpenHistory = { live -> onOpenHistory(selectedSid, live) },
                        onFork = sVm::fork,
                        onForked = { id -> scope.launch { homeVm.refresh(); navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) } },
                        onOpenParent = { id -> scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) } },
                        onOpenSettings = { onOpenSettings(selectedSid) },
                        onMoveToGroup = { moveTargetSid = selectedSid },
                        vm = sVm,
                    )
                }
            }
        },
        extraPane = {
            AnimatedPane(enterTransition = PaneEnter, exitTransition = PaneExit, boundsAnimationSpec = PaneBoundsSpec) {
                if (selectedSid == null) {
                    PanePlaceholder("No session selected")
                } else {
                    val wVm: WorkflowViewModel = viewModel(
                        key = "w:$selectedSid",
                        factory = viewModelFactory {
                            initializer { WorkflowViewModel(container.workflowsRepo, selectedSid) }
                        },
                    )
                    WorkflowScreen(onBack = { scope.launch { navigator.navigateBack() } }, vm = wVm)
                }
            }
        },
    )

    // ── Group picker sheet (per-session move from session chrome) ──────────
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

@Composable
private fun PanePlaceholder(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
