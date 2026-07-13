package dev.agentic.ui.newrequest

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.data.net.Template
import dev.agentic.di.appContainer
import dev.agentic.domain.UploadState
import dev.agentic.ui.AccentVioletContainer
import dev.agentic.ui.EFFORT_OPTIONS
import dev.agentic.ui.SESSION_START_MODEL_OPTIONS
import dev.agentic.ui.OnAccentVioletContainer
import dev.agentic.ui.drawUltracodeRipple
import dev.agentic.ui.rememberUltracodeRipplePhase
import dev.agentic.ui.components.AppTextField
import dev.agentic.ui.components.AttachmentChip
import dev.agentic.ui.components.CappedScrollColumn
import dev.agentic.ui.components.ComponentChip
import dev.agentic.ui.components.SectionCard
import dev.agentic.ui.components.SliderField
import dev.agentic.ui.components.cardFieldColors
import dev.agentic.ui.components.rememberSyncedTextFieldState
import dev.agentic.ui.components.VoiceDictationField
import dev.agentic.ui.components.CommandItem
import dev.agentic.ui.components.CommandPalette
import dev.agentic.ui.components.activeCommandQuery
import dev.agentic.ui.components.applyCommand
import dev.agentic.ui.components.filterCommands
import dev.agentic.ui.components.clearFocusOnTap

// Slider convention: leading "" notch = "Default" (no override); VM stores null, so the screen
// translates "" ↔ null at the VM boundary. Dangerous default notch ↔ null for back-compat.
private val PERMISSION_MODES = listOf(
    "plan" to "Plan",
    "default" to "Ask",
    "acceptEdits" to "Accept edits",
    "bypassPermissions" to "Dangerous",
)

/**
 * New-request screen. Stateless — state lives in [NewRequestViewModel]; [onCreated] is called with
 * both the new session id and submitted prompt so the caller can pre-load the optimistic prompt.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun NewRequestScreen(
    onBack: () -> Unit,
    onCreated: (id: String, prompt: String) -> Unit,
    vm: NewRequestViewModel? = null,
) {
    val container = appContainer()
    val realVm: NewRequestViewModel = vm ?: viewModel(
        factory = viewModelFactory {
            initializer { NewRequestViewModel(container.sessionsRepo) }
        },
    )
    val s by realVm.uiState.collectAsStateWithLifecycle()

    // Stored in a ref updated every recomposition because the VM doesn't clear s.prompt,
    // so when createdId fires the value is still the one the user had at submit time.
    val submittedPromptRef = remember { mutableStateOf("") }
    submittedPromptRef.value = s.prompt

    LaunchedEffect(s.createdId) {
        s.createdId?.let { id -> onCreated(id, submittedPromptRef.value) }
    }

    var pendingTemplate by remember { mutableStateOf<Template?>(null) }

    // System file picker via Storage Access Framework — no runtime permission on any API level we ship.
    val ctx = LocalContext.current
    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        // applicationContext (not Activity): upload runs in VM coroutine scope which can outlive
        // a config change; the app context avoids leaking the destroyed Activity.
        if (uris.isNotEmpty()) realVm.attachFiles(uris, ctx.applicationContext.contentResolver)
    }
    // Block Launch while an upload is still in flight: submit() awaits uploads, but gating keeps
    // the button from looking ready a frame before the staged paths are final.
    val uploading = s.attachments.any { it.state is UploadState.Uploading }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "back")
                    }
                },
                title = { Text("New request") },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .clearFocusOnTap()
                // imePadding: edge-to-edge + adjustResize means the app applies the keyboard inset
                // itself; the focused field then auto-scrolls into view instead of being covered.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Card 0 · Template picker (quick-start strip) ──────────────────────────
            if (s.templates.isNotEmpty()) {
                SectionCard("Start from template") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        s.templates.forEach { tmpl ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    if (tmpl.vars.isEmpty()) {
                                        realVm.applyTemplate(tmpl, emptyMap())
                                    } else {
                                        pendingTemplate = tmpl
                                    }
                                },
                                label = { Text(tmpl.name) },
                            )
                        }
                    }
                }
            }

            // ── Var-resolution dialog ────────────────────────────────────────────────
            pendingTemplate?.let { tmpl ->
                TemplateVarDialog(
                    template = tmpl,
                    onApply = { varValues ->
                        realVm.applyTemplate(tmpl, varValues)
                        pendingTemplate = null
                    },
                    onDismiss = { pendingTemplate = null },
                )
            }

            // ── Card 1 · Repos: which repositories this session works in ──────────────
            SectionCard("Repos") {
                ChipPicker(
                    label = "Repos",
                    options = s.availableRepos,
                    selected = s.selectedRepos.toSet(),
                    onToggle = { repo ->
                        val updated = if (repo in s.selectedRepos) s.selectedRepos - repo
                                      else s.selectedRepos + repo
                        realVm.setRepos(updated)
                    },
                )
            }

            // ── Card 2 · Request: prompt + session CLAUDE.md ─────────────────────────
            SectionCard("Request") {
                // `/` command palette — caret is the end of the string field while typing.
                val cmdQuery = activeCommandQuery(s.prompt, s.prompt.length)
                if (cmdQuery != null) {
                    CommandPalette(
                        candidates = filterCommands(
                            s.commands.map { CommandItem(it.name, it.description, it.argumentHint) },
                            cmdQuery.query,
                        ),
                        onPick = { picked ->
                            realVm.setPrompt(applyCommand(s.prompt, cmdQuery, picked.name).first)
                        },
                    )
                }
                VoiceDictationField(
                    value = s.prompt,
                    onValueChange = realVm::setPrompt,
                    maxLines = Int.MAX_VALUE,
                    placeholder = "Describe the task for this session…",
                    shape = MaterialTheme.shapes.small,
                    colors = cardFieldColors(),
                )
                // `/lfg` default toggle — shown only when lfg is available (compound-engineering installed).
                if (s.commands.any { it.name == "lfg" }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = s.lfgEnabled, onCheckedChange = realVm::setLfgEnabled)
                        Text(
                            "  Run via /lfg pipeline",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                // ── Attachments ──────────────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { attachLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Rounded.AttachFile, contentDescription = null)
                        Text("  Attach files")
                    }
                }
                if (s.attachments.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        s.attachments.forEach { att ->
                            AttachmentChip(att = att, onRemove = { realVm.removePending(att.id) })
                        }
                    }
                }
                // ── Session-scoped CLAUDE.md (collapsed by default) ──────────────────
                // Collapsing only hides the editor — the value lives in the VM, not view state.
                var claudeExpanded by remember { mutableStateOf(false) }
                val claudeStatus = when {
                    s.claudeMd.isBlank() -> "None — no extra guidance"
                    s.claudeMd == DEFAULT_CLAUDE_MD -> "Using default rules"
                    else -> "Customized"
                }
                Column {
                    // Header row — tapping the entire row toggles expand/collapse.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { claudeExpanded = !claudeExpanded }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "CLAUDE.md (optional)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                claudeStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            if (claudeExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (claudeExpanded) "Collapse CLAUDE.md" else "Expand CLAUDE.md",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(
                        visible = claudeExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        AppTextField(
                            // State overload (not String): catalog loads re-emit state while the user
                            // edits CLAUDE.md; the String overload would strand the caret. The bridge
                            // owns the caret and only re-feeds on a real text change.
                            state = rememberSyncedTextFieldState(s.claudeMd, realVm::setClaudeMd),
                            placeholder = "No extra guidance.",
                            supportingText = "Pre-filled defaults — edit or clear for this session.",
                            singleLine = false,
                            minLines = 4,
                            // maxLines=Int.MAX_VALUE (not a cap): a capped multi-line field inside the
                            // page's verticalScroll steals vertical drags to scroll its own text.
                            maxLines = Int.MAX_VALUE,
                            shape = MaterialTheme.shapes.small,
                            // mutedUnfocusedText: pre-filled boilerplate reads as de-emphasized default
                            // until tapped; focus restores full-emphasis text.
                            colors = cardFieldColors(mutedUnfocusedText = true),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }

            // ── Card 3 · Settings: model / effort / permission sliders ───────────────
            SectionCard("Settings") {
                SliderField(
                    label = "Model",
                    options = SESSION_START_MODEL_OPTIONS,
                    value = s.model ?: "",
                    onSelect = { realVm.setModel(it.ifBlank { null }) },
                )
                // Effort: Ultracode notch sets mode=ultracode + effort=xhigh (fused in one slider);
                // lower notches clear mode and apply a plain effort override.
                SliderField(
                    label = "Effort",
                    options = EFFORT_OPTIONS,
                    value = if (s.mode == "ultracode") "ultracode" else (s.effort ?: ""),
                    onSelect = { key ->
                        if (key == "ultracode") {
                            realVm.setMode("ultracode")
                            realVm.setEffort("xhigh")
                        } else {
                            realVm.setMode(null)
                            realVm.setEffort(key.ifBlank { null })
                        }
                    },
                    accentActive = s.mode == "ultracode",
                )
                // Permissions: VM null ⇒ "bypassPermissions" (Dangerous default notch, back-compat).
                val permMode = s.permissionMode ?: "bypassPermissions"
                SliderField(
                    label = "Permissions",
                    options = PERMISSION_MODES,
                    value = permMode,
                    onSelect = { key -> realVm.setPermissionMode(if (key == "bypassPermissions") null else key) },
                    dangerActive = permMode == "bypassPermissions",
                )
            }

            // ── Error ────────────────────────────────────────────────────────────────
            s.error?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // ── Submit (screen-level CTA, kept outside the cards as the single Full-shape action) ─
            Button(
                onClick = realVm::submit,
                enabled = s.prompt.isNotBlank() && !s.submitting && !uploading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.RocketLaunch, contentDescription = null)
                Text("  Launch session")
            }
        }
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

/** Caps a chip FlowRow at ~4 rows (152dp) so a long component list doesn't dominate the form. */
@Composable
private fun LimitedChipRows(content: @Composable () -> Unit) {
    CappedScrollColumn(maxHeight = 152.dp) { content() }
}

/**
 * Horizontally-scrolling chip group with an inline filter (used only for Repos — plain in/out,
 * not a tri-state global override). Chips keep source order; toggling does not reshuffle them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipPicker(
    label: String,
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    displayLabel: (String) -> String = { it },
) {
    var q by remember { mutableStateOf("") }
    // Filter matches both the raw option and its display label.
    val shown = options.filter {
        q.isBlank() || it.contains(q, ignoreCase = true) || displayLabel(it).contains(q, ignoreCase = true)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // AppTextField (not SearchBar) so the filter is the SAME filled, tonal, rounded field as
        // the prompt / CLAUDE.md inputs — one field family.
        AppTextField(
            value = q,
            onValueChange = { q = it },
            // Placeholder (not floating label): on a borderless filled field a floating label
            // rises onto the (invisible) top outline and reads as overlapping the box. The
            // magnifier carries the accessible name for TalkBack.
            placeholder = "Filter ${label.lowercase()}",
            singleLine = true,
            leadingIcon = {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = "Filter ${label.lowercase()}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (q.isNotEmpty()) {
                    IconButton(onClick = { q = "" }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Clear filter",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            shape = MaterialTheme.shapes.small,
            colors = cardFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        LimitedChipRows {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                shown.forEach { o ->
                    ComponentChip(
                        label = displayLabel(o),
                        kind = "repo",
                        effective = o in selected,
                        onClick = { onToggle(o) },
                    )
                }
            }
        }
    }
}

/** Dialog shown when a template has {{var}} placeholders; "Apply" only enabled when all are filled. */
@Composable
private fun TemplateVarDialog(
    template: Template,
    onApply: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val values = remember(template.name) {
        template.vars.associateWith { mutableStateOf("") }
    }
    val allFilled = values.values.all { it.value.isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fill in: ${template.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                template.vars.forEach { varName ->
                    val state = values[varName]!!
                    AppTextField(
                        value = state.value,
                        onValueChange = { state.value = it },
                        label = varName,
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        colors = cardFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(values.mapValues { it.value.value }) },
                enabled = allFilled,
            ) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
