package dev.agentic.ui.globalsettings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.data.net.ComponentInfo
import dev.agentic.di.appContainer

/**
 * Global Settings screen — lists all skills, plugins, and MCP components with on/off switches.
 *
 * Components are grouped by kind in display order: Skills → Plugins → MCP. Each row shows the
 * component name + description alongside a [Switch] bound to its [ComponentInfo.globalEnabled]
 * state. Flipping a switch calls [GlobalSettingsViewModel.toggle], which optimistically updates
 * the row, then sends `POST /api/global-settings/toggle`, applies the refreshed list on success,
 * or reverts and shows a transient snackbar error on failure.
 *
 * VM creation follows project convention (lifecycle-scoped factory via [viewModelFactory]).
 * The fake [appContainer] allows test injection.
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
            s.components.isEmpty() -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(pad),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No components found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                // Group by kind in display order. Unknown kinds land at the bottom.
                val kindOrder = listOf("skill", "plugin", "mcp")
                val grouped: Map<String, List<ComponentInfo>> = buildMap {
                    kindOrder.forEach { kind ->
                        val items = s.components.filter { it.kind == kind }
                        if (items.isNotEmpty()) put(kind, items)
                    }
                    // Any unknown kinds.
                    val known = kindOrder.toSet()
                    s.components.filter { it.kind !in known }.groupBy { it.kind }.forEach { (k, v) ->
                        put(k, v)
                    }
                }

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(pad)
                        .verticalScroll(rememberScrollState()),
                ) {
                    grouped.forEach { (kind, items) ->
                        SectionHeader(kind)
                        items.forEachIndexed { idx, component ->
                            ComponentRow(
                                component = component,
                                toggling = "${component.kind}:${component.id}" in s.toggling,
                                onToggle = { resolvedVm.toggle(component) },
                            )
                            if (idx < items.lastIndex) {
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

/** Section header showing the human-readable group name (Skills / Plugins / MCP). */
@Composable
private fun SectionHeader(kind: String) {
    val label = when (kind) {
        "skill"  -> "Skills"
        "plugin" -> "Plugins"
        "mcp"    -> "MCP"
        else     -> kind.replaceFirstChar { it.uppercaseChar() }
    }
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * One component row: name + description on the left, [Switch] on the right.
 * The whole row is a toggleable semantic unit (Role.Switch) so accessibility tools announce it correctly.
 */
@Composable
private fun ComponentRow(
    component: ComponentInfo,
    toggling: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .semantics { role = Role.Switch }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                component.name.ifBlank { component.id },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (component.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    component.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = component.globalEnabled,
            onCheckedChange = { if (!toggling) onToggle() },
            enabled = !toggling,
        )
    }
}
