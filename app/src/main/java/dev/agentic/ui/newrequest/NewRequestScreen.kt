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
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import dev.agentic.data.net.ComponentInfo
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
import dev.agentic.ui.components.ComponentChip
import dev.agentic.ui.components.SectionCard
import dev.agentic.ui.components.SliderField
import dev.agentic.ui.components.cardFieldColors
import dev.agentic.ui.components.rememberSyncedTextFieldState
import dev.agentic.ui.components.VoiceDictationField
import dev.agentic.ui.components.clearFocusOnTap

// The model + effort option lists (raw value → slider label) live in [dev.agentic.ui.ModelEffortLabels]
// (SESSION_START_MODEL_OPTIONS / EFFORT_OPTIONS) as the shared source of truth, so these sliders and
// the session pills/chips that render the same values elsewhere never drift apart. The Model slider
// uses the Claude-only session-start list — BYOK provider models are delegate-routing-only.
// Slider convention: the leading "" notch is "Default" (no override); the VM stores null for that, so
// this screen translates "" ↔ null at the VM boundary.
// Permission mode the session launches in. Ordered left→right by ascending autonomy so the slider
// reads "least free" → "most free": Plan (read-only) · Ask (prompt each tool) · Accept edits ·
// Dangerous (auto-allow = today's default). VM stores null for Dangerous (back-compat); we map
// "bypassPermissions" ↔ null at the VM boundary so the default notch sends no override.
private val PERMISSION_MODES = listOf(
    "plan" to "Plan",
    "default" to "Ask",
    "acceptEdits" to "Accept edits",
    "bypassPermissions" to "Dangerous",
)

/**
 * New-request screen. Stateless: all state lives in [NewRequestViewModel]; this composable
 * reads [NewRequestUiState] and calls VM event handlers.
 *
 * Ports the look from old [dev.agentic.ui.NewRequestScreen] (NewRequestScreen.kt):
 * template chips, repo/skill multi-select with search, model/effort sliders, mode toggle
 * buttons, prompt field with voice dictation, submit button.
 *
 * [onCreated] is called with both the new session id and the submitted prompt so that the
 * caller (AppNav) can navigate to the Session screen with the optimistic prompt pre-loaded.
 *
 * VM creation note: [appContainer] is @Composable and must be called in the composable body;
 * the [vm] nullable parameter allows injection in tests / previews.
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

    // Capture the submitted prompt so we can pass it to onCreated when createdId arrives.
    // We store it in a remembered ref updated on every recomposition — when createdId fires,
    // s.prompt is still the value that was in the field at submit time (VM doesn't clear it).
    val submittedPromptRef = remember { mutableStateOf("") }
    submittedPromptRef.value = s.prompt

    // Navigate out once the VM signals a successful create.
    LaunchedEffect(s.createdId) {
        s.createdId?.let { id -> onCreated(id, submittedPromptRef.value) }
    }

    // When a template with {{vars}} is tapped, hold it here to show the dialog.
    var pendingTemplate by remember { mutableStateOf<Template?>(null) }

    // System file picker (Storage Access Framework — no runtime permission on any API level we ship).
    // The contract hands back read-granted URIs scoped to our activity; we grab the ContentResolver
    // from the same context so the VM's upload coroutine can openInputStream() each one.
    val ctx = LocalContext.current
    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        // applicationContext.contentResolver (not the Activity's): the upload runs in the VM's
        // coroutine scope, which can outlive a config change — using the app context avoids leaking
        // the destroyed Activity.
        if (uris.isNotEmpty()) realVm.attachFiles(uris, ctx.applicationContext.contentResolver)
    }
    // Block Launch while an upload is still in flight: submit() awaits uploads anyway, but gating
    // keeps the button from looking ready a frame before the staged paths are final.
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
                // Tap empty space on the form to blur the prompt / chip-filter field and drop the
                // keyboard. Chips, the field, and the Launch button still receive their own taps.
                .clearFocusOnTap()
                // Shrink the scroll viewport to sit above the IME (edge-to-edge + adjustResize means
                // the app applies the keyboard inset itself). The focused field then auto-scrolls into
                // view instead of being covered by the keyboard.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Page header (MD3 Expressive headline + subtitle, matching Models screen) ──
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "New request",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Configure a new session and launch it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Template picker (quick-start strip, above the cards) ──────────────────
            // Row is hidden when the backend returns no templates (opt-in server feature).
            if (s.templates.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Start from template", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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

            // ── Card 1 · Filters: repo + skill (tri-state) + plugin (tri-state) + MCP ──
            SectionCard("Filters") {
                // Repos: keep binary toggle (in/out, not a global setting)
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
                // Skills — binary effective-state chips: ON = globalEnabled resolved through override.
                // Tap toggles effective and sets the minimal override (Inherit when equal to global).
                if (s.availableSkillComponents.isNotEmpty()) {
                    ComponentChipPicker(
                        label = "Skills",
                        components = s.availableSkillComponents,
                        overrides = s.skillOverrides,
                        onToggle = { comp ->
                            val cur = s.skillOverrides[comp.id] ?: Override.Inherit
                            val eff = when (cur) {
                                Override.Inherit  -> comp.globalEnabled
                                Override.ForceOn  -> true
                                Override.ForceOff -> false
                            }
                            val newEff = !eff
                            val newOverride = if (newEff == comp.globalEnabled) Override.Inherit
                                              else if (newEff) Override.ForceOn
                                              else Override.ForceOff
                            realVm.setOverride("skill", comp.id, newOverride)
                        },
                    )
                }
                // Plugins — same binary effective-state logic.
                if (s.availablePluginComponents.isNotEmpty()) {
                    ComponentChipPicker(
                        label = "Plugins",
                        components = s.availablePluginComponents,
                        overrides = s.pluginOverrides,
                        displayLabel = { it.name.substringBefore('@') },
                        onToggle = { comp ->
                            val cur = s.pluginOverrides[comp.id] ?: Override.Inherit
                            val eff = when (cur) {
                                Override.Inherit  -> comp.globalEnabled
                                Override.ForceOn  -> true
                                Override.ForceOff -> false
                            }
                            val newEff = !eff
                            val newOverride = if (newEff == comp.globalEnabled) Override.Inherit
                                              else if (newEff) Override.ForceOn
                                              else Override.ForceOff
                            realVm.setOverride("plugin", comp.id, newOverride)
                        },
                    )
                }
                // MCP — tri-state chips for globally configured MCP servers + inline add form.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "MCP servers",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // Globally configured MCP servers as binary effective-state chips (FlowRow).
                    if (s.mcpComponents.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            s.mcpComponents.forEach { comp ->
                                val cur = s.mcpOverrides[comp.id] ?: Override.Inherit
                                val eff = when (cur) {
                                    Override.Inherit  -> comp.globalEnabled
                                    Override.ForceOn  -> true
                                    Override.ForceOff -> false
                                }
                                ComponentChip(
                                    label = comp.name,
                                    kind = "mcp",
                                    effective = eff,
                                    onClick = {
                                        val newEff = !eff
                                        val newOverride = if (newEff == comp.globalEnabled) Override.Inherit
                                                          else if (newEff) Override.ForceOn
                                                          else Override.ForceOff
                                        realVm.setOverride("mcp", comp.id, newOverride)
                                    },
                                )
                            }
                        }
                    }
                    // Extra (ad-hoc) added servers — shown as removable chips (FlowRow).
                    if (s.extraMcpServers.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            s.extraMcpServers.forEach { srv ->
                                InputChip(
                                    selected = false,
                                    onClick = {},
                                    label = { Text(srv.name) },
                                    trailingIcon = {
                                        IconButton(onClick = { realVm.removeMcpServer(srv.name) }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "Remove ${srv.name}")
                                        }
                                    },
                                )
                            }
                        }
                    }
                    // Collapsible "Add MCP server" form.
                    var addMcpExpanded by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { addMcpExpanded = !addMcpExpanded }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Add MCP server",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (addMcpExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (addMcpExpanded) "Collapse Add MCP" else "Expand Add MCP",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(
                        visible = addMcpExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        AddMcpForm(
                            draft = s.mcpDraft,
                            onDraftChange = realVm::updateMcpDraft,
                            onAdd = {
                                val err = realVm.addMcpServer()
                                if (err == null) addMcpExpanded = false
                                err
                            },
                        )
                    }
                }
            }

            // ── Card 2 · Request: prompt + session CLAUDE.md ─────────────────────────
            SectionCard("Request") {
                // Prompt field with voice dictation. Shape + colors match the other inputs so the
                // whole form is one filled, tonal, rounded field family (no pill-vs-square clash).
                VoiceDictationField(
                    value = s.prompt,
                    onValueChange = realVm::setPrompt,
                    maxLines = Int.MAX_VALUE,
                    placeholder = "Describe the task for this session…",
                    shape = MaterialTheme.shapes.small,
                    colors = cardFieldColors(),
                )
                // ── Attachments ──────────────────────────────────────────────────────
                // Pick files to send with the request. Each uploads to the pre-session staging area in
                // the background (the chip shows progress); on Launch the staged files are adopted into
                // the new session's uploads/ dir and referenced in the prompt's `[attached: ...]`
                // marker, so the agent can read them in its very first turn.
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
                // Pre-filled with DEFAULT_CLAUDE_MD (the multi-session worktree / PR / conflict
                // workflow); the backend writes it into the session dir so the session loads it as
                // project memory ON TOP of each repo's own CLAUDE.md. It is sent as-is on submit whether
                // or not this is expanded — collapsing only hides the editor, it does NOT clear the
                // value (the text lives in the VM, not this row's view state). Collapsed by default so
                // the long default doesn't dominate the form; tap the header to reveal the editor inline.
                var claudeExpanded by remember { mutableStateOf(false) }
                val claudeStatus = when {
                    s.claudeMd.isBlank() -> "None — no extra guidance"
                    s.claudeMd == DEFAULT_CLAUDE_MD -> "Using default rules"
                    else -> "Customized"
                }
                Column {
                    // Header row — the entire row is the expand/collapse toggle.
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
                                // Same weight as the card section headers (Filters / Request / Settings).
                                fontWeight = FontWeight.SemiBold,
                            )
                            // Reflects state without expanding: default vs edited vs cleared.
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
                    // Inline reveal of the editor (Expressive expand/fade, like the workflow accordion).
                    AnimatedVisibility(
                        visible = claudeExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        AppTextField(
                            // State overload: the catalog loads (repos/skills/templates) re-emit this
                            // screen's state while the user edits CLAUDE.md, which with the String overload
                            // would strand the caret. The bridge owns the caret and only re-feeds on a real
                            // text change.
                            state = rememberSyncedTextFieldState(s.claudeMd, realVm::setClaudeMd),
                            placeholder = "Cleared — the session gets no extra guidance.\n" +
                                "Add session-specific rules here, e.g.\n" +
                                "Run tests before committing.",
                            supportingText = "Default workflow rules for this multi-session environment, pre-filled. " +
                                "Edit or clear them for this session — sent as-is, layered on top of each " +
                                "repo's own CLAUDE.md.",
                            singleLine = false,
                            minLines = 4,
                            // Grow to fit the whole pre-filled default instead of capping the height and
                            // scrolling internally — a capped multi-line field inside the page's
                            // verticalScroll steals vertical drags to scroll its OWN text.
                            maxLines = Int.MAX_VALUE,
                            shape = MaterialTheme.shapes.small,
                            // Muted (unfocused) text so the pre-filled boilerplate reads as a de-emphasized
                            // default until you tap in; focus restores full-emphasis text.
                            colors = cardFieldColors(mutedUnfocusedText = true),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }

            // ── Card 3 · Settings: model / effort / permission sliders ───────────────
            SectionCard("Settings") {
                // VM stores null for "no override"; slider uses "" to mean the same thing.
                SliderField(
                    label = "Model",
                    options = SESSION_START_MODEL_OPTIONS,
                    value = s.model ?: "",
                    onSelect = { realVm.setModel(it.ifBlank { null }) },
                )
                // Effort — Ultracode is the top notch. Selecting it sets mode=ultracode (xhigh effort +
                // automatic dynamic-workflow orchestration); any lower notch clears mode and is a plain
                // effort override. VM stores null for "no override"; the slider uses "" for the same.
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
                    // Recolor the slider violet AND run the ultracode ripple across its track while the
                    // ultracode notch is selected (see SliderField — same wave as the session pill).
                    accentActive = s.mode == "ultracode",
                )
                // Default notch = Dangerous (today's behavior); VM null ⇒ "bypassPermissions".
                // Recolor the whole slider red on the Dangerous notch as a standing warning.
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

/**
 * Chip-group section with an inline filter field for a list of [ComponentInfo] components.
 * Each chip shows its EFFECTIVE state (globalEnabled resolved through override) via [ComponentChip].
 * Tap toggles effective state; [onToggle] is called with the clicked component so the caller can
 * compute the minimal override and call setOverride.
 * [displayLabel] extracts the chip label from a [ComponentInfo] (default = name).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComponentChipPicker(
    label: String,
    components: List<ComponentInfo>,
    overrides: Map<String, Override>,
    onToggle: (ComponentInfo) -> Unit,
    displayLabel: (ComponentInfo) -> String = { it.name },
) {
    var q by remember { mutableStateOf("") }
    val shown = components.filter { c ->
        q.isBlank() || c.name.contains(q, ignoreCase = true) || displayLabel(c).contains(q, ignoreCase = true)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTextField(
            value = q,
            onValueChange = { q = it },
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            shown.forEach { comp ->
                val cur = overrides[comp.id] ?: Override.Inherit
                val effective = when (cur) {
                    Override.Inherit  -> comp.globalEnabled
                    Override.ForceOn  -> true
                    Override.ForceOff -> false
                }
                ComponentChip(
                    label = displayLabel(comp),
                    kind = comp.kind,
                    effective = effective,
                    onClick = { onToggle(comp) },
                )
            }
        }
    }
}

/**
 * One-row, horizontally-scrolling chip group with an inline filter field (binary toggle).
 * Used only for Repos (which is a plain in/out selection, not a tri-state global override).
 * Chips keep source order; toggling does not reshuffle them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipPicker(
    label: String,
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    // Optional accent for the SELECTED (lit) chips. When both are non-null the lit chip is filled
    // in these colors — used by Skills to match the cyan skill inline card. Null (the default,
    // used by Repos) keeps FilterChip's stock Material 3 look.
    selectedContainer: Color? = null,
    selectedContent: Color? = null,
    // Chip text for an option. Lets Plugins show the short `<plugin>` name while the option value
    // (selection set + onToggle) stays the full `<plugin>@<marketplace>` id. Identity by default.
    displayLabel: (String) -> String = { it },
) {
    var q by remember { mutableStateOf("") }
    // Match against both the raw option and its display label, so typing either form filters.
    val shown = options.filter {
        q.isBlank() || it.contains(q, ignoreCase = true) || displayLabel(it).contains(q, ignoreCase = true)
    }
    // Default colors when no accent is supplied; an accented (filled) selected state otherwise.
    val chipColors = if (selectedContainer != null && selectedContent != null) {
        FilterChipDefaults.filterChipColors(
            selectedContainerColor = selectedContainer,
            selectedLabelColor = selectedContent,
            selectedLeadingIconColor = selectedContent,
        )
    } else {
        FilterChipDefaults.filterChipColors()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Inline chip filter built from AppTextField (NOT the round SearchBar) so it is the SAME
        // filled, tonal, rounded field as the prompt / CLAUDE.md inputs — one field family, no pill.
        // The leading magnifier keeps the "filter" meaning; the floating label names the group.
        AppTextField(
            value = q,
            onValueChange = { q = it },
            // Placeholder, NOT a floating label: on a borderless filled field a floating label
            // rises onto the (invisible) top outline and reads as overlapping the box. A placeholder
            // shows the same hint while empty and simply disappears on input. The magnifier carries
            // the accessible name for TalkBack.
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
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            shown.forEach { o ->
                FilterChip(
                    selected = o in selected,
                    onClick = { onToggle(o) },
                    label = { Text(displayLabel(o)) },
                    colors = chipColors,
                )
            }
        }
    }
}

/**
 * Inline form for adding an ad-hoc MCP server for this session.
 * Shows a transport toggle (stdio | HTTP); stdio reveals command + args + env;
 * HTTP/SSE reveals url + type + headers. Client-side validation mirrors backend.
 *
 * [onAdd] returns a nullable error string (non-null = show error, stay open; null = success).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddMcpForm(
    draft: McpDraft,
    onDraftChange: (McpDraft) -> Unit,
    onAdd: () -> String?,
) {
    var localError by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Transport toggle: stdio | HTTP/SSE
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf("stdio", "http").forEachIndexed { i, t ->
                SegmentedButton(
                    selected = draft.transport == t,
                    onClick = { onDraftChange(draft.copy(transport = t)) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                    label = { Text(if (t == "stdio") "stdio" else "HTTP / SSE") },
                )
            }
        }

        // Name field (always shown)
        AppTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            placeholder = "Server name (e.g. my-mcp)",
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = cardFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (draft.transport == "stdio") {
            AppTextField(
                value = draft.command,
                onValueChange = { onDraftChange(draft.copy(command = it)) },
                placeholder = "Command (e.g. /usr/bin/node server.js)",
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = cardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = draft.args,
                onValueChange = { onDraftChange(draft.copy(args = it)) },
                placeholder = "Args (optional, space-separated)",
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = cardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = draft.env,
                onValueChange = { onDraftChange(draft.copy(env = it)) },
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
                        onClick = { onDraftChange(draft.copy(httpType = t)) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                        label = { Text(t.uppercase()) },
                    )
                }
            }
            AppTextField(
                value = draft.url,
                onValueChange = { onDraftChange(draft.copy(url = it)) },
                placeholder = "URL (e.g. https://example.com/mcp)",
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = cardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = draft.headers,
                onValueChange = { onDraftChange(draft.copy(headers = it)) },
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
                val err = onAdd()
                localError = err
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Text("  Add MCP server")
        }
    }
}

/**
 * Dialog shown when a template has {{var}} placeholders.
 * Renders one OutlinedTextField per variable; "Apply" is enabled only when all are filled.
 */
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
