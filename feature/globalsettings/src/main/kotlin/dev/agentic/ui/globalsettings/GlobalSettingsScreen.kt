package dev.agentic.ui.globalsettings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.text.font.FontWeight
import dev.agentic.data.net.ComponentInfo
import dev.agentic.di.appContainer
import dev.agentic.ui.components.AppTextField
import dev.agentic.ui.components.ComponentChip
import dev.agentic.ui.components.SectionCard
import dev.agentic.ui.components.cardFieldColors
import dev.agentic.ui.newrequest.McpDraft
import dev.agentic.ui.providers.ModelsSections

/**
 * Global settings: skills / plugins / MCP as interactive chips (tap toggles via VM, long-press deletes),
 * plus [ModelsSections]. Groups: Skills → Plugins → MCP → Models. Chip visual: see [ComponentChip].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GlobalSettingsScreen(
    onBack: () -> Unit,
    onOpenSkillStore: () -> Unit = {},
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

    LaunchedEffect(s.error) {
        val msg = s.error ?: return@LaunchedEffect
        snackbarHost.showSnackbar(msg)
        resolvedVm.clearError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Settings") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { pad ->
        when {
            s.loading -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(pad),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingIndicator()
                }
            }
            else -> {
                val skills  = s.components.filter { it.kind == "skill" }
                val plugins = s.components.filter { it.kind == "plugin" }
                val mcps    = s.components.filter { it.kind == "mcp" }

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(pad)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // ── Skills ───────────────────────────────────────────────────
                    SkillsSection(
                        skills = skills,
                        toggling = s.toggling,
                        busy = s.busy,
                        error = s.error,
                        onOpenStore = onOpenSkillStore,
                        onToggle = { resolvedVm.toggle(it) },
                        onDeleteSkill = { resolvedVm.deleteSkill(it) },
                    )

                    // ── Plugins ──────────────────────────────────────────────────
                    PluginsSection(
                        plugins = plugins,
                        toggling = s.toggling,
                        busy = s.busy,
                        error = s.error,
                        onToggle = { resolvedVm.toggle(it) },
                        onInstallPlugin = { resolvedVm.installPlugin(it) },
                        onUninstallPlugin = { resolvedVm.uninstallPlugin(it) },
                    )

                    // ── MCP (tap toggles the global state; long-press removes) ──
                    McpSection(
                        mcps = mcps,
                        toggling = s.toggling,
                        busy = s.busy,
                        error = s.error,
                        onToggle = { resolvedVm.toggle(it) },
                        onAddMcpServer = { draft -> resolvedVm.addMcpServer(draft) },
                        onDeleteMcpServer = { resolvedVm.deleteMcpServer(it) },
                    )

                    // ── Models (Router + Sub-agent models) — merged from the old Models screen ──
                    ModelsSections()
                }
            }
        }
    }
}

// ── Shared "Add" header action (rendered in the SectionCard trailing slot) ────

@Composable
private fun AddHeaderButton(label: String, onAdd: () -> Unit) {
    // Text-only — the header context already names what gets added; semantics supplies TalkBack text.
    TextButton(onClick = onAdd, modifier = Modifier.semantics { contentDescription = "Add $label" }) {
        Text("Add")
    }
}

// ── Skills section ────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SkillsSection(
    skills: List<ComponentInfo>,
    toggling: Set<String>,
    busy: Boolean,
    error: String?,
    onOpenStore: () -> Unit,
    onToggle: (ComponentInfo) -> Unit,
    onDeleteSkill: (name: String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ComponentInfo?>(null) }

    pendingDelete?.let { comp ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete skill?") },
            text = { Text("Delete skill \"${comp.name}\"? This removes it globally.") },
            confirmButton = {
                Button(onClick = {
                    onDeleteSkill(comp.name)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    SectionCard(
        title = "Skills",
        // Skill management lives in the full-screen store — this card is just the installed chips.
        trailing = {
            TextButton(
                onClick = onOpenStore,
                modifier = Modifier.semantics { contentDescription = "Open skill store" },
            ) { Text("Store") }
        },
    ) {
        Column {
            if (skills.isEmpty()) {
                Text(
                    "No skills",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val toggleKey = { c: ComponentInfo -> "${c.kind}:${c.id}" }
                    skills.forEach { component ->
                        val isToggling = toggleKey(component) in toggling
                        ComponentChip(
                            label = component.name.ifBlank { component.id },
                            kind = component.kind,
                            effective = component.globalEnabled,
                            onClick = { if (!isToggling && !busy) onToggle(component) },
                            enabled = !isToggling && !busy,
                            onLongClick = { if (!busy) pendingDelete = component },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PluginsSection(
    plugins: List<ComponentInfo>,
    toggling: Set<String>,
    busy: Boolean,
    error: String?,
    onToggle: (ComponentInfo) -> Unit,
    onInstallPlugin: (id: String) -> Boolean,
    onUninstallPlugin: (id: String) -> Unit,
) {
    var addExpanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ComponentInfo?>(null) }
    var submitting by remember { mutableStateOf(false) }

    // Close form on success (busy→false, no error); keep open on error so user can retry.
    LaunchedEffect(busy, error) {
        if (submitting && !busy) {
            if (error == null) addExpanded = false
            submitting = false
        }
    }

    pendingDelete?.let { comp ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Uninstall plugin?") },
            text = { Text("Uninstall plugin \"${comp.name.ifBlank { comp.id }}\"? This removes it globally.") },
            confirmButton = {
                Button(onClick = {
                    onUninstallPlugin(comp.id)
                    pendingDelete = null
                }) { Text("Uninstall") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    SectionCard(
        title = "Plugins",
        trailing = { AddHeaderButton("Plugins") { if (!busy) addExpanded = !addExpanded } },
    ) {
        // One Column child — keeps the collapsed add-form from doubling the card's spacedBy(12) gap.
        Column {
            // Progress indicator while any op is in flight.
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                Text(
                    "Plugin operation in progress…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            // Add form (bottom padding belongs to the expanded state so it animates away).
            AnimatedVisibility(
                visible = addExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(bottom = 12.dp)) {
                    AddPluginForm(
                        busy = busy,
                        onInstall = { id -> submitting = onInstallPlugin(id) },
                    )
                }
            }

            if (plugins.isEmpty()) {
                Text(
                    "No plugins installed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val toggleKey = { c: ComponentInfo -> "${c.kind}:${c.id}" }
                    plugins.forEach { component ->
                        val isToggling = toggleKey(component) in toggling
                        ComponentChip(
                            label = component.name.ifBlank { component.id },
                            kind = component.kind,
                            effective = component.globalEnabled,
                            onClick = { if (!isToggling && !busy) onToggle(component) },
                            enabled = !isToggling && !busy,
                            onLongClick = { if (!busy) pendingDelete = component },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddPluginForm(
    busy: Boolean,
    onInstall: (id: String) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    // No horizontal padding of its own — SectionCard already insets its content.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTextField(
            value = id,
            onValueChange = { id = it; localError = null },
            placeholder = "Plugin id (e.g. my-plugin@npm)",
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = cardFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        localError?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = {
                val trimmed = id.trim()
                when {
                    trimmed.isBlank() -> localError = "Plugin id is required"
                    trimmed.startsWith("-") -> localError = "Plugin id must not start with a dash"
                    else -> {
                        // Keep `id` so user can retry on API failure; parent unmounts this form on success.
                        onInstall(trimmed)
                        localError = null
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Install plugin")
        }
    }
}

// ── MCP section ───────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun McpSection(
    mcps: List<ComponentInfo>,
    toggling: Set<String>,
    busy: Boolean,
    error: String?,
    onToggle: (ComponentInfo) -> Unit,
    onAddMcpServer: (McpDraft) -> String?,
    onDeleteMcpServer: (name: String) -> Unit,
) {
    var addExpanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ComponentInfo?>(null) }
    var submitting by remember { mutableStateOf(false) }

    // Close form on success (busy→false, no error); keep open on error so user can retry.
    LaunchedEffect(busy, error) {
        if (submitting && !busy) {
            if (error == null) addExpanded = false
            submitting = false
        }
    }

    pendingDelete?.let { comp ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove MCP server?") },
            text = { Text("Remove MCP server \"${comp.name.ifBlank { comp.id }}\"? This removes it globally.") },
            confirmButton = {
                Button(onClick = {
                    onDeleteMcpServer(comp.name.ifBlank { comp.id })
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    SectionCard(
        title = "MCP servers",
        trailing = { AddHeaderButton("MCP servers") { if (!busy) addExpanded = !addExpanded } },
    ) {
        // One Column child — keeps the collapsed add-form from doubling the card's spacedBy(12) gap.
        Column {
            // Add form (bottom padding belongs to the expanded state so it animates away).
            AnimatedVisibility(
                visible = addExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(bottom = 12.dp)) {
                    AddMcpForm(
                        busy = busy,
                        onAdd = { draft ->
                            val validationErr = onAddMcpServer(draft)
                            if (validationErr == null) {
                                // Validation passed; API call enqueued. submitting lets the
                                // LaunchedEffect close the form when the op completes.
                                submitting = true
                            }
                            validationErr
                        },
                    )
                }
            }

            // Tap toggles the server globally (backend parks definition in .claude.json's mcpServersDisabled);
            // long-press removes it.
            if (mcps.isEmpty()) {
                Text(
                    "No MCP servers",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val toggleKey = { c: ComponentInfo -> "${c.kind}:${c.id}" }
                    mcps.forEach { component ->
                        val isToggling = toggleKey(component) in toggling
                        ComponentChip(
                            label = component.name.ifBlank { component.id },
                            kind = component.kind,
                            effective = component.globalEnabled,
                            onClick = { if (!isToggling && !busy) onToggle(component) },
                            enabled = !isToggling && !busy,
                            onLongClick = { if (!busy) pendingDelete = component },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Inline form for adding an MCP server globally. [onAdd] returns null when validation passes
 * and the API call is enqueued; the form stays open on failure so the user can retry.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddMcpForm(
    busy: Boolean,
    onAdd: (McpDraft) -> String?,
) {
    var draft by remember { mutableStateOf(McpDraft()) }
    var localError by remember { mutableStateOf<String?>(null) }

    // No horizontal padding of its own — SectionCard already insets its content.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // icon = {} drops the default selected-checkmark; the filled segment already shows selection.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf("stdio", "http").forEachIndexed { i, t ->
                SegmentedButton(
                    selected = draft.transport == t,
                    onClick = { draft = draft.copy(transport = t) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                    icon = {},
                    label = { Text(if (t == "stdio") "stdio" else "HTTP / SSE") },
                )
            }
        }

        AppTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it); localError = null },
            placeholder = "Server name (e.g. my-mcp)",
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = cardFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (draft.transport == "stdio") {
            AppTextField(
                value = draft.command,
                onValueChange = { draft = draft.copy(command = it) },
                placeholder = "Command (e.g. /usr/bin/node server.js)",
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = cardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = draft.args,
                onValueChange = { draft = draft.copy(args = it) },
                placeholder = "Args (optional, space-separated)",
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = cardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = draft.env,
                onValueChange = { draft = draft.copy(env = it) },
                placeholder = "Env vars (optional, KEY=VALUE lines)",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                shape = MaterialTheme.shapes.small,
                colors = cardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // HTTP/SSE type sub-toggle.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("http", "sse").forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = draft.httpType == t,
                        onClick = { draft = draft.copy(httpType = t) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                        icon = {},
                        label = { Text(t.uppercase()) },
                    )
                }
            }
            AppTextField(
                value = draft.url,
                onValueChange = { draft = draft.copy(url = it) },
                placeholder = "URL (e.g. https://example.com/mcp)",
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = cardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = draft.headers,
                onValueChange = { draft = draft.copy(headers = it) },
                placeholder = "Headers (optional, KEY=VALUE lines)",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                shape = MaterialTheme.shapes.small,
                colors = cardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        localError?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                val err = onAdd(draft)
                localError = err
                // Don't reset `draft` — keep values so user can retry on API failure; parent
                // unmounts on success which naturally resets the draft.
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add MCP server")
        }
    }
}
