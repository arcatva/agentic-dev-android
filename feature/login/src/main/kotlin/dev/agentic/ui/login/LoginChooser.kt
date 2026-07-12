package dev.agentic.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginChooser(
    state: LoginUiState,
    onScan: () -> Unit,
    onManual: () -> Unit,
) {
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Rounded.AutoAwesome, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp),
            )
            Text("agentic-dev", style = MaterialTheme.typography.headlineMedium)

            ChooserCard(
                icon = Icons.Rounded.Wifi,
                title = "Scan LAN",
                subtitle = scanSubtitle(state),
                showSpinner = state.scanning,
                onClick = onScan,
            )
            ChooserCard(
                icon = Icons.Rounded.Keyboard,
                title = "Manual entry",
                subtitle = "Type the host address yourself",
                showSpinner = false,
                onClick = onManual,
            )
        }
    }
}

private fun scanSubtitle(state: LoginUiState): String = when {
    state.notOnLan -> "Not on a local network"
    state.scanning -> "Scanning…"
    state.results.isEmpty() -> "No servers found"
    else -> "Found ${state.results.size} server(s)"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChooserCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    showSpinner: Boolean,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Column(Modifier.fillMaxWidth(if (showSpinner) 0.8f else 1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showSpinner) LoadingIndicator()
        }
    }
}
