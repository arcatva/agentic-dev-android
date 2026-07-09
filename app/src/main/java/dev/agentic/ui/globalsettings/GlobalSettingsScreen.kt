package dev.agentic.ui.globalsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import dev.agentic.ui.components.ComponentChip

/**
 * Global Settings screen — lists all skills, plugins, and MCP components as colored chips in
 * FlowRow groups (same visual language as the New Request screen).
 *
 * Skills and plugins are interactive: tapping a chip calls [GlobalSettingsViewModel.toggle].
 * MCP chips are read-only (non-interactive, 0.5 alpha, "managed per-session" caption); the
 * MCP section is always rendered — even if empty it shows a placeholder.
 *
 * Components are grouped: Skills → Plugins → MCP.
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
                    if (skills.isNotEmpty()) {
                        ChipGroupSection(
                            label = "Skills",
                            components = skills,
                            toggling = s.toggling,
                            readOnly = false,
                            onToggle = { resolvedVm.toggle(it) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    // ── Plugins ──────────────────────────────────────────────────
                    if (plugins.isNotEmpty()) {
                        ChipGroupSection(
                            label = "Plugins",
                            components = plugins,
                            toggling = s.toggling,
                            readOnly = false,
                            onToggle = { resolvedVm.toggle(it) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    // ── MCP (always rendered, read-only, "managed per-session") ──
                    SectionHeader("MCP")
                    if (mcps.isEmpty()) {
                        Text(
                            "No MCP servers",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                    } else {
                        ChipGroupSection(
                            label = null,   // header already rendered above
                            components = mcps,
                            toggling = s.toggling,
                            readOnly = true,
                            onToggle = { /* read-only */ },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

/** Section header showing the human-readable group name (Skills / Plugins / MCP). */
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

/**
 * A labeled group of [ComponentChip]s rendered in a [FlowRow].
 *
 * If [label] is non-null, a [SectionHeader] is rendered first. Pass null to suppress the header
 * when it has already been rendered by the caller (e.g. the MCP section, where the header is
 * rendered before the empty-state check so it always appears).
 *
 * When [readOnly] is true all chips are non-interactive (0.5 alpha, "managed per-session" caption).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipGroupSection(
    label: String?,
    components: List<ComponentInfo>,
    toggling: Set<String>,
    readOnly: Boolean,
    onToggle: (ComponentInfo) -> Unit,
) {
    if (label != null) SectionHeader(label)
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val toggleKey = { c: ComponentInfo -> "${c.kind}:${c.id}" }
        components.forEach { component ->
            val isToggling = toggleKey(component) in toggling
            ComponentChip(
                label = component.name.ifBlank { component.id },
                kind = component.kind,
                effective = component.globalEnabled,
                onClick = { if (!isToggling) onToggle(component) },
                enabled = !readOnly && !isToggling,
                readOnlyCaption = if (readOnly) "managed per-session" else null,
            )
        }
    }
}
