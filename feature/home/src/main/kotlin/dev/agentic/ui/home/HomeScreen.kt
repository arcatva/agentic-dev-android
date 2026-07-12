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
import dev.agentic.core.designsystem.R
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

/** Home screen — stateless; reads HomeUiState, calls VM handlers. Single-pane session list (no list+detail split — detail opens via onOpenSession). [vm] is nullable for test injection. */
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
    // FAB collapses to icon-only when scrolled (MD3 Expressive).
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
                // Tap anywhere (empty rows, gaps, "no match") blurs the search field + drops the keyboard. Row taps/scroll/swipe still win.
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

/** Top bar: non-selection = app title + logout; multi-select = contextual bar (close, "N selected", select-all, delete). Owns both confirm dialogs + the BackHandler that makes system-back exit selection first. */
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

    // Keyed on selectionMode so the confirm flag can't survive leaving selection mode and re-open the dialog unprompted on re-entry.
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

    // Logout confirm — independent of delete's selectionMode-keyed flag.
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
                // MD3E tonal icon buttons (parity with non-selection settings/debug row).
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
                    // MD3E: brand title gracefully yields its width to the search field while typing (spatial expand/shrink).
                    AnimatedVisibility(
                        visible = searchQuery.isEmpty(),
                        enter = expandHorizontally(appSpatialSpec(), expandFrom = Alignment.Start) + fadeIn(appEffectsSpec()),
                        exit = shrinkHorizontally(appSpatialSpec(), shrinkTowards = Alignment.Start) + fadeOut(appEffectsSpec()),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Image (not Icon): brand mark is multi-color gradient art; Icon would tint it to a single color.
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
                        // end gap so the pill never touches the action buttons (their tonal circles extend left of their icons).
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                }
            },
            actions = {
                // Compact widths (portrait phone / folded) can't fit title + search + 3 icons — collapse into a single ⋮ overflow menu so the search field has the width.
                val compact = LocalConfiguration.current.screenWidthDp < 600
                if (compact) {
                    var menuOpen by remember { mutableStateOf(false) }
                    FilledTonalIconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                    }
                    Spacer(Modifier.width(4.dp))
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
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
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
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

/** Scaffold-free list body: unreachable banner, usage meters, LazyColumn of cards with swipe-to-delete + confirm. Stateless; reusable in both single-pane HomeScreen and the wide 3-pane (where [selectedId] highlights the matching row). Search surface lives in HomeTopBar; when length ≥ 2 the list renders searchResults. */
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
    // Tick every 30 s so relative timestamps ("5m ago") stay fresh without recomposing.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    // 2-char threshold mirrors the backend's `length >= 2` filter.
    val list: List<Session> = if (state.searchQuery.length >= 2) state.searchResults.map { it.session }
                              else state.sessions

    // Filter sessions by the selected group filter (null = "All", "__uncategorized__" = groupId == null).
    val displayed = remember(list, state.selectedGroupFilter) {
        when (state.selectedGroupFilter) {
            null -> list
            "__uncategorized__" -> list.filter { it.groupId == null }
            else -> list.filter { it.groupId == state.selectedGroupFilter }
        }
    }

    // Keep the LazyColumn anchored on the open session in 3-pane mode — server reorders it to the top after each reply.
    // No-op during mid-scroll (isScrollInProgress) so it never fights a gesture.
    LaunchedEffect(selectedId, list) {
        val idx = indexOfSelected(list, selectedId)
        if (idx < 0) return@LaunchedEffect
        if (listState.isScrollInProgress) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == idx }
        if (!visible) {
            listState.animateScrollToItem(idx)
        }
    }

    // Single-pane: when the top session changes (server re-sort after a reply), scroll to the top so the freshly-bumped one is visible. Only fires when the user is already near the top (index 1-2) — never hijacks a deep-browse scroll.
    // Keys on isScrollInProgress so a re-sort that lands mid-drag isn't lost: the effect re-fires when the drag ends.
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
                    // Search mode: replace list body with a results panel — list region IS the results region (no push-down, no overlay-occlusion).
                    SearchResultsPanel(
                        results = state.searchResults,
                        query = state.searchQuery,
                        onOpen = onOpen,
                        onClearQuery = onClearSearch,
                        lastSearchedQuery = state.lastSearchedQuery,
                    )
                } else {
                Column(Modifier.fillMaxSize()) {
                    // First-load server-unreachable banner.
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
                    // Usage meters sit above the search box. vertical=5.dp matches session cards' top padding.
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
                    // Pull-to-refresh: re-fetch sessions + usage on demand.
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

/** M3 Button Groups filter row: horizontally scrollable connected ButtonGroup with ToggleButton per group + an "All" button + trailing ⋮ menu. Dropdown offers rename/delete per group + create-new. */
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
                // AppTextField + cardFieldColors — shared dialog-input family.
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

    // One per button (All + N groups + trailing ⋮).
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
                        val icon = group.icon
                        if (icon != null) {
                            Text(icon, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(group.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
                    }
                }
            }

            // Trailing ⋮ — inside the scrollable ButtonGroup, always at the far right; users scroll to find it.
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

        // Dropdown: rename/delete/create. Placed OUTSIDE ButtonGroup so the popup positions relative to the Row, not the scrollable group.
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

/** One session card. Shared by the normal list (wrapped in swipe-to-delete) and the multi-select list. [openHighlight] tints the open-session row in the wide layout; [checked] tints a ticked row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionRow(
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
    // In selection mode, highlight = "ticked" (open-session tint would otherwise look identical); outside, "open session".
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
        // combinedClickable on the INNER row clips the tap indication to the card's rounded shape while the Card keeps its elevation shadow.
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                // Smaller start inset so the title + indicator sit further left; the indicator reads as roughly centred in the gap.
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
                // Reserve the indicator slot (fixed footprint) so the title + repo never shift when it clears. Watchdog cap (wall/idle timeout) isn't a failure — render like done, not the red error icon.
                StatusIndicator(
                    status = indicatorStatus(session),
                    awaitingInput = indicatorAwaitingInput(session),
                    size = 16.dp,
                    unread = unread && !openHighlight,
                    // Same gate as the steady dot: only flash the transient completion check for a completion the user has NOT read yet — otherwise the resume catch-up replays an old running→idle flip and flashes a check for a session already acked.
                    flashOnComplete = unread && !openHighlight,
                )
                Spacer(Modifier.width(6.dp))
            }
            Column(Modifier.weight(1f)) {
                FadingText(
                    session.prompt.ifBlank { "(no prompt)" },
                    style = MaterialTheme.typography.bodyLarge,
                )
                // Same 8-char/labelSmall/onSurfaceVariant pair as the session page top bar, so a session can be identified (@-mentioned) by id from the list.
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

/** One session in the list, with swipe-to-delete wrapping (normal mode) or bare (selection mode). */
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
    // Tap either meter to flip BOTH labels between the window name (5h/7d) and the time left until reset (3h29m / 3d21h).
    var showReset by remember { mutableStateOf(false) }
    val toggle = { showReset = !showReset }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        u.five_hour?.let { Meter(if (showReset) resetIn(it.resets_at) else "5h", it, toggle, Modifier.weight(1f)) }
        u.seven_day?.let { Meter(if (showReset) resetIn(it.resets_at) else "7d", it, toggle, Modifier.weight(1f)) }
    }
}

@Composable
private fun Meter(label: String, window: UsageWindow, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val pct = window.utilization   // 0..100 from the API
    val color = when {
        pct >= 90.0 -> MaterialTheme.colorScheme.error      // near the cap
        pct >= 70.0 -> MaterialTheme.colorScheme.tertiary   // filling up
        else -> MaterialTheme.colorScheme.primary           // healthy
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

/** Index of the session whose [Session.id] matches [selectedId], or -1 if none. Pure for unit-testing. */
internal fun indexOfSelected(sessions: List<Session>, selectedId: String?): Int {
    if (selectedId == null) return -1
    return sessions.indexOfFirst { it.id == selectedId }
}
