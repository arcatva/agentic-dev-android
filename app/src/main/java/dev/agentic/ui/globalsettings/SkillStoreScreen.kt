package dev.agentic.ui.globalsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.data.net.CatalogSkill
import dev.agentic.di.appContainer
import dev.agentic.ui.components.AppTextField
import dev.agentic.ui.components.cardFieldColors

/**
 * Full-screen skill store (navigated from Settings → Skills → Store). Replaces the old
 * inline install pane, which crammed search + catalog + source management + a one-off
 * GitHub field into one Settings card.
 *
 * Layout: pinned search field, then the aggregated catalog as a REAL page-scrolling list
 * (no nested capped scroll). The search field is smart: paste something that looks like a
 * GitHub reference (`owner/repo[/path]` or URL) and a direct-install row appears on top.
 * Source management (list / add / remove) lives in a bottom sheet behind the top-bar
 * "Sources" action; Refresh re-scans past the server's cache.
 *
 * Owns its own [GlobalSettingsViewModel] instance — the Settings screen quietly reloads its
 * component list when you navigate back (see [GlobalSettingsViewModel.load]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SkillStoreScreen(
    onBack: () -> Unit,
    vm: GlobalSettingsViewModel? = null,
) {
    val container = appContainer()
    val resolvedVm: GlobalSettingsViewModel = vm ?: viewModel(
        factory = viewModelFactory {
            initializer { GlobalSettingsViewModel(container.api) }
        },
    )
    val s by resolvedVm.uiState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    // The store IS this screen — fetch it immediately (cached server-side for 6h).
    LaunchedEffect(Unit) { resolvedVm.loadCatalog() }

    // Transient errors (install/remove/source failures) via snackbar, like Settings.
    LaunchedEffect(s.error) {
        val msg = s.error ?: return@LaunchedEffect
        snackbarHost.showSnackbar(msg)
        resolvedVm.clearError()
    }

    var query by remember { mutableStateOf("") }
    var sourcesOpen by remember { mutableStateOf(false) }
    // Remove-skill confirmation (same contract as the Settings chips' long-press dialog).
    var pendingRemove by remember { mutableStateOf<String?>(null) }

    pendingRemove?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Delete skill?") },
            text = { Text("Delete skill \"$name\"? This removes it globally.") },
            confirmButton = {
                Button(onClick = {
                    resolvedVm.deleteSkill(name)
                    pendingRemove = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            },
        )
    }

    if (sourcesOpen) {
        SourcesSheet(
            sources = s.sources,
            busy = s.busy,
            onAddSource = { resolvedVm.addSource(it) },
            onRemoveSource = { resolvedVm.removeSource(it) },
            onDismiss = { sourcesOpen = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Skill store") },
                actions = {
                    // Text actions (no decorative icons): manage sources / bypass the cache.
                    TextButton(onClick = { sourcesOpen = true }) {
                        Text("Sources${s.sources?.let { " (${it.size})" } ?: ""}")
                    }
                    TextButton(onClick = { resolvedVm.loadCatalog(force = true) }, enabled = !s.catalogLoading) {
                        Text("Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
        ) {
            // Pinned search — also accepts a direct GitHub reference (see githubRefQuery).
            AppTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search skills — or paste owner/repo/path or URL",
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = "Search skills",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                shape = MaterialTheme.shapes.small,
                colors = cardFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )

            val catalog = s.catalog
            when {
                s.catalogLoading && catalog == null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { LoadingIndicator() }
                s.catalogError != null && catalog == null -> Text(
                    s.catalogError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                catalog != null -> {
                    val installedNames = s.components.filter { it.kind == "skill" }.map { it.name }.toSet()
                    val q = query.trim()
                    val shown = catalog.filter {
                        q.isEmpty() || it.name.contains(q, ignoreCase = true) ||
                            it.description.contains(q, ignoreCase = true)
                    }
                    // Direct-install offer only when the query looks like a GitHub reference AND
                    // matches nothing in the catalog — a slashed SEARCH term ("ci/cd") with real
                    // results must not grow a spurious install row.
                    val directRef = if (shown.isEmpty()) githubRefQuery(q) else null
                    val multiSource = catalog.map { it.sourceRepo }.distinct().size > 1
                    LazyColumn(Modifier.fillMaxSize()) {
                        // Direct install: the query itself looks like a GitHub reference.
                        if (directRef != null) {
                            item(key = "direct-install") {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Install from GitHub",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            directRef,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(
                                        onClick = { resolvedVm.installSkill(directRef) },
                                        enabled = !s.busy,
                                    ) { Text("Install") }
                                }
                                HorizontalDivider()
                            }
                        }
                        if (shown.isEmpty() && directRef == null) {
                            item(key = "empty") {
                                Text(
                                    if (catalog.isEmpty()) "The store is empty — add a source (top right)."
                                    else "No skills match \"$q\".",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            }
                        }
                        items(shown, key = { "${it.sourceRepo}|${it.source}" }) { entry ->
                            StoreRow(
                                entry = entry,
                                installed = entry.name in installedNames,
                                showSource = multiSource,
                                busy = s.busy,
                                onInstall = { update -> resolvedVm.installSkill(entry.source, update) },
                                onRemove = { pendingRemove = entry.name },
                            )
                        }
                        // Per-source scan failures — the rest of the store still renders.
                        items(s.catalogErrors, key = { "err|$it" }) { err ->
                            Text(
                                err,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One catalog row: name + description (+ provenance when several sources contribute), with
 *  Install (not installed) or Update + Remove (installed) as trailing text actions. */
@Composable
private fun StoreRow(
    entry: CatalogSkill,
    installed: Boolean,
    showSource: Boolean,
    busy: Boolean,
    onInstall: (update: Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (entry.description.isNotBlank()) {
                Text(
                    entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showSource && entry.sourceRepo.isNotBlank()) {
                Text(
                    entry.sourceRepo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (installed) {
            TextButton(onClick = { onInstall(true) }, enabled = !busy) { Text("Update") }
            TextButton(onClick = onRemove, enabled = !busy) { Text("Remove") }
        } else {
            TextButton(onClick = { onInstall(false) }, enabled = !busy) { Text("Install") }
        }
    }
}

/** Bottom sheet listing the configured store sources with remove actions and an add form. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcesSheet(
    sources: List<String>?,
    busy: Boolean,
    onAddSource: (String) -> Unit,
    onRemoveSource: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Store sources",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            sources?.forEach { src ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        src,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { onRemoveSource(src) }, enabled = !busy) {
                        Text("Remove")
                    }
                }
            }
            var newSource by remember { mutableStateOf("") }
            // Clear the field only once the source actually lands in the list — a failed
            // add keeps the (possibly long) input for retry.
            LaunchedEffect(sources) {
                if (newSource.isNotBlank() && sources?.contains(newSource.trim().trimEnd('/')) == true) {
                    newSource = ""
                }
            }
            AppTextField(
                value = newSource,
                onValueChange = { newSource = it },
                placeholder = "Add source — owner/repo[/path] or URL",
                supportingText = "Every directory containing a SKILL.md becomes a store entry.",
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = cardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onAddSource(newSource.trim()) },
                enabled = !busy && newSource.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add source")
            }
        }
    }
}

/**
 * If [q] looks like an installable GitHub reference, return it normalized, else null.
 * Accepted: `https://github.com/...` URLs, `github.com/...`, or `owner/repo[/path]`
 * shorthand (at least one '/', no spaces, plausible first segment). A plain search word
 * ("weather") or phrase never matches.
 */
internal fun githubRefQuery(q: String): String? {
    if (q.isBlank() || q.any { it.isWhitespace() }) return null
    // URL forms are validated the same way after stripping the prefix — a bare
    // "https://github.com/" or ".../owner" must not offer an install.
    val prefixes = listOf("https://github.com/", "http://github.com/", "github.com/")
    val remaining = prefixes.firstOrNull { q.startsWith(it) }?.let { q.removePrefix(it) } ?: q
    val parts = remaining.trim('/').split('/')
    if (parts.size < 2) return null
    val segOk = { s: String -> s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } }
    return if (segOk(parts[0]) && segOk(parts[1])) q else null
}
