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
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
 * Full-screen skill store (Settings → Skills → Store). Pinned search field; catalog as a real
 * page-scrolling list. Pasting a GitHub-style reference offers a direct-install row.
 * Sources live in a bottom sheet. Shares [GlobalSettingsViewModel] with the Settings back-stack entry.
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
            initializer { GlobalSettingsViewModel(container.globalSettingsRepo) }
        },
    )
    val s by resolvedVm.uiState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    // Fetch immediately (cached server-side for 6h).
    LaunchedEffect(Unit) { resolvedVm.loadCatalog() }

    LaunchedEffect(s.error) {
        val msg = s.error ?: return@LaunchedEffect
        snackbarHost.showSnackbar(msg)
        resolvedVm.clearError()
    }

    var query by remember { mutableStateOf("") }
    var sourcesOpen by remember { mutableStateOf(false) }
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
                    // Icon actions — manage sources / bypass the cache.
                    IconButton(onClick = { sourcesOpen = true }) {
                        val count = s.sources?.size ?: 0
                        // Fold the count into the icon's contentDescription and clear the badge's
                        // semantics so TalkBack announces "Sources (N)" as one label, not two nodes.
                        BadgedBox(
                            badge = {
                                if (count > 0) {
                                    Badge(modifier = Modifier.clearAndSetSemantics {}) { Text("$count") }
                                }
                            },
                        ) {
                            Icon(
                                Icons.Rounded.Source,
                                contentDescription = if (count > 0) "Sources ($count)" else "Sources",
                            )
                        }
                    }
                    IconButton(onClick = { resolvedVm.loadCatalog(force = true) }, enabled = !s.catalogLoading) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
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
                placeholder = "Search skills",
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
                    // Direct-install offer: an explicit URL always gets one (the user pasted it
                    // to install, even if search matches); a bare slashed term only when nothing
                    // in the catalog matches, so searches don't grow a spurious install row.
                    val isExplicitUrl = q.startsWith("https://") || q.startsWith("http://") || q.startsWith("github.com/")
                    val directRef = if (isExplicitUrl || shown.isEmpty()) githubRefQuery(q) else null
                    val multiSource = catalog.map { it.sourceRepo }.distinct().size > 1
                    LazyColumn(Modifier.fillMaxSize()) {
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
                                    if (catalog.isEmpty()) "Store is empty — add a source."
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
                        // Per-source scan failures — rest of store still renders.
                        // No key: duplicate error strings would crash on key collision.
                        items(s.catalogErrors) { err ->
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

/** Catalog row: name + description (+ provenance when several sources contribute), trailing Install/Update/Remove. */
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
            // Update only when the store has something newer. false = match (no button);
            // null = unknown provenance — offer Update as a reinstall which records metadata
            // for future checks.
            if (entry.updateAvailable != false) {
                TextButton(onClick = { onInstall(true) }, enabled = !busy) { Text("Update") }
            }
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
                    IconButton(onClick = { onRemoveSource(src) }, enabled = !busy) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove $src")
                    }
                }
            }
            var newSource by remember { mutableStateOf("") }
            // Clear only once the source lands in the list — failed add keeps input for retry.
            LaunchedEffect(sources) {
                if (newSource.isNotBlank() && sources?.contains(newSource.trim().trimEnd('/')) == true) {
                    newSource = ""
                }
            }
            AppTextField(
                value = newSource,
                onValueChange = { newSource = it },
                placeholder = "owner/repo or URL",
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

/** If [q] looks like an installable GitHub reference, return it normalized; else null.
 *  Accepted: `https://github.com/...`, `github.com/...`, or `owner/repo[/path]` shorthand
 *  (at least one '/', no spaces, plausible segment chars). */
internal fun githubRefQuery(q: String): String? {
    if (q.isBlank() || q.any { it.isWhitespace() }) return null
    // URL forms validate the same way after prefix-strip; a bare "https://github.com/" must not offer an install.
    val prefixes = listOf("https://github.com/", "http://github.com/", "github.com/")
    val remaining = prefixes.firstOrNull { q.startsWith(it) }?.let { q.removePrefix(it) } ?: q
    val parts = remaining.trim('/').split('/')
    if (parts.size < 2) return null
    val segOk = { s: String -> s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } }
    return if (segOk(parts[0]) && segOk(parts[1])) q else null
}
