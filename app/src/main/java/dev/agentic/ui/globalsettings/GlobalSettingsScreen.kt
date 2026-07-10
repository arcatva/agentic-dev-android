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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.data.net.ComponentInfo
import dev.agentic.di.appContainer
import dev.agentic.ui.components.AppTextField
import dev.agentic.ui.components.ComponentChip
import dev.agentic.ui.components.cardFieldColors
import dev.agentic.ui.newrequest.McpDraft

/**
 * Global Settings screen — lists all skills, plugins, and MCP components as colored chips in
 * FlowRow groups (same visual language as the New Request screen).
 *
 * Skills and plugins are interactive: tapping a chip calls [GlobalSettingsViewModel.toggle].
 * MCP chips have no global toggle (backend doesn't support it) — long-press is still available
 * for delete.
 *
 * Each section now has an "Add" affordance (collapsible inline form) and each chip supports
 * long-press → confirm dialog → delete/uninstall.
 *
 * Components are grouped: Skills → Plugins → MCP. All sections are always rendered.
 * Chip visual:
 *   - ON (globalEnabled==true): filled with kind color (green for skills, purple for plugins/mcp)
 *   - OFF (globalEnabled==false): outlined/unselected default FilterChip look
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GlobalSettingsScreen(
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

    // Show transient errors in a snackbar and dismiss them from the VM.
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
                title = { Text("Global Settings") },
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
                        .verticalScroll(rememberScrollState()),
                ) {
                    // ── Skills ───────────────────────────────────────────────────
                    SkillsSection(
                        skills = skills,
                        toggling = s.toggling,
                        onToggle = { resolvedVm.toggle(it) },
                        onAddSkill = { name, desc -> resolvedVm.addSkill(name, desc) },
                        onDeleteSkill = { resolvedVm.deleteSkill(it) },
                    )
                    Spacer(Modifier.height(8.dp))

                    // ── Plugins ──────────────────────────────────────────────────
                    PluginsSection(
                        plugins = plugins,
                        toggling = s.toggling,
                        pluginBusy = s.pluginBusy,
                        onToggle = { resolvedVm.toggle(it) },
                        onInstallPlugin = { resolvedVm.installPlugin(it) },
                        onUninstallPlugin = { resolvedVm.uninstallPlugin(it) },
                    )
                    Spacer(Modifier.height(8.dp))

                    // ── MCP (always rendered, toggle read-only, but add/delete supported) ──
                    McpSection(
                        mcps = mcps,
                        onAddMcpServer = { draft -> resolvedVm.addMcpServer(draft) },
                        onDeleteMcpServer = { resolvedVm.deleteMcpServer(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ── Section header row with "+ Add" button ────────────────────────────────────

@Composable
private fun SectionHeaderRow(label: String, onAdd: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        TextButton(onClick = onAdd) {
            Icon(Icons.Rounded.Add, contentDescription = "Add $label", modifier = Modifier.padding(end = 4.dp))
            Text("Add")
        }
    }
}

/** Section header without an add button (used inside sections that render the header themselves). */
@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

// ── Skills section ────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SkillsSection(
    skills: List<ComponentInfo>,
    toggling: Set<String>,
    onToggle: (ComponentInfo) -> Unit,
    onAddSkill: (name: String, description: String) -> Unit,
    onDeleteSkill: (name: String) -> Unit,
) {
    var addExpanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ComponentInfo?>(null) }

    // Confirm delete dialog
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

    SectionHeaderRow(label = "Skills", onAdd = { addExpanded = !addExpanded })

    // Add form
    AnimatedVisibility(
        visible = addExpanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        AddSkillForm(
            onAdd = { name, desc ->
                onAddSkill(name, desc)
                addExpanded = false
            },
        )
    }

    // Chips
    if (skills.isEmpty()) {
        Text(
            "No skills",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    } else {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val toggleKey = { c: ComponentInfo -> "${c.kind}:${c.id}" }
            skills.forEach { component ->
                val isToggling = toggleKey(component) in toggling
                ComponentChip(
                    label = component.name.ifBlank { component.id },
                    kind = component.kind,
                    effective = component.globalEnabled,
                    onClick = { if (!isToggling) onToggle(component) },
                    enabled = !isToggling,
                    onLongClick = { pendingDelete = component },
                )
            }
        }
    }
}

@Composable
private fun AddSkillForm(onAdd: (name: String, description: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppTextField(
            value = name,
            onValueChange = { name = it; localError = null },
            placeholder = "Skill name",
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = cardFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        AppTextField(
            value = description,
            onValueChange = { description = it },
            placeholder = "Description (optional)",
            singleLine = false,
            minLines = 2,
            maxLines = 4,
            shape = MaterialTheme.shapes.small,
            colors = cardFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        localError?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = {
                if (name.isBlank()) {
                    localError = "Skill name is required"
                } else {
                    onAdd(name.trim(), description.trim())
                    name = ""
                    description = ""
                    localError = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Text("  Add Skill")
        }
    }
}

// ── Plugins section ───────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PluginsSection(
    plugins: List<ComponentInfo>,
    toggling: Set<String>,
    pluginBusy: Boolean,
    onToggle: (ComponentInfo) -> Unit,
    onInstallPlugin: (id: String) -> Unit,
    onUninstallPlugin: (id: String) -> Unit,
) {
    var addExpanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ComponentInfo?>(null) }

    // Confirm uninstall dialog
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

    SectionHeaderRow(label = "Plugins", onAdd = { if (!pluginBusy) addExpanded = !addExpanded })

    // Busy indicator while plugin CLI op is in flight
    if (pluginBusy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Text(
            "Plugin operation in progress…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }

    // Add form
    AnimatedVisibility(
        visible = addExpanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        AddPluginForm(
            pluginBusy = pluginBusy,
            onInstall = { id ->
                onInstallPlugin(id)
                addExpanded = false
            },
        )
    }

    // Chips
    if (plugins.isEmpty()) {
        Text(
            "No plugins installed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    } else {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val toggleKey = { c: ComponentInfo -> "${c.kind}:${c.id}" }
            plugins.forEach { component ->
                val isToggling = toggleKey(component) in toggling
                ComponentChip(
                    label = component.name.ifBlank { component.id },
                    kind = component.kind,
                    effective = component.globalEnabled,
                    onClick = { if (!isToggling && !pluginBusy) onToggle(component) },
                    enabled = !isToggling && !pluginBusy,
                    onLongClick = { if (!pluginBusy) pendingDelete = component },
                )
            }
        }
    }
}

@Composable
private fun AddPluginForm(
    pluginBusy: Boolean,
    onInstall: (id: String) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                        onInstall(trimmed)
                        id = ""
                        localError = null
                    }
                }
            },
            enabled = !pluginBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Text("  Install Plugin")
        }
    }
}

// ── MCP section ───────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun McpSection(
    mcps: List<ComponentInfo>,
    onAddMcpServer: (McpDraft) -> String?,
    onDeleteMcpServer: (name: String) -> Unit,
) {
    var addExpanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ComponentInfo?>(null) }

    // Confirm delete dialog
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

    SectionHeaderRow(label = "MCP", onAdd = { addExpanded = !addExpanded })

    // Add form
    AnimatedVisibility(
        visible = addExpanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        AddMcpForm(
            onAdd = { draft ->
                val err = onAddMcpServer(draft)
                if (err == null) {
                    addExpanded = false
                }
                err
            },
        )
    }

    // Chips (read-only toggle, but deletable via long-press)
    if (mcps.isEmpty()) {
        Text(
            "No MCP servers",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    } else {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            mcps.forEach { component ->
                ComponentChip(
                    label = component.name.ifBlank { component.id },
                    kind = component.kind,
                    effective = component.globalEnabled,
                    onClick = { /* MCP global toggle not supported */ },
                    enabled = true,
                    readOnlyCaption = "managed per-session",
                    onLongClick = { pendingDelete = component },
                )
            }
        }
    }
}

/**
 * Inline form for adding an MCP server globally.
 * Mirrors the AddMcpForm in NewRequestScreen — transport toggle (stdio | HTTP), name, fields.
 * [onAdd] receives the draft and returns a nullable error string (null = success, close the form).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddMcpForm(
    onAdd: (McpDraft) -> String?,
) {
    var draft by remember { mutableStateOf(McpDraft()) }
    var localError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Transport toggle: stdio | HTTP/SSE
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf("stdio", "http").forEachIndexed { i, t ->
                SegmentedButton(
                    selected = draft.transport == t,
                    onClick = { draft = draft.copy(transport = t) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                    label = { Text(if (t == "stdio") "stdio" else "HTTP / SSE") },
                )
            }
        }

        // Name field (always shown)
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
            // HTTP/SSE type sub-toggle
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("http", "sse").forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = draft.httpType == t,
                        onClick = { draft = draft.copy(httpType = t) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
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
                if (err == null) {
                    draft = McpDraft()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Text("  Add MCP Server")
        }
    }
}
