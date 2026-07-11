package dev.agentic.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.di.appContainer
import dev.agentic.ui.EFFORT_OPTIONS
import dev.agentic.ui.SESSION_START_MODEL_OPTIONS
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import dev.agentic.ui.components.SectionCard
import dev.agentic.ui.components.SliderField

/** Per-session settings subpage (session-persistent). MD3 Expressive layout matching NewRequestScreen/GlobalSettingsScreen. */

/** Mirrors NewRequestScreen.PERMISSION_MODES; null session reads as the Dangerous notch (bypassPermissions on backend). */
private val PERMISSION_MODES_FOR_SETTINGS = listOf(
    "plan" to "Plan",
    "default" to "Ask",
    "acceptEdits" to "Accept edits",
    "bypassPermissions" to "Dangerous",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SessionSettingsScreen(
    sessionId: String,
    onBack: () -> Unit,
) {
    val container = appContainer()
    val vm: SessionSettingsViewModel = viewModel(
        key = "sessionSettings:$sessionId",
        factory = viewModelFactory {
            initializer {
                SessionSettingsViewModel(container.sessionsRepo, sessionId)
            }
        },
    )
    val s by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "back")
                    }
                },
                title = { Text("Session settings") },
            )
        },
    ) { pad ->
        if (s.loading) {
            // Spinner avoids rendering sliders with stale fallback values during the initial fetch.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ── Card 1 · Settings: model / effort / permission sliders ───────────────
                SectionCard("Settings") {
                    SliderField(
                        label = "Model",
                        options = SESSION_START_MODEL_OPTIONS,
                        value = s.pendingModel ?: "",
                        onSelect = { vm.setPendingModel(it.ifBlank { null }) },
                    )
                    SliderField(
                        label = "Effort",
                        options = EFFORT_OPTIONS,
                        value = if (s.pendingMode == "ultracode") "ultracode" else (s.pendingEffort ?: ""),
                        onSelect = { key ->
                            if (key == "ultracode") {
                                vm.setPendingMode("ultracode")
                                vm.setPendingEffort("xhigh")
                            } else {
                                vm.setPendingMode(null)
                                vm.setPendingEffort(key.ifBlank { null })
                            }
                        },
                        accentActive = s.pendingMode == "ultracode",
                    )
                    SliderField(
                        label = "Permissions",
                        options = PERMISSION_MODES_FOR_SETTINGS,
                        // Store the literal key so saveToSession() patches a concrete value (null = no change).
                        value = s.pendingPermissionMode ?: "bypassPermissions",
                        onSelect = { vm.setPendingPermissionMode(it) },
                        dangerActive = (s.pendingPermissionMode ?: "bypassPermissions") == "bypassPermissions",
                    )
                }

                // ── Card 2 · Reliability: auto-resume after usage limit ──────────────────
                SectionCard("Reliability") {
                    SettingsSwitchRow(
                        label = "Auto-resume after usage limit",
                        supporting = buildAutoResumeText(s.session?.autoResumeAt),
                        checked = s.pendingAutoResume ?: (s.session?.autoResume ?: true),
                        onCheckedChange = { vm.setPendingAutoResume(it) },
                    )
                }

                // ── Card 3 · Claude Code: hand this session off to a terminal claude --resume ─
                SectionCard("Claude Code") {
                    if (s.session?.detached == true) {
                        Text(
                            "Handed off to a terminal — a claude --resume session is driving this. " +
                                "Reopen it here to re-sync the terminal-added turns.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = { vm.detach() },
                        enabled = !s.detaching && !s.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (s.detaching) "Handing off…" else "Open in Claude Code")
                    }
                    s.resumeCmd?.let { cmd ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Run this in a terminal on the host to take over the session:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            cmd,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        val clipboard = LocalClipboardManager.current
                        TextButton(onClick = { clipboard.setText(AnnotatedString(cmd)) }) {
                            Text("Copy command")
                        }
                    }
                }

                // ── Error ────────────────────────────────────────────────────────────────
                s.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                // ── Save (screen-level CTA, outside the cards) ─
                Button(
                    onClick = { vm.saveToSession() },
                    enabled = !s.saving && !s.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null)
                    Text(if (s.saving) "  Saving…" else "  Save to session")
                }
            }
        }
    }
}

/** Auto-resume supporting text; appends scheduled time in user's zone when [autoResumeAt] non-null. */
private fun buildAutoResumeText(autoResumeAt: Long?): String {
    val base = "When a 5h/7d usage limit interrupts this session, resume automatically once it resets"
    if (autoResumeAt == null) return base
    val formatted = try {
        java.time.Instant.ofEpochMilli(autoResumeAt)
            .atZone(java.time.ZoneId.systemDefault())
            .format(
                java.time.format.DateTimeFormatter
                    .ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
            )
    } catch (e: Exception) {
        autoResumeAt.toString()
    }
    return "$base\nScheduled: $formatted"
}

/** Switch row for use inside a [SectionCard]; whole row toggles for touch target + a11y; Switch is display-only (onCheckedChange=null). */
@Composable
private fun SettingsSwitchRow(
    label: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
