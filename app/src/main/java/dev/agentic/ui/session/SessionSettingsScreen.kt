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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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

/**
 * Per-session settings subpage — session-persistent only.
 *
 * MD3 Expressive structure, matching NewRequestScreen / ProvidersScreen: a bold in-page
 * headline + subtitle, controls grouped into tonal [SectionCard]s ("Settings" for the
 * model/effort/permission sliders, "Reliability" for the auto-resume switch), and a single
 * full-width CTA outside the cards that PATCHes `/api/sessions/:id` via
 * [SessionSettingsViewModel.saveToSession].
 *
 * VM creation note: project convention (see AdaptiveHome.kt / SessionScreen.kt) is to build VMs
 * with `viewModel(key = ..., factory = viewModelFactory { initializer { ... } })` so they scope
 * to the current [ViewModelStoreOwner] and survive configuration changes. The brief's option (b)
 * `remember(sessionId) { ... }` would re-create the VM on every rotation, discarding pending
 * selections mid-edit — using the lifecycle-scoped factory preserves the project pattern and
 * keeps pending edits stable across device rotation.
 */
// Mirrors NewRequestScreen.PERMISSION_MODES exactly (same keys, same labels, same order) so the same
// session reads identically on both screens. The old "" -> "Default (Ask)" notch was wrong twice over:
// a session created as Dangerous stores null, which fell onto that index-0 notch (so Dangerous showed
// as "Default (Ask)"); and the label lied — the backend treats null/"" as bypassPermissions (auto-allow),
// the OPPOSITE of Ask. Dangerous is now an explicit "bypassPermissions" value (see the slider below):
// patch semantics require a concrete value to switch a session TO Dangerous (a null patch = "no change").
private val PERMISSION_MODES_FOR_SETTINGS = listOf(
    "plan" to "Plan",
    "default" to "Ask",
    "acceptEdits" to "Accept edits",
    "bypassPermissions" to "Dangerous",
)

@OptIn(ExperimentalMaterial3Api::class)
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
            // While the session fetch is in flight, show a spinner — don't render the sliders with
            // stale fallback values ("Default" model, "Default" effort, "Dangerous" permissions).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
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
                // ── Page header (MD3 Expressive headline + subtitle, matching NewRequest/Models) ──
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Session settings",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Saved to this session and applied to every turn",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── Card 1 · Settings: model / effort / permission sliders ───────────────
                // Same card + slider family as NewRequestScreen's "Settings" card.
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
                        // null (a Dangerous-created session, or any pre-permission-mode session) reads as the
                        // Dangerous notch, matching NewRequestScreen. Store the literal key — including
                        // "bypassPermissions" — so saveToSession() patches a concrete value (null = no change).
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

                // ── Save (screen-level CTA, outside the cards — mirrors Launch on NewRequest) ─
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

/** Supporting text for the auto-resume switch. When [autoResumeAt] is non-null, appends the
 *  scheduled time formatted in the user's time zone. */
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

/**
 * Switch row for use INSIDE a [SectionCard] — label + supporting text on the left (the same
 * titleSmall-SemiBold / bodySmall-onSurfaceVariant pair as NewRequest's collapsible CLAUDE.md
 * header row), switch on the right. The whole row toggles (bigger touch target + proper switch
 * semantics for accessibility); the Switch itself is display-only (onCheckedChange = null).
 */
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
