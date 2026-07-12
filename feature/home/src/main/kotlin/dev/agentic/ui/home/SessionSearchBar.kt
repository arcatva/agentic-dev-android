@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.agentic.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentic.data.net.SearchField
import dev.agentic.data.net.SearchHit
import dev.agentic.data.net.SearchMatch
import dev.agentic.ui.components.SearchBar
import dev.agentic.ui.components.highlightQuery
import dev.agentic.ui.components.matchRanges
import dev.agentic.ui.components.snippetAroundMatch

/** Stateless search surface; hosts the shared SearchBar. Results render in SearchResultsPanel inside the host's SessionListPane (replaces, not overlays). */
@Composable
internal fun SessionSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    @Suppress("UNUSED_PARAMETER") results: List<SearchHit>,
    selectionMode: Boolean,
    @Suppress("UNUSED_PARAMETER") onOpen: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Local text for immediate display — avoids the VM→combine→recompose round-trip on every keystroke.
    var localText by rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (localText != query) localText = query
    }

    val expanded = localText.length >= 2 && !selectionMode
    LaunchedEffect(expanded) { onExpandedChange(expanded) }

    // canFocus=false (not just FocusRequester) is what keeps the adaptive scaffold's auto-focus off
    // the field. `remember`, not rememberSaveable: restoring userActivated=true after process recreation
    // would pop the keyboard unprompted.
    var userActivatedSearch by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val canFocusSearch = searchTextFieldCanFocus(query = localText, userActivated = userActivatedSearch)
    LaunchedEffect(userActivatedSearch) {
        if (userActivatedSearch) searchFocus.requestFocus()
    }

    Box(modifier) {
        SearchBar(
            query = localText,
            onQueryChange = { newText ->
                localText = newText
                onQueryChange(newText)
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchFocus)
                .focusProperties { canFocus = canFocusSearch }
                .onFocusChanged { state ->
                    if (!state.isFocused && localText.isEmpty()) userActivatedSearch = false
                },
            searching = searching,
        )
        // canFocus=false makes the TextField swallow its own taps without gaining focus — an outer
        // .clickable never fires (the "search bar is dead" bug). Overlay sits ON TOP, arms the gate,
        // the LaunchedEffect above then requests focus + IME.
        if (!canFocusSearch) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Search sessions",
                        role = Role.Button,
                    ) { userActivatedSearch = true }
                    // Gated TextField is skipped by a11y traversal, so this overlay IS the TalkBack entry.
                    .semantics { contentDescription = "Search sessions" },
            )
        }
    }
}

internal fun searchTextFieldCanFocus(query: String, userActivated: Boolean): Boolean =
    userActivated || query.isNotEmpty()

/** Replaces SessionListPane's body when the query has length ≥ 2. Occupies the full list region so clearing the box flips back to the regular list immediately. */
@Composable
internal fun SearchResultsPanel(
    results: List<SearchHit>,
    query: String,
    onOpen: (String) -> Unit,
    onClearQuery: () -> Unit,
    modifier: Modifier = Modifier,
    lastSearchedQuery: String = "",
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val now = remember(query) { System.currentTimeMillis() }
    // In-flight when the current query differs from the last one the server answered — immune to
    // one-frame ordering races between searchQuery / searching / searchResults updates.
    val searchInFlight = query.isNotEmpty() && query != lastSearchedQuery

    if (results.isEmpty()) {
        Box(
            modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (searchInFlight) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LoadingIndicator()
                    Text(
                        "Searching…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "No sessions match \"${query.trim()}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp),
        ) {
            items(results, key = { it.session.id }) { hit ->
                // animateItem() so rows slide in/out/reorder as the query narrows — matches HomeScreen.
                Column(Modifier.animateItem().fillMaxWidth().padding(vertical = 5.dp)) {
                    SessionRow(
                        session = hit.session,
                        now = now,
                        openHighlight = false,
                        inSelectionMode = false,
                        checked = false,
                        unread = false,
                        onClick = {
                            keyboard?.hide()
                            onClearQuery()
                            onOpen(hit.session.id)
                        },
                        onLongClick = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Surface content the row doesn't show (answer, tool detail, notes) with the match painted — otherwise an unexplained result row.
                    hit.bestSnippetMatch(query)?.let { match ->
                        SearchSnippet(
                            label = match.field.snippetLabel(),
                            snippet = match.snippet,
                            query = query,
                            modifier = Modifier.padding(start = 30.dp, end = 6.dp, top = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Matched-content line under a SessionRow: field label + snippet with the query painted via highlightQuery. Max 2 lines. */
@Composable
private fun SearchSnippet(
    label: String,
    snippet: String,
    query: String,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    // Re-window so the match is near the front (the 2-line clamp would otherwise hide a centred backend window), then highlight.
    val highlighted = remember(snippet, query, accent) {
        highlightQuery(snippetAroundMatch(snippet, query), query, accent)
    }
    Row(modifier, verticalAlignment = Alignment.Top) {
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Text(
            highlighted,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Fields already shown on the row (prompt = main line, repos = subtitle) — a match needs no extra snippet.
private val ROW_VISIBLE_FIELDS = setOf(SearchField.Title, SearchField.Repo)

/**
 * Single match worth surfacing under the row, or null if only row-visible fields matched. Backend
 * lists matches in importance order (prompt → metadata → log), capped — so the first non-row-visible
 * match is the most relevant "why did this match?" snippet.
 *
 * Subtlety 1 — backend exposes prompt via two fields: Title (= session.prompt, the row's main line) AND Prompt (a rendered log line).
 * For a single-turn session those are identical, so a Prompt match would render a snippet that just repeats the title — drop it
 * only when the snippet equals the row's prompt; an earlier turn in a multi-turn session or a windowed slice of a long prompt still surfaces.
 *
 * Subtlety 2 — backend snippet is a fixed window that may not contain the query (window can fall beside the match). Prefer matches whose snippet actually contains the query, and prefer prose over Ask (raw AskUserQuestion JSON).
 */
internal fun SearchHit.bestSnippetMatch(query: String): SearchMatch? {
    val rowPrompt = session.prompt.trim()
    val eligible = matches.filter { m ->
        when {
            m.field in ROW_VISIBLE_FIELDS -> false
            m.field == SearchField.Prompt && m.snippet.stripSnippetEllipsis().trim() == rowPrompt -> false
            else -> true
        }
    }
    if (eligible.isEmpty()) return null
    fun hasQuery(m: SearchMatch) = matchRanges(m.snippet, query).isNotEmpty()
    return eligible.firstOrNull { hasQuery(it) && it.field != SearchField.Ask }
        ?: eligible.firstOrNull { hasQuery(it) }
        ?: eligible.first()
}

/** Strips the "..." the backend's `extract_snippet` adds when it windows a long field. */
private fun String.stripSnippetEllipsis(): String = removePrefix("...").removeSuffix("...")

/** Short human label for the field a content match came from; empty for row-visible fields (never rendered). */
private fun SearchField.snippetLabel(): String = when (this) {
    SearchField.Title, SearchField.Repo -> ""
    SearchField.Branch -> "branch"
    SearchField.SessionId -> "id"
    SearchField.Status -> "status"
    SearchField.Error -> "error"
    SearchField.Prompt -> "message"
    SearchField.Notes -> "note"
    SearchField.Answer -> "answer"
    SearchField.ToolName, SearchField.ToolSummary, SearchField.ToolDetail -> "tool"
    SearchField.SpawnDesc, SearchField.SpawnResult -> "subagent"
    SearchField.Skill -> "skill"
    SearchField.Workflow -> "workflow"
    SearchField.Ask -> "question"
    SearchField.Plan -> "plan"
    SearchField.Perm -> "permission"
    SearchField.Attachment -> "file"
}