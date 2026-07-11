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

/** Window width (dp) at/above which we use the classic 3-pane home (list │ rail │ session); below, the Material3 list-detail pane scaffold. */
private const val THREE_PANE_MIN_WIDTH_DP = 840

/** A short window (landscape phone) also gets the 3-pane. */
private const val THREE_PANE_MAX_HEIGHT_DP = 600

/** Pane bounds animation with NO overshoot — critical damping replaces the adaptive library's default (dampingRatio 0.8) which visibly rebounds on session open. IntRect(1,1,1,1) is the value the IntRect.VisibilityThreshold extension would return. */
private val PaneBoundsSpec: FiniteAnimationSpec<IntRect> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 380f,
    visibilityThreshold = IntRect(1, 1, 1, 1),
)

/** Pane enter/exit with NO slide overshoot — a single directional slide can't be right for both list and detail, so it's a cross-fade. */
private val PaneEnter: EnterTransition = fadeIn(tween(AppMotion.DurationMedium1, easing = AppMotion.Emphasized))
private val PaneExit: ExitTransition = fadeOut(tween(AppMotion.DurationMedium1, easing = AppMotion.Emphasized))

/** True when [config] should use the wide 3-pane home. Single source of truth, shared with AppNav so nav can avoid stacking a duplicate Home. */
fun isWideHome(config: Configuration): Boolean =
    config.screenWidthDp >= THREE_PANE_MIN_WIDTH_DP || config.screenHeightDp < THREE_PANE_MAX_HEIGHT_DP

/** Adaptive home entry point: picks the arrangement by window size. Both arrangements render the same SessionScreen/HomeScreen/WorkflowScreen so the detail UI never drifts. */
@Composable
fun HomeAdaptive(
    onNewRequest: () -> Unit,
    onOpenHistory: (String, Boolean) -> Unit,
    onOpenSettings: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAdoptPicker: () -> Unit = {},
    onOpenGlobalSettings: () -> Unit = {},
    initialSelectedId: String? = null,
    // Non-null when hosted by the SESSION route: invoked once the selection this route was opened for has been shown and then cleared — the host pops the duplicate nav entry (narrow leg of "two-layer Home").
    onDeselected: (() -> Unit)? = null,
) {
    val config = LocalConfiguration.current
    if (isWideHome(config)) {
        // Wide never needs [onDeselected]: AppNav normalizes the Session route away at render time regardless of selection.
        WideThreePaneHome(
            onNewRequest = onNewRequest,
            onOpenHistory = onOpenHistory,
            onOpenSettings = onOpenSettings,
            onOpenDiagnostics = onOpenDiagnostics,
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
            onOpenAdoptPicker = onOpenAdoptPicker,
            onOpenGlobalSettings = onOpenGlobalSettings,
            initialSelectedId = initialSelectedId,
            onDeselected = onDeselected,
        )
    }
}

/** Phone / medium-width: single pane on phone, 2-pane with native draggable handle on tablet/folded. Detail = SessionScreen; its workflow button opens WorkflowScreen as the scaffold's extra pane. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun NarrowScaffoldHome(
    onNewRequest: () -> Unit,
    onOpenHistory: (String, Boolean) -> Unit,
    onOpenSettings: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
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

    // Session-route host only (onDeselected != null): once a selection is shown and then cleared (scaffold back / predictive back), this scaffold renders exactly what the Home route's does — the host pops the duplicate nav entry so backing out of a pushed Session doesn't show its list THEN the real Home's list.
    // hadSelection guards the first composition (selectedSid starts null since the effect above applies it) and is rememberSaveable so a process-restored, already-deselected duplicate normalizes immediately.
    if (onDeselected != null) {
        // rememberUpdatedState so the effect (keyed only on selectedSid) invokes the LATEST host lambda even if recreated since launch.
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
