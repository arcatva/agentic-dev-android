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

/**
 * Stateless session-list search surface. Hosts the shared [SearchBar] at the title row only. The
 * results list itself is rendered by [SearchResultsPanel] inside the host's [SessionListPane], so
 * the panel REPLACES (not overlays) the session list body — no occlusion, no reflow.
 *
 * The wrapper itself owns no query state — the host (top bar) passes [query] / [onQueryChange] so
 * the same String that drives HomeViewModel's content search also drives the visible text and
 * spinner.
 *
 * [onExpandedChange] mirrors whether the host should swap the body into search-results mode
 * (query has length >= 2 and selection mode is off) so the host can hide the New-request FAB.
 */
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
    // Local text owned by the composable for immediate display — no round-trip
    // through ViewModel → combine chain → recompose before the user sees each
    // keystroke. The external [query] syncs back in only when it's externally
    // cleared (e.g. the host calls onClearSearch → ViewModel sets query to "").
    var localText by rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (localText != query) localText = query
    }

    val expanded = localText.length >= 2 && !selectionMode
    LaunchedEffect(expanded) { onExpandedChange(expanded) }

    // The adaptive scaffold auto-focuses the first focusable child when the list pane
    // re-enters. Keep the search TextField out of focus traversal until the USER
    // explicitly taps it (or while it already contains text). A FocusRequester alone
    // does not prevent scaffold focus — canFocus=false does.
    //
    // `remember`, not rememberSaveable: restoring userActivated=true after process
    // recreation would re-request focus and pop the keyboard unprompted on restore.
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
        // While the field is focus-gated (canFocus=false), the TextField's own gesture
        // handler swallows taps WITHOUT gaining focus — an outer `.clickable` on the
        // TextField's modifier chain never fires (that was the "search bar is dead" bug).
        // This overlay sits ON TOP of the TextField, so it receives the tap first: arm
        // the gate, then the LaunchedEffect above requests focus and the IME opens.
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
                    // The gated TextField (canFocus=false) is skipped by accessibility
                    // traversal, so this overlay IS the search entry point for TalkBack —
                    // label it accordingly.
                    .semantics { contentDescription = "Search sessions" },
            )
        }
    }
}

internal fun searchTextFieldCanFocus(query: String, userActivated: Boolean): Boolean =
    userActivated || query.isNotEmpty()

/**
 * Replaces [SessionListPane]'s body when the user has typed at least 2 characters. Lists the
 * [SearchHit] results as plain `SessionRow`s, or shows a centered "no match" message. The panel
 * occupies the entire list region, so it neither overlaps nor pushes anything — when the user
 * clears the search box the host flips back to the regular list immediately.
 *
 * Tapping a result row also hides the soft keyboard so the row beneath isn't obscured, and clears
 * the search query so the user lands on the regular list once they return from the session detail.
 *
 * Tapping empty space in the panel (gaps, below the last row, the "no match" area) blurs the search
 * field and drops the keyboard too — but that is handled once at the screen root by
 * [Modifier.clearFocusOnTap] on the host (this panel renders inside that container), so the panel no
 * longer carries its own full-area tap handler.
 */
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
    // Sample the clock once per query so timestamps stay correct without a recomposition storm.
    val now = remember(query) { System.currentTimeMillis() }
    // A search is "in flight" when the current query differs from the last one the
    // server has answered. This is immune to one-frame ordering races between
    // searchQuery / searching / searchResults updates in the combine chain.
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
                // animateItem() so result rows slide in/out/reorder as the query narrows, matching the
                // main session list (HomeScreen) instead of snapping.
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
                    // When the session matched on content the row doesn't show (an answer, a tool's
                    // detail, notes, …) surface that snippet with the matched text painted in the
                    // theme colour — otherwise a content hit looks like an unexplained result row.
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

/**
 * One matched-content line shown beneath a search result's [SessionRow]: a quiet field [label]
 * (e.g. "answer", "tool") followed by the backend [snippet], with every occurrence of [query]
 * painted in the theme's primary colour via [highlightQuery]. Wraps to at most two lines so a long
 * snippet can't make one result tower over the others; the matched substring is what the user
 * scanned for, so it carries the brand colour while the surrounding context stays muted.
 */
@Composable
private fun SearchSnippet(
    label: String,
    snippet: String,
    query: String,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    // Re-window so the matched term is near the front (otherwise the 2-line clamp hides the
    // backend's centred match), then highlight it. Recomputed only when an input actually changes.
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

/**
 * Fields whose text the [SessionRow] already shows — the prompt is the row's main line and the repo
 * names sit in its subtitle — so a match on them needs no extra snippet. Every other field is
 * "content" the row doesn't surface, which is what [SearchSnippet] exists to reveal.
 */
private val ROW_VISIBLE_FIELDS = setOf(SearchField.Title, SearchField.Repo)

/**
 * The single match worth showing under the row as a highlighted snippet, or `null` when the session
 * only matched fields the row already shows (its prompt / repos). The backend lists [matches] in
 * importance order (prompt → metadata → log content) and caps the list, so the first
 * non-row-visible match is the most relevant "why did this match?" snippet.
 *
 * Subtlety 1 — the backend exposes the prompt text through TWO fields: [SearchField.Title] (the
 * `session.prompt` metadata that IS the row's main line) and [SearchField.Prompt] (a rendered
 * user-turn log line). For a single-turn session those are the same text, so a `Prompt` match would
 * render a snippet that just repeats the title — the very redundancy this feature removes. We drop a
 * `Prompt` match only when its snippet equals the row's prompt; an earlier turn in a multi-turn
 * session, or a windowed slice of a long prompt whose match sits off-screen in the truncated title,
 * differs and is still surfaced.
 *
 * Subtlety 2 — a backend snippet is a fixed-size window that does NOT always contain the query: for a
 * long field the window can fall entirely beside the match, so the snippet has nothing to highlight.
 * Among the eligible matches we therefore prefer one whose snippet actually contains [query] (so the
 * highlight is visible), and prefer readable prose over [SearchField.Ask] — whose snippet is the raw
 * AskUserQuestion JSON payload — falling back to the first eligible match only when none contain it.
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

/** Strips the leading/trailing "..." that the backend's `extract_snippet` adds when it windows a
 *  long field, so a snippet can be compared against the full source text it was cut from. */
private fun String.stripSnippetEllipsis(): String = removePrefix("...").removeSuffix("...")

/**
 * Short human label for the field a content match came from, shown as a quiet prefix on the snippet.
 * Empty for the row-visible fields, which are never rendered as snippets.
 */
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