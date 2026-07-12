package dev.agentic.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.agentic.data.net.DiscoveredServer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScanScreen(
    state: LoginUiState,
    onSelect: (String) -> Unit,
    onPassword: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onRescan: () -> Unit,
    onSubmit: () -> Unit,
    onManual: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan LAN") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onRescan, enabled = !state.scanning) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Rescan")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            if (state.scanning) {
                // Capture to a local val: a Float? property read inside the (non-inline) progress
                // lambda would not smart-cast, so `{ state.scanProgress }` is `() -> Float?` and
                // fails to match the `() -> Float` overload. A local val smart-casts into the lambda.
                val progress = state.scanProgress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            when {
                state.notOnLan -> EmptyState("Not on a local network", "Connect to Wi-Fi, or enter the host manually.", onManual)
                !state.scanning && state.results.isEmpty() ->
                    EmptyState("No servers found", "Make sure the server is running on port 7420.", onManual)
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(state.results, key = { it.baseUrl }) { server ->
                        ServerRow(server, selected = server.baseUrl == state.selectedHost, onSelect = { onSelect(server.baseUrl) })
                    }
                }
            }

            // Password + connect appear once at least one server is present.
            if (state.results.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PasswordField(
                        state,
                        onPassword,
                        onTogglePassword,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (!state.busy && state.selectedHost != null && state.password.isNotEmpty()) onSubmit()
                            },
                        ),
                    )
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = onSubmit,
                        enabled = !state.busy && state.selectedHost != null && state.password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.busy) "…" else "Connect") }
                }
            }
        }
    }
}

@Composable
private fun ServerRow(server: DiscoveredServer, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        leadingContent = { Icon(Icons.Rounded.Computer, contentDescription = null) },
        headlineContent = { Text("${server.ip}:${server.port}") },
        supportingContent = { Text("${server.latencyMs} ms") },
        trailingContent = { RadioButton(selected = selected, onClick = onSelect) },
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    )
}

@Composable
private fun EmptyState(title: String, body: String, onManual: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onManual) { Text("Enter manually") }
    }
}
