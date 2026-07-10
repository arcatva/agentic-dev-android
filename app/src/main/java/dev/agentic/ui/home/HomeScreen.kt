package dev.agentic.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import dev.agentic.data.net.Group
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentic.R
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.data.net.Session
import dev.agentic.data.net.Usage
import dev.agentic.data.net.UsageWindow
import dev.agentic.di.appContainer
import dev.agentic.domain.indicatorStatus
import dev.agentic.domain.indicatorAwaitingInput
import dev.agentic.domain.relativeAge
import dev.agentic.domain.resetIn
import dev.agentic.ui.FadingText
import dev.agentic.ui.appEffectsSpec
import dev.agentic.ui.appSpatialSpec
import dev.agentic.ui.components.AppTextField
import dev.agentic.ui.components.StatusIndicator
import dev.agentic.ui.components.cardFieldColors
import dev.agentic.ui.components.clearFocusOnTap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Home screen. Stateless: all state lives in [HomeViewModel]; this composable reads
 * [HomeUiState] and calls VM event handlers.
 *
 * Shows a single-pane session list (no list+detail split — detail opens via [onOpenSession]).
 * Card look mirrors [dev.agentic.ui.SessionListPane] from old Home.kt.
 *
 * VM creation note: [appContainer] is a @Composable helper; it is called in the composable
 * body so LocalContext is available. The [vm] nullable parameter allows injection for tests.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    onOpenSession: (String) -> Unit,
    onNewRequest: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenAdoptPicker: () -> Unit = {},
    onOpenGlobalSettings: () -> Unit = {},
    vm: HomeViewModel? = null,
) {
    val container = appContainer()
    val resolvedVm: HomeViewModel = vm ?: viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(container.sessionsRepo) }
        },
    )
    val s by resolvedVm.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var searching by rememberSaveable { mutableStateOf(false) }
    var moveSelectedOpen by rememberSaveable { mutableStateOf(false) }
    // FAB label collapses to icon-only as user scrolls down (MD3 Expressive pattern).
    val fabExpanded by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 50
        }
    }

    Scaffold(
        topBar = {
            HomeTopBar(
                selectionMode = s.selectionMode,
                selectedCount = s.selectedCount,
                totalCount = s.sessions.size,
                onCloseSelection = resolvedVm::clearSelection,
                onSelectAll = resolvedVm::selectAll,
                onDeleteSelected = resolvedVm::deleteSelected,
                onForkSelected = resolvedVm::forkSelected,
                onMoveSelected = { moveSelectedOpen = true },
                onLogout = container.authRepo::logout,
                searchQuery = s.searchQuery,
                searching = s.searching,
                searchResults = s.searchResults,
                onSearchQueryChange = resolvedVm::setSearchQuery,
                onOpenSession = onOpenSession,
                onSearchExpandedChange = { searching = it },
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenAdoptPicker = onOpenAdoptPicker,
                onOpenGlobalSettings = onOpenGlobalSettings,
            )
        },
        floatingActionButton = {
            // Hide the New-request FAB while picking sessions or while the search surface is open.
            if (!s.selectionMode && !searching) {
                ExtendedFloatingActionButton(
                    onClick = onNewRequest,
                    expanded = fabExpanded,
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("New request") },
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 8.dp),
                )
            }
        },
    ) { pad ->
        SessionListPane(
            state = s,
            selectedId = null,
            onOpen = onOpenSession,
            onDelete = resolvedVm::delete,
            onRefresh = resolvedVm::refresh,
            onToggleSelect = resolvedVm::toggleSelection,
            onLongPress = resolvedVm::toggleSelection,
            onClearSearch = { resolvedVm.setSearchQuery("") },
            onSelectGroupFilter = resolvedVm::selectGroupFilter,
            onRenameGroup = { id, name -> resolvedVm.updateGroup(id, name) },
            onDeleteGroup = resolvedVm::deleteGroup,
            onCreateGroup = { resolvedVm.createGroup(it) },
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                // Tap anywhere on the list (empty rows, gaps, the "no match" panel) to blur the
                // top-bar search field and drop the keyboard. Row taps/scroll/swipe still win.
                .clearFocusOnTap(),
            listState = listState,
        )
    }

    // ── Group picker sheet (multi-select move) ──────────────────────────────
    if (moveSelectedOpen) {
        GroupPickerSheet(
            groups = s.groups,
            onDismiss = { moveSelectedOpen = false },
            onGroupPicked = { groupId ->
                moveSelectedOpen = false
                s.selectedIds.forEach { resolvedVm.moveSessionToGroup(it, groupId) }
                resolvedVm.clearSelection()
            },
        )
    }

}

/**
 * Top bar for the session list. In its normal (non-selection) state it shows the app title plus a
 * logout action ([onLogout]) that opens a confirm dialog before signing out. Swaps to a contextual
 * action bar while in multi-select mode: a close (✕) navigation icon, an "N selected" title, a
 * "Select all" action and a delete action that opens the batch-delete confirm dialog. Owns both
 * confirm dialogs and the [BackHandler] that makes the system-back gesture leave selection mode
 * first, so both [HomeScreen] and [AdaptiveHome] get identical chrome from one place.
 *
 * Task 8: in non-selection mode the title row now hosts the session-list [SessionSearchBar] in
 * the middle, between the title and the actions slot. The search bar is hidden entirely while
 * selection mode is on (the contextual bar takes the full width); tapping a hit calls
 * [onOpenSession]. The search bar's `LaunchedEffect` drives [onSearchExpandedChange] so the
 * host can hide its FAB while the results panel is on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeTopBar(
    selectionMode: Boolean,
    selectedCount: Int,
    totalCount: Int,
    onCloseSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onForkSelected: () -> Unit,
    onMoveSelected: () -> Unit = {},
    onLogout: () -> Unit,
    searchQuery: String,
    searching: Boolean,
    searchResults: List<dev.agentic.data.net.SearchHit>,
    onSearchQueryChange: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onSearchExpandedChange: (Boolean) -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenAdoptPicker: () -> Unit = {},
    onOpenGlobalSettings: () -> Unit = {},
) {
    // While selecting, system/gesture back exits selection mode instead of leaving the screen.
    BackHandler(enabled = selectionMode, onBack = onCloseSelection)

    // Keyed on selectionMode so the confirm flag can't survive leaving selection mode and then
    // re-open the dialog unprompted when the user re-enters selection later.
    var confirm by remember(selectionMode) { mutableStateOf(false) }
    if (confirm && selectionMode) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = {
                Text(if (selectedCount == 1) "Delete session?" else "Delete $selectedCount sessions?")
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; onDeleteSelected() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            },
        )
    }

    // Logout confirm — independent of the delete `confirm` flag (which is keyed on selection
    // mode). Lives in the non-selection top bar. Mirrors the delete dialog's structure/styling.
    var confirmLogout by remember { mutableStateOf(false) }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
            title = { Text("Log out?") },
            text = { Text("You'll need to sign in again to continue.") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("Log out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("Cancel") }
            },
        )
    }

    if (selectionMode) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onCloseSelection) {
                    Icon(Icons.Rounded.Close, "exit selection")
                }
            },
            title = {
                Text(
                    "$selectedCount selected",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            actions = {
                val hasSelection = selectedCount > 0
                val allSelected = totalCount > 0 && selectedCount == totalCount
                // MD3E tonal icon buttons — same style as the non-selection settings/debug row.
                FilledTonalIconButton(
                    onClick = if (allSelected) onCloseSelection else onSelectAll,
                ) {
                    Icon(
                        if (allSelected) Icons.Rounded.Close else Icons.Rounded.Checklist,
                        contentDescription = if (allSelected) "deselect all" else "select all",
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = onMoveSelected, enabled = hasSelection) {
                    Icon(Icons.Rounded.Folder, contentDescription = "move to group")
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = onForkSelected, enabled = hasSelection) {
                    Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = "fork selected")
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = { confirm = true }, enabled = hasSelection) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "delete selected")
                }
                Spacer(Modifier.width(4.dp))
            },
        )
    } else {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // MD3E motion: the brand title gracefully yields its width to the search field the
                    // moment the user starts typing (expressive spatial expand/shrink), then returns
                    // when the query is cleared.
                    AnimatedVisibility(
                        visible = searchQuery.isEmpty(),
                        enter = expandHorizontally(appSpatialSpec(), expandFrom = Alignment.Start) + fadeIn(appEffectsSpec()),
                        exit = shrinkHorizontally(appSpatialSpec(), shrinkTowards = Alignment.Start) + fadeOut(appEffectsSpec()),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Image (not Icon): the brand mark is multi-color gradient art; Icon
                            // would tint it to a single color.
                            Image(
                                painterResource(R.drawable.ic_brand_logo),
                                contentDescription = "agentic-dev",
                                modifier = Modifier.size(44.dp),
                            )
                            Spacer(Modifier.width(16.dp))
                        }
                    }
                    SessionSearchBar(
                        query = searchQuery,
                        onQueryChange = onSearchQueryChange,
                        searching = searching,
                        results = searchResults,
                        selectionMode = false,
                        onOpen = onOpenSession,
                        onExpandedChange = onSearchExpandedChange,
                        // end gap so the pill never touches the action buttons (their tonal circles
                        // extend a bit left of their icons).
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                }
            },
            actions = {
                // Compact widths (portrait phone / folded) can't fit the title + search + 3 icons, so
                // the search box gets squeezed to a stub. Collapse the actions into a single overflow
                // menu there to give the search field the width; on wider screens show them inline.
                // MD3E: actions are expressive tonal icon buttons (filled tonal containers). Compact
                // widths (portrait phone / folded) can't fit the title + search + 3 buttons, so there
                // they collapse into a single overflow ⋮ button + menu, freeing the search field's width.
                val compact = LocalConfiguration.current.screenWidthDp < 600
                if (compact) {
                    var menuOpen by remember { mutableStateOf(false) }
                    FilledTonalIconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                    }
                    Spacer(Modifier.width(4.dp))
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Global settings") },
                            leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                            onClick = { menuOpen = false; onOpenGlobalSettings() },
                        )
                        DropdownMenuItem(
                            text = { Text("Diagnostics") },
                            leadingIcon = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
                            onClick = { menuOpen = false; onOpenDiagnostics() },
                        )
                        DropdownMenuItem(
                            text = { Text("Adopt Claude Code session") },
                            leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                            onClick = { menuOpen = false; onOpenAdoptPicker() },
                        )
                        DropdownMenuItem(
                            text = { Text("Log out") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
                            onClick = { menuOpen = false; confirmLogout = true },
                        )
                    }
                } else {
                    FilledTonalIconButton(onClick = onOpenGlobalSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Global settings")
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(onClick = onOpenDiagnostics) {
                        Icon(Icons.Rounded.BugReport, contentDescription = "Diagnostics")
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(onClick = onOpenAdoptPicker) {
                        Icon(Icons.Rounded.Download, contentDescription = "Adopt Claude Code session")
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(onClick = { confirmLogout = true }) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Log out")
                    }
                    Spacer(Modifier.width(4.dp))
                }
            },
        )
    }
}

/**
 * Scaffold-free body of the session list: server-unreachable banner, usage meters and the
 * LazyColumn of session cards with swipe-to-delete + delete-confirm dialog.
 *
 * Stateless: reads [HomeUiState] and reports events via [onOpen] / [onDelete]. Reusable in both
 * the single-pane [HomeScreen] and the wide 3-pane layout, where [selectedId] highlights the row
 * whose `session.id == selectedId`.
 *
 * Task 8: the search surface lives in [HomeTopBar] now (next to the title). When the user has
 * typed enough text to trigger a backend search ([HomeUiState.searchQuery] has length ≥ 2), the
 * list renders [HomeUiState.searchResults] instead of the full session list — search-empty
 * shows the "No sessions match" empty-state inside the bar's expanded panel, not here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SessionListPane(
    state: HomeUiState,
    selectedId: String?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSelectGroupFilter: (String?) -> Unit = {},
    onRenameGroup: (String, String) -> Unit = { _, _ -> },
    onDeleteGroup: (String) -> Unit = {},
    onCreateGroup: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
) {
    // ui-4: tick every 30 s so relative timestamps ("5m ago") stay fresh without recomposing.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    // Task 8: when a content search is active, render the search hits; otherwise the full session
    // list. The 2-char threshold mirrors the backend's `length >= 2` filter — the expanded panel
    // and the actual search request only fire once the user has typed enough for a useful query.
    val list: List<Session> = if (state.searchQuery.length >= 2) state.searchResults.map { it.session }
                              else state.sessions

    // Filter sessions by the selected group filter (null = "All").
    val displayed = remember(list, state.selectedGroupFilter) {
        when (state.selectedGroupFilter) {
            null -> list
            "__uncategorized__" -> list.filter { it.groupId == null }
            else -> list.filter { it.groupId == state.selectedGroupFilter }
        }
    }

    // Keep the LazyColumn anchored on the open session in 3-pane mode. When the user sends a
    // message in the chat pane, the server usually reorders this session to the top — without
    // this LaunchedEffect the list stays where the user last scrolled it, so the highlighted
    // row drifts off-screen. We only scroll when the active row is currently NOT visible AND
    // the user isn't actively dragging (the same `isScrollInProgress` check the project uses in
    // TranscriptScrollbar), so this never fights a mid-scroll gesture, and we no-op when the
    // selection is null (single-pane) or hasn't been loaded yet.
    LaunchedEffect(selectedId, list) {
        val idx = indexOfSelected(list, selectedId)
        if (idx < 0) return@LaunchedEffect
        if (listState.isScrollInProgress) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == idx }
        if (!visible) {
            listState.animateScrollToItem(idx)
        }
    }

    // Single-pane mode: when the top session changes (server re-sort after a reply),
    // scroll to the top so the freshly-bumped session is visible. Only fires when the
    // user is already near the top (index 1-2) — never hijacks a deep-browse scroll.
    // Keys on isScrollInProgress so a re-sort that lands mid-drag isn't lost: the
    // effect re-fires when the drag ends.
    val topId = list.firstOrNull()?.id
    var lastScrolledTopId by remember { mutableStateOf(topId) }
    LaunchedEffect(topId, listState.isScrollInProgress) {
        if (topId == null || listState.isScrollInProgress) return@LaunchedEffect
        if (topId != lastScrolledTopId) {
            if (listState.firstVisibleItemIndex in 1..2) {
                listState.animateScrollToItem(0)
            }
            lastScrolledTopId = topId
        }
    }

    Box(modifier) {
        when {
            state.loading -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    LoadingIndicator()
                }
            }
            else -> {
                val isSearchActive = state.searchQuery.length >= 2
                if (isSearchActive) {
                    // Search mode: replace the list body with a results panel. This avoids BOTH the
                    // push-down artifact of the old inline column AND the overlay-occlusion of the
                    // Popup approach — the list region IS the results region.
                    SearchResultsPanel(
                        results = state.searchResults,
                        query = state.searchQuery,
                        onOpen = onOpen,
                        onClearQuery = onClearSearch,
                        lastSearchedQuery = state.lastSearchedQuery,
                    )
                } else {
                Column(Modifier.fillMaxSize()) {
                    // PR-9: server-unreachable banner (first-load failure only).
                    if (state.serverUnreachable) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Warning, null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                "⚠ Can't reach the server — retrying…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                    // Usage meters sit above the search box. vertical=5.dp keeps the spacing
                    // consistent with the session cards' 5.dp top padding.
                    state.usage?.let { UsageMeters(it, Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) }
                    // ── M3 Button Groups: filter row ────────────────────────────────
                    if (state.groups.isNotEmpty()) {
                        GroupFilterRow(
                            groups = state.groups,
                            selectedFilter = state.selectedGroupFilter,
                            onSelectFilter = onSelectGroupFilter,
                            onRenameGroup = onRenameGroup,
                            onDeleteGroup = onDeleteGroup,
                            onCreateGroup = onCreateGroup,
                        )
                    }
                    // Pull-to-refresh: re-fetch the session list + usage meters on demand (restored
                    // from the pre-MVVM Home.kt, which the MVVM rewrite had dropped).
                    PullToRefreshBox(
                        isRefreshing = state.refreshing,
                        onRefresh = onRefresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(bottom = 104.dp),
                    ) {
                        items(displayed, key = { it.id }) { session ->
                            SessionItem(
                                session = session,
                                now = now,
                                state = state,
                                selectedId = selectedId,
                                onOpen = onOpen,
                                onDelete = onDelete,
                                onToggleSelect = onToggleSelect,
                                onLongPress = onLongPress,
                                modifier = Modifier.animateItem(),
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

/**
 * M3 Button Groups filter row: horizontally scrollable connected `ButtonGroup`
 * with `ToggleButton` per group + an "All" button + a trailing "⋮" menu button.
 * The "⋮" dropdown provides rename/delete per group and a create-new-group action.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GroupFilterRow(
    groups: List<Group>,
    selectedFilter: String?,
    onSelectFilter: (String?) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onCreateGroup: (String) -> Unit,
) {
    // ── New-group dialog ──────────────────────────────────────────────────
    var createGroupOpen by remember { mutableStateOf(false) }
    if (createGroupOpen) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createGroupOpen = false },
            title = { Text("New group") },
            text = {
                // Shared field family (AppTextField + filled card colors) — same as every other
                // dialog input (e.g. the template-variable dialog on New request).
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = "Group name",
                    shape = MaterialTheme.shapes.small,
                    colors = cardFieldColors(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { createGroupOpen = false; if (name.isNotBlank()) onCreateGroup(name.trim()) },
                    enabled = name.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { createGroupOpen = false }) { Text("Cancel") } },
        )
    }

    // ── Per-group rename / delete state ───────────────────────────────────
    var renameTarget by remember { mutableStateOf<Group?>(null) }
    var deleteTarget by remember { mutableStateOf<Group?>(null) }
    var groupMenuOpen by remember { mutableStateOf(false) }
    var manageGroupsOpen by remember { mutableStateOf(false) }

    // Rename dialog
    val rt = renameTarget
    if (rt != null) {
        var text by remember(rt.id) { mutableStateOf(rt.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename group") },
            text = {
                AppTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = "Group name",
                    shape = MaterialTheme.shapes.small,
                    colors = cardFieldColors(),
                )
            },
            confirmButton = {
                TextButton(onClick = { renameTarget = null; if (text.isNotBlank()) onRenameGroup(rt.id, text.trim()) }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    // Delete confirm dialog
    val dt = deleteTarget
    if (dt != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Delete group?") },
            text = { Text("Sessions in \"${dt.name}\" will become uncategorized.") },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; onDeleteGroup(dt.id) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    // Manage groups sheet: each group row has rename + delete buttons.
    if (manageGroupsOpen) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { manageGroupsOpen = false },
            sheetState = sheetState,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    "Manage groups",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                groups.forEach { group ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(group.name, Modifier.weight(1f))
                        IconButton(onClick = {
                            manageGroupsOpen = false
                            renameTarget = group
                        }) {
                            Icon(Icons.Rounded.Edit, "Rename ${group.name}", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = {
                            manageGroupsOpen = false
                            deleteTarget = group
                        }) {
                            Icon(Icons.Rounded.DeleteOutline, "Delete ${group.name}", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    // One per button (All + N groups + trailing ⋮)
    val interactionSources = remember(groups.size) { List(groups.size + 2) { MutableInteractionSource() } }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ButtonGroup(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            // "All" — leading shape
            ToggleButton(
                checked = selectedFilter == null,
                onCheckedChange = { if (selectedFilter != null) onSelectFilter(null) },
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                interactionSource = interactionSources[0],
                modifier = Modifier.animateWidth(interactionSources[0]),
            ) { Text("All", maxLines = 1, softWrap = false) }

            groups.forEachIndexed { i, group ->
                val src = interactionSources[i + 1]
                ToggleButton(
                    checked = selectedFilter == group.id,
                    onCheckedChange = { if (it) onSelectFilter(group.id) },
                    shapes = when {
                        groups.isEmpty() -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        i == groups.lastIndex -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    interactionSource = src,
                    modifier = Modifier.animateWidth(src),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (group.icon != null) {
                            Text(group.icon, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(group.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
                    }
                }
            }

            // Trailing ⋮ — inside the scrollable ButtonGroup, always at the far right.
            // Users naturally scroll to find it when they need group management.
            val lastSrc = interactionSources[interactionSources.lastIndex]
            ToggleButton(
                checked = false,
                onCheckedChange = { groupMenuOpen = true },
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                interactionSource = lastSrc,
                modifier = Modifier.animateWidth(lastSrc),
            ) {
                Icon(Icons.Rounded.MoreVert, "Group actions", modifier = Modifier.size(18.dp))
            }
        }

        // Dropdown: pick a group to rename/delete, or create a new one.
        // Placed OUTSIDE ButtonGroup so the popup positions relative to the Row, not the scrollable group.
        DropdownMenu(expanded = groupMenuOpen, onDismissRequest = { groupMenuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Manage groups…") },
                leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                onClick = { groupMenuOpen = false; manageGroupsOpen = true },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("New group") },
                leadingIcon = { Icon(Icons.Rounded.Add, null) },
                onClick = { groupMenuOpen = false; createGroupOpen = true },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupPickerSheet(
    groups: List<Group>,
    onDismiss: () -> Unit,
    onGroupPicked: (String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item {
                Text(
                    "Move session to",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                // "Uncategorized" option (groupId = null).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onGroupPicked(null) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.FolderOff, null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Uncategorized")
                }
            }
            item {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            items(groups, key = { it.id }) { group ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onGroupPicked(group.id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(group.name)
                }
            }
        }
    }
}

/**
 * One session card. Shared by the normal list (wrapped in swipe-to-delete) and the multi-select
 * list. [openHighlight] tints the row that matches the open session in the wide layout; [checked]
 * tints a ticked row and, with [inSelectionMode], swaps the status dot for a check icon. Click and
 * long-press are reported via [onClick]/[onLongClick] so the caller decides open-vs-toggle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionRow(
    session: Session,
    now: Long,
    openHighlight: Boolean,
    inSelectionMode: Boolean,
    checked: Boolean,
    unread: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repos = if (session.repos.isNotEmpty()) session.repos.joinToString(", ") else "skill-only"
    // In selection mode the tint means "ticked" (the open-session highlight would otherwise look
    // identical to a selected row); outside it, it means "this is the open session" (wide layout).
    val highlighted = if (inSelectionMode) checked else openHighlight
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        // combinedClickable lives on the INNER row (not the Card's outer modifier) so the tap/
        // long-press indication is clipped to the card's rounded shape instead of overflowing as a
        // rectangle — while the Card keeps its elevation shadow.
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                // Smaller start inset so the title + indicator sit a bit further left; the indicator
                // then reads as roughly centred in the gap between the card edge and the text.
                .padding(start = 8.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inSelectionMode) {
                Icon(
                    if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (checked) "selected" else "not selected",
                    tint = if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
            } else {
                // Always reserve the indicator slot (fixed footprint) so the title + repo never shift
                // when it clears — idle paints nothing (done shows its check). A watchdog cap (wall/idle
                // timeout) isn't a failure, so render it like done, not the red error icon.
                StatusIndicator(
                    status = indicatorStatus(session),
                    awaitingInput = indicatorAwaitingInput(session),
                    size = 16.dp,
                    unread = unread && !openHighlight,
                    // Same gate as the steady dot: only flash the transient completion check for a
                    // completion the user has NOT read yet. Without this, the resume catch-up
                    // (frozen uiState + retained composition while backgrounded) replays an old
                    // running→idle flip and flashes a check for a session already opened and acked.
                    flashOnComplete = unread && !openHighlight,
                )
                Spacer(Modifier.width(6.dp))
            }
            Column(Modifier.weight(1f)) {
                FadingText(
                    session.prompt.ifBlank { "(no prompt)" },
                    style = MaterialTheme.typography.bodyLarge,
                )
                // Short session id right under the title — the same 8-char/labelSmall/
                // onSurfaceVariant pair the session page shows in its top bar, so a session can
                // be identified (and @-mentioned) by id straight from the list.
                FadingText(
                    session.id.take(8),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FadingText(
                    "$repos · ${relativeAge(session.createdAt, now)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One session in the list, with swipe-to-delete wrapping (normal mode) or bare (selection mode).
 * Extracted so both the grouped and flat list paths render the same row.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SessionItem(
    session: Session,
    now: Long,
    state: HomeUiState,
    selectedId: String?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val checked = session.id in state.selectedIds

    if (state.selectionMode) {
        SessionRow(
            session = session,
            now = now,
            openHighlight = session.id == selectedId,
            inSelectionMode = true,
            checked = checked,
            unread = session.id in state.unreadIds,
            onClick = { onToggleSelect(session.id) },
            onLongClick = { onToggleSelect(session.id) },
            modifier = modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
        return
    }

    val scope = rememberCoroutineScope()
    val dismiss = rememberSwipeToDismissBoxState(
        positionalThreshold = { it * 0.55f },
    )
    var confirm by remember(session.id) { mutableStateOf(false) }

    LaunchedEffect(dismiss.currentValue) {
        if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) {
            confirm = true
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = {
                confirm = false
                scope.launch { dismiss.reset() }
            },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Delete session?") },
            text = {
                Text(
                    session.prompt.ifBlank { "(no prompt)" },
                    maxLines = 2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch { dismiss.reset() }
                    onDelete(session.id)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch { dismiss.reset() }
                }) { Text("Cancel") }
            },
        )
    }

    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    "delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        SessionRow(
            session = session,
            now = now,
            openHighlight = session.id == selectedId,
            inSelectionMode = false,
            checked = false,
            unread = session.id in state.unreadIds,
            onClick = { onOpen(session.id) },
            onLongClick = { onLongPress(session.id) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}


@Composable
private fun UsageMeters(u: Usage, modifier: Modifier = Modifier) {
    // Tap either meter to flip BOTH labels between the window name (5h/7d) and the time left until
    // that window resets (3h29m / 3d21h). One shared toggle so they switch together.
    var showReset by remember { mutableStateOf(false) }
    val toggle = { showReset = !showReset }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        u.five_hour?.let { Meter(if (showReset) resetIn(it.resets_at) else "5h", it, toggle, Modifier.weight(1f)) }
        u.seven_day?.let { Meter(if (showReset) resetIn(it.resets_at) else "7d", it, toggle, Modifier.weight(1f)) }
    }
}

@Composable
private fun Meter(label: String, window: UsageWindow, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val pct = window.utilization   // 0..100, a float from the API (e.g. 37.0)
    val color = when {
        pct >= 90.0 -> MaterialTheme.colorScheme.error      // near the cap — semantic red
        pct >= 70.0 -> MaterialTheme.colorScheme.tertiary   // filling up — teal accent (on-brand)
        else -> MaterialTheme.colorScheme.primary           // healthy — brand blue
    }
    Column(modifier.clickable(onClick = onToggle)) {
        Row {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text("${pct.roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = color)
        }
        LinearProgressIndicator(
            progress = { (pct / 100.0).toFloat().coerceIn(0f, 1f) },
            color = color,
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        )
    }
}

/**
 * Returns the index of the session whose [Session.id] matches [selectedId] inside [sessions], or
 * -1 when there is no selection or the selected session is not (yet) in the list. Used by
 * [SessionListPane] to decide where to keep the LazyColumn anchored when the open session moves
 * (e.g. a new message bumps it to the top, or the user opens a different session in 3-pane mode).
 * Pure so it can be unit-tested without Compose.
 */
internal fun indexOfSelected(sessions: List<Session>, selectedId: String?): Int {
    if (selectedId == null) return -1
    return sessions.indexOfFirst { it.id == selectedId }
}
