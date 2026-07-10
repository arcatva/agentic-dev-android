package dev.agentic.ui.globalsettings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import dev.agentic.data.net.ComponentInfo
import dev.agentic.di.appContainer
import dev.agentic.ui.components.AppTextField
import dev.agentic.ui.components.ComponentChip
import dev.agentic.ui.components.SectionCard
import dev.agentic.ui.components.cardFieldColors
import dev.agentic.ui.newrequest.McpDraft
import dev.agentic.ui.providers.ModelsSections

/**
 * Global settings screen — the app's single settings page: skills, plugins, and MCP components
 * as colored chips in FlowRow groups (same visual language as the New Request screen), plus the
 * models registry ([ModelsSections]: Router + Sub-agent models — merged from the old Models screen).
 *
 * All chips are interactive: tapping toggles the component's GLOBAL enabled state via
 * [GlobalSettingsViewModel.toggle] (skills/plugins through settings.local.json, MCP servers by
 * parking the definition in .claude.json); long-press deletes/uninstalls.
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

                // Same scaffold as the other form pages (NewRequest / Models / Session settings):
                // 16dp content padding, sections in tonal SectionCards spaced 12dp apart.
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
                        onToggle = { resolvedVm.toggle(it) },
                        onAddSkill = { name, desc, instructions -> resolvedVm.addSkill(name, desc, instructions) },
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
    // Text-only ("no decorative icons" rule): the header context already says what gets added.
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
    onToggle: (ComponentInfo) -> Unit,
    onAddSkill: (name: String, description: String, instructions: String) -> Unit,
    onDeleteSkill: (name: String) -> Unit,
) {
    var addExpanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ComponentInfo?>(null) }
    // True while waiting for the add-skill API call started from this form.
    // The form closes ONLY on success (busy→false with no error); stays open on failure
    // so the user can retry without re-entering values.
    var submitting by remember { mutableStateOf(false) }

    // Detect op completion: busy just went false (op finished). If error is null → success →
    // close and reset the form. If error is non-null → failure → keep form open for retry.
    LaunchedEffect(busy, error) {
        if (submitting && !busy) {
            if (error == null) {
                // Success: close the form.
                addExpanded = false
            }
            // Either way stop tracking the in-flight submit so a subsequent tap works.
            submitting = false
        }
    }

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

    SectionCard(
        title = "Skills",
        trailing = { AddHeaderButton("Skills") { if (!busy) addExpanded = !addExpanded } },
    ) {
        // One Column child: a COLLAPSED AnimatedVisibility is a zero-height child, and the card's
        // spacedBy(12) would still pad both sides of it — doubling the header-to-content gap.
        // Grouping the form and the chips into a single child keeps the gap at exactly 12dp.
        Column {
            // Add form (the bottom padding belongs to the expanded state so it animates away).
            AnimatedVisibility(
                visible = addExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(bottom = 12.dp)) {
                    AddSkillForm(
                        busy = busy,
                        onAdd = { name, desc, instructions ->
                            submitting = true
                            onAddSkill(name, desc, instructions)
                            // Form does NOT close here — closes via LaunchedEffect above on success.
                        },
                    )
                }
            }

            // Chips
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

@Composable
private fun AddSkillForm(
    busy: Boolean,
    onAdd: (name: String, description: String, instructions: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    // No horizontal padding of its own — the enclosing SectionCard already insets its content.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            placeholder = "Description — when should the agent load this skill?",
            singleLine = false,
            minLines = 2,
            maxLines = 4,
            shape = MaterialTheme.shapes.small,
            colors = cardFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        // The actual skill content. Without it the created skill is an empty shell — the
        // description only decides WHEN the skill loads; this markdown is WHAT it says.
        AppTextField(
            value = instructions,
            onValueChange = { instructions = it; localError = null },
            placeholder = "Instructions (markdown) — the steps and rules the agent follows " +
                "when this skill is active",
            supportingText = "This becomes the SKILL.md body — the content the agent actually reads.",
            singleLine = false,
            minLines = 4,
            maxLines = 12,
            shape = MaterialTheme.shapes.small,
            colors = cardFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        localError?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = {
                when {
                    name.isBlank() -> localError = "Skill name is required"
                    instructions.isBlank() -> localError =
                        "Instructions are required — a skill without them is an empty shell"
                    else -> {
                        // Do NOT clear the fields here — keep values in case the API call
                        // fails so the user can retry without re-typing. The parent section
                        // clears them by collapsing (and unmounting) the form only on success.
                        onAdd(name.trim(), description.trim(), instructions.trim())
                        localError = null
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add skill")
        }
    }
}

// ── Plugins section ───────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PluginsSection(
    plugins: List<ComponentInfo>,
    toggling: Set<String>,
    busy: Boolean,
    error: String?,
    onToggle: (ComponentInfo) -> Unit,
    onInstallPlugin: (id: String) -> Unit,
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

    SectionCard(
        title = "Plugins",
        trailing = { AddHeaderButton("Plugins") { if (!busy) addExpanded = !addExpanded } },
    ) {
        // One Column child — see SkillsSection: keeps the collapsed add-form from doubling the
        // header-to-content gap via the card's spacedBy(12).
        Column {
            // Progress indicator while any op is in flight
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

            // Add form (the bottom padding belongs to the expanded state so it animates away).
            AnimatedVisibility(
                visible = addExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(bottom = 12.dp)) {
                    AddPluginForm(
                        busy = busy,
                        onInstall = { id ->
                            submitting = true
                            onInstallPlugin(id)
                            // Form does NOT close here — closes via LaunchedEffect above on success.
                        },
                    )
                }
            }

            // Chips
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

    // No horizontal padding of its own — the enclosing SectionCard already insets its content.
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
                        // Do NOT clear `id` here — keep it in case the API fails so the user can
                        // retry. The parent section collapses the form (unmounting this composable)
                        // only on success, which naturally resets the field.
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

    SectionCard(
        title = "MCP servers",
        trailing = { AddHeaderButton("MCP servers") { if (!busy) addExpanded = !addExpanded } },
    ) {
        // One Column child — see SkillsSection: keeps the collapsed add-form from doubling the
        // header-to-content gap via the card's spacedBy(12).
        Column {
            // Add form (the bottom padding belongs to the expanded state so it animates away).
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
                                // Validation passed; API call is enqueued. Track the submit so the
                                // LaunchedEffect above can close the form when the op completes.
                                submitting = true
                            }
                            // Return validation error (if any) for the form to show inline.
                            validationErr
                        },
                    )
                }
            }

            // Chips — tap toggles the server globally (the backend parks the definition in
            // .claude.json's mcpServersDisabled); long-press removes it.
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
 * Inline form for adding an MCP server globally.
 * Mirrors the AddMcpForm in NewRequestScreen — transport toggle (stdio | HTTP), name, fields.
 * [onAdd] receives the draft and returns a nullable validation error string (null = validation
 * passed, API call enqueued). The form stays open after a failed API call so the user can retry.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddMcpForm(
    busy: Boolean,
    onAdd: (McpDraft) -> String?,
) {
    var draft by remember { mutableStateOf(McpDraft()) }
    var localError by remember { mutableStateOf<String?>(null) }

    // No horizontal padding of its own — the enclosing SectionCard already insets its content.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Transport toggle: stdio | HTTP/SSE. icon = {} drops the default selected-checkmark —
        // the filled segment already shows the selection ("no decorative symbols" rule).
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
            // HTTP/SSE type sub-toggle (icon = {} — no selected-checkmark)
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
                // Do NOT reset `draft` here — keep values so the user can retry if the API fails.
                // The parent section collapses the form (unmounting this composable) on success,
                // which naturally resets the draft for the next open.
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add MCP server")
        }
    }
}
