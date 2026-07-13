@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package dev.agentic.ui.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.data.net.NativeFamily
import dev.agentic.data.net.NativeOverrideReq
import dev.agentic.data.net.NewProviderReq
import dev.agentic.data.net.Provider
import dev.agentic.di.appContainer
import dev.agentic.ui.AccentBlue
import dev.agentic.ui.AccentBlueContainer
import dev.agentic.ui.AccentViolet
import dev.agentic.ui.AccentVioletContainer
import dev.agentic.ui.OnAccentBlue
import dev.agentic.ui.OnAccentBlueContainer
import dev.agentic.ui.OnAccentViolet
import dev.agentic.ui.OnAccentVioletContainer
import dev.agentic.ui.components.AppTextField
import dev.agentic.ui.components.FloatSliderField
import dev.agentic.ui.components.SectionCard
import dev.agentic.ui.components.cardFieldColors
import kotlinx.coroutines.delay

/** One-tap presets: fill the endpoint + protocol + a default model so adding a model is just
 *  "pick a preset, paste the key". Endpoints are the providers' Anthropic-compatible URLs where
 *  available; openai-protocol ones are reached through the LiteLLM proxy. */
private data class ProviderPreset(
    val title: String,
    val name: String,
    val baseUrl: String,
    val protocol: String,
    val model: String,
) {
    companion object {
        val ALL = listOf(
            ProviderPreset("MiniMax", "minimax", "https://api.minimaxi.com/anthropic", "anthropic", "MiniMax-M3"),
            ProviderPreset("DeepSeek", "deepseek", "https://api.deepseek.com/anthropic", "anthropic", "deepseek-chat"),
            ProviderPreset("Claude API", "claude", "https://api.anthropic.com", "anthropic", "claude-3-5-haiku-latest"),
            ProviderPreset("OpenAI", "openai", "https://api.openai.com/v1", "openai", "gpt-4o-mini"),
        )
    }
}

/** Mutable holder for the add / edit form so the whole form can be passed around as one value and
 *  reset / prefilled in one call. [editing] = true means we're changing an EXISTING provider
 *  (its name is the identity key and is locked); false means we're adding a new one. */
private class ProviderFormState {
    var name by mutableStateOf("")
    var baseUrl by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var model by mutableStateOf("")
    var protocol by mutableStateOf("anthropic")
    var capability by mutableFloatStateOf(0.5f)
    var priority by mutableFloatStateOf(0.5f)
    var cost by mutableFloatStateOf(0.5f)
    var router by mutableStateOf(false)
    var enabled by mutableStateOf(true)
    var description by mutableStateOf("")
    var editing by mutableStateOf(false)

    fun reset() {
        name = ""
        baseUrl = ""
        apiKey = ""
        model = ""
        protocol = "anthropic"
        capability = 0.5f
        priority = 0.5f
        cost = 0.5f
        router = false
        enabled = true
        description = ""
        editing = false
    }

    /** Prefill from an existing provider for in-place editing. The API key is intentionally left
     *  blank — it's write-only (the server never returns it), so the user must re-enter it to save. */
    fun loadFrom(p: Provider) {
        name = p.name
        baseUrl = p.baseUrl
        apiKey = ""
        model = p.model
        protocol = p.protocol
        capability = p.capability
        priority = p.priority
        cost = p.cost
        router = p.router
        enabled = p.enabled
        description = p.description.orEmpty()
        editing = true
    }

    fun toReq() = NewProviderReq(
        name = name.trim(), baseUrl = baseUrl.trim(), apiKey = apiKey,
        model = model.trim(), protocol = protocol, capability = capability,
        description = description.trim().ifBlank { null }, priority = priority, cost = cost, router = router,
        enabled = enabled,
    )

    val valid: Boolean
        get() = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank() &&
            (editing || apiKey.isNotBlank())
}

/**
 * Models management sections — the BYOK provider registry, embedded in the GLOBAL SETTINGS page
 * (this replaced the standalone "Models" screen: models and component settings now live on one page).
 *
 * Renders two tonal SectionCards: "Router" (the active router card) and "Sub-agent models"
 * (every other model). The Sub-agent models card carries an "Add" header action — the add/edit
 * details stay hidden until the user taps Add (or Edit on a card), matching the
 * Skills / Plugins / MCP servers sections above it.
 *
 * Self-contained: owns its ViewModel, the delete-confirm dialog, and the add/edit form state.
 * Emits its cards as siblings into the caller's Column (which provides the 12dp rhythm).
 */
@Composable
fun ModelsSections() {
    val container = appContainer()
    val vm: ProvidersViewModel = viewModel(
        factory = viewModelFactory { initializer { ProvidersViewModel(container.providersRepo) } },
    )
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val form = remember { ProviderFormState() }
    // The add/edit details are hidden until Add is tapped (or Edit on a card).
    var formVisible by remember { mutableStateOf(false) }
    var providerToDelete by remember { mutableStateOf<String?>(null) }

    // Deleting a provider is destructive and the API key is write-only.
    val pending = providerToDelete
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { providerToDelete = null },
            title = { Text("Delete provider?") },
            text = { Text("Delete \"$pending\"? This can't be undone — you'll have to re-enter its API key.") },
            confirmButton = {
                TextButton(onClick = {
                    providerToDelete = null
                    if (form.editing && form.name == pending) {
                        form.reset()
                        formVisible = false
                    }
                    vm.remove(pending)
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { providerToDelete = null }) { Text("Cancel") }
            },
        )
    }

    val err = ui.error
    if (err != null) {
        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    // ── Global routing preference — the single cost⇄quality knob feeding the delegate router ──
    SectionCard("Routing") {
        // Local drag state; re-seeded when the loaded value arrives. Persist on release only (the
        // slider is continuous, so saving every tick would spam the API).
        var tradeoff by remember(ui.tradeoff) { mutableFloatStateOf(ui.tradeoff) }
        FloatSliderField(
            label = "Prefer cheaper ⇄ stronger",
            value = { tradeoff },
            onValueChange = { tradeoff = it },
            onValueChangeFinished = { vm.saveTradeoff(tradeoff) },
        )
        Text(
            "0 = always the cheapest capable model · 1 = always the strongest. " +
                "Per-model priority fine-tunes within near-ties.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val router = ui.providers.firstOrNull { it.router }
    val currentRouter = router?.name
    val onEdit: (Provider) -> Unit = { form.loadFrom(it); formVisible = true }
    val onDelete: (String) -> Unit = { providerToDelete = it }

    // ── Router — a standalone card; the violet container makes it self-evident ──
    if (router != null) {
        SectionCard("Router") {
            key(router.name) { ProviderCard(router, ui.busy, onEdit, onDelete, inRouterSection = true) }
        }
    }

    // ── Sub-agent models — every non-router model + the Add-toggled add/edit form ──
    SectionCard(
        title = "Sub-agent models",
        trailing = {
            IconButton(onClick = {
                if (formVisible) {
                    formVisible = false
                } else {
                    form.reset()
                    formVisible = true
                }
            }) {
                Icon(
                    if (formVisible) Icons.Rounded.Close else Icons.Rounded.Add,
                    contentDescription = if (formVisible) "Close model form" else "Add a model",
                )
            }
        },
    ) {
        // One Column child — a collapsed AnimatedVisibility is zero-height and would otherwise
        // double the card's spacedBy(12) gap (same fix as the Global settings sections).
        Column {
            val others = ui.providers.filter { it != router }
            when {
                ui.loading -> LoadingIndicator()
                // Hidden while the form is open — "tap Add" would point at a form that's
                // already right below the message.
                others.isEmpty() && !formVisible -> Text(
                    "No sub-agent models — tap Add.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    others.forEach { p ->
                        key(p.name) { ProviderCard(p, ui.busy, onEdit, onDelete) }
                    }
                }
            }
            // The add-model details only appear after tapping Add (or Edit on a card) — below the list.
            val formBringIntoView = remember { BringIntoViewRequester() }
            AnimatedVisibility(
                visible = formVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    Modifier
                        .padding(top = 12.dp)
                        .bringIntoViewRequester(formBringIntoView),
                ) {
                    AddOrEditForm(
                        form = form,
                        busy = ui.busy,
                        currentRouter = currentRouter,
                        onSubmit = {
                            vm.add(form.toReq()) { e ->
                                if (e == null) {
                                    form.reset()
                                    formVisible = false
                                }
                            }
                        },
                    )
                }
            }
            // Scroll the revealed form into view — Edit on the ROUTER card (top of the page) would
            // otherwise open the form a full card below with no visible feedback. Keyed on the form
            // identity too, so Edit on another card re-scrolls even while already open. The page's
            // scroll state lives in GlobalSettingsScreen; bringIntoView reaches it through the
            // scrollable ancestor chain. The short delay lets the expand animation gain height first.
            LaunchedEffect(formVisible, form.editing, form.name) {
                if (formVisible) {
                    delay(150)
                    formBringIntoView.bringIntoView()
                }
            }
        }
    }

    // Claude Code official (native) models — per-family routing override editor.
    NativeModelsSection()
}

// ── Router color helpers ──────────────────────────────────────────────────
// Every provider card gets a tinted container: violet for the router, blue for everyone else,
// so non-router models read as colored peers rather than flat gray rows.

@Composable
private fun routerColors(p: Provider): Triple<Color, Color, Color> {
    val isRouter = p.router
    val container = if (isRouter) AccentVioletContainer else AccentBlueContainer
    val onContainer = if (isRouter) OnAccentVioletContainer else OnAccentBlueContainer
    val muted = onContainer.copy(alpha = 0.75f)
    return Triple(container, onContainer, muted)
}

/**
 * Fades the trailing edge of the content into transparency instead of clipping it hard.
 * Used on the provider name so that when the Router badge / action buttons crowd it, the
 * last letters melt away (渐隐) rather than getting an abrupt "…" or a hard cut.
 *
 * Implementation: render the node into an offscreen layer, then multiply the trailing
 * [width] strip's alpha with a horizontal gradient (DstIn keeps destination pixels scaled
 * by the gradient's alpha). No-op while [enabled] is false, so unclipped names pay nothing.
 */
private fun Modifier.fadeTrailingEdge(enabled: Boolean, width: Dp = 28.dp): Modifier =
    if (!enabled) this
    else this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val w = width.toPx().coerceAtMost(size.width)
            val rtl = layoutDirection == LayoutDirection.Rtl
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startX = if (rtl) w else size.width - w,
                    endX = if (rtl) 0f else size.width,
                ),
                topLeft = Offset(if (rtl) 0f else size.width - w, 0f),
                size = Size(w, size.height),
                blendMode = BlendMode.DstIn,
            )
        }

/** Dim factor applied to a model card whose `enabled` flag is off (excluded from routing). */
private const val DISABLED_CARD_ALPHA = 0.4f

// ── Provider card (Expressive: lives INSIDE a SectionCard now) ──────────────

@Composable
private fun ProviderCard(
    p: Provider,
    busy: Boolean,
    onEdit: (Provider) -> Unit,
    onDelete: (String) -> Unit,
    // True when this card is rendered inside the SectionCard("Router") group. The section
    // header + violet container already say "this is the router", so the in-card Router
    // badge would be redundant there — it only shows for a router card rendered elsewhere.
    inRouterSection: Boolean = false,
) {
    val (container, onContainer, muted) = routerColors(p)
    val avatarBg = if (p.router) AccentViolet else AccentBlue
    val avatarFg = if (p.router) OnAccentViolet else OnAccentBlue
    val capColor = if (p.router) AccentViolet else AccentBlue
    val prioColor = onContainer
    val costColor = onContainer.copy(alpha = 0.85f)
    val track = onContainer.copy(alpha = 0.22f)

    // MD3 Expressive: use theme shape tokens (large = 24dp) instead of a hardcoded literal
    // so radius stays consistent if the theme ever changes.
    Surface(
        color = container,
        shape = MaterialTheme.shapes.large,
        // Disabled models are dimmed — they're excluded from routing until re-enabled.
        modifier = Modifier.fillMaxWidth().alpha(if (p.enabled) 1f else DISABLED_CARD_ALPHA),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(avatarBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        p.name.firstOrNull()?.uppercase() ?: "?",
                        color = avatarFg,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Overflow is a fade-out, not an ellipsis: when the badge/actions crowd the
                        // name, the trailing letters melt into transparency instead of a hard "…" cut.
                        var nameOverflows by remember { mutableStateOf(false) }
                        Text(
                            p.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            onTextLayout = { nameOverflows = it.hasVisualOverflow },
                            // Weighted + fill = false so the name yields room to the router badge
                            // when long, instead of starving the badge of width — which forced
                            // "Router" to wrap one letter per line and overlap the name.
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .fadeTrailingEdge(enabled = nameOverflows),
                        )
                        if (p.router && !inRouterSection) {
                            Spacer(Modifier.width(8.dp))
                            // Router badge — a compact pill with star icon. Title case, NOT all-caps,
                            // consistent with the SectionCard header style. softWrap = false keeps
                            // "Router" on a single line even when horizontal space is tight.
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp)).background(AccentViolet).padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Star, contentDescription = null, tint = OnAccentViolet, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Router",
                                    color = OnAccentViolet,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                    Text(
                        "${p.model} · ${p.protocol}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onEdit(p) }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Edit ${p.name}", tint = onContainer)
                }
                IconButton(onClick = { onDelete(p.name) }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete ${p.name}", tint = onContainer)
                }
            }

            val desc = p.description
            if (!desc.isNullOrBlank()) {
                Surface(
                    color = onContainer.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyLarge,
                        color = onContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // Metric rows — sentence case labels (not ALL-CAPS), matching MD3 label treatment.
            MetricRow("Capability", p.capability, capColor, track, muted, showLabel = true)
            MetricRow("Priority", p.priority, prioColor, track, muted, showLabel = true)
            MetricRow("Cost", p.cost, costColor, track, muted, showLabel = true)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (p.hasKey) Icons.Rounded.Star else Icons.Rounded.Close,
                    contentDescription = null,
                    tint = if (p.hasKey) capColor else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (p.hasKey) "Key stored" else "No API key",
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    p.baseUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Shared metric row (sentence case, MD3 consistent) ──────────────────────

@Composable
private fun MetricRow(
    label: String,
    value: Float,
    color: Color,
    trackColor: Color,
    labelColor: Color,
    showLabel: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(if (showLabel) 90.dp else 82.dp),
        )
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            color = color,
            trackColor = trackColor,
            modifier = Modifier.weight(1f),
        )
        Text(
            "%.2f".format(value),
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(40.dp).padding(start = 10.dp),
        )
    }
}

// ── Add / Edit form (unified for all variants, MD3 Expressive consistent) ───

@Composable
private fun AddOrEditForm(
    form: ProviderFormState,
    busy: Boolean,
    currentRouter: String?,
    onSubmit: () -> Unit,
) {
    // Plain column — this form now lives INSIDE the "Sub-agent models" SectionCard (revealed by
    // its Add header action), so it carries a titleSmall subsection header instead of a card.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (form.editing) "Editing ${form.name}" else "Add a model",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        // Presets — FilterChip matching NewRequestScreen's template chips.
        // Section sub-header uses titleSmall + SemiBold (NOT labelMedium + Bold ALL-CAPS),
        // consistent with every other section header across both screens.
        if (!form.editing) {
            Text(
                "Quick presets",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProviderPreset.ALL.forEach { preset ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            form.name = preset.name
                            form.baseUrl = preset.baseUrl
                            form.protocol = preset.protocol
                            form.model = preset.model
                        },
                        label = { Text(preset.title) },
                        enabled = !busy,
                    )
                }
            }
        }

        // Filled, borderless fields — same family as NewRequestScreen (cardFieldColors + shapes.small).
        // One shared source of truth so these fields never drift from the prompt / filter fields.
        val fieldShape = MaterialTheme.shapes.small
        val fieldColors = cardFieldColors()

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppTextField(
                value = form.name, onValueChange = { form.name = it },
                placeholder = "Name", singleLine = true,
                enabled = !busy, readOnly = form.editing,
                shape = fieldShape, colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            if (form.editing) {
                Text(
                    "Delete & re-add to rename",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AppTextField(
            value = form.baseUrl, onValueChange = { form.baseUrl = it },
            placeholder = "Endpoint (base URL)", singleLine = true, enabled = !busy,
            shape = fieldShape, colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppTextField(
                value = form.apiKey, onValueChange = { form.apiKey = it },
                placeholder = if (form.editing) "API key (leave blank to keep)" else "API key",
                singleLine = true, enabled = !busy,
                shape = fieldShape, colors = fieldColors,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (form.editing) {
                Text(
                    "Blank keeps the current key",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AppTextField(
            value = form.model, onValueChange = { form.model = it },
            placeholder = "Model name", singleLine = true, enabled = !busy,
            shape = fieldShape, colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )

        // Protocol selector — section sub-header uses titleSmall + SemiBold.
        Text(
            "Protocol",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        ProtocolSelector(
            selected = form.protocol,
            enabled = !busy,
            onSelect = {
                form.protocol = it
                if (it == "openai") form.router = false
            },
        )

        // Capability / priority / cost — continuous ("无极") sliders bound straight to the Float
        // form fields. No discrete option set: the thumb moves freely (steps = 0), so any value in
        // 0..1 is selectable, and the backend router compares these as raw f32 (EPS = 1e-4), so the
        // extra precision is meaningful. The label shows the live value to two decimals.
        FloatSliderField(
            label = "Capability",
            value = { form.capability },
            onValueChange = { form.capability = it },
        )
        FloatSliderField(
            label = "Scheduling priority (higher = preferred)",
            value = { form.priority },
            onValueChange = { form.priority = it },
        )
        FloatSliderField(
            label = "Relative cost (0 = cheapest)",
            value = { form.cost },
            onValueChange = { form.cost = it },
        )

        AppTextField(
            value = form.description, onValueChange = { form.description = it },
            placeholder = "Good at (the router reads this)", enabled = !busy,
            shape = fieldShape, colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )

        // Enabled toggle — off removes the model from the routing candidate pool entirely.
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Enabled",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Off → never used for routing (excluded from the candidate pool)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = form.enabled,
                    // Turning a model off also clears its router flag — a disabled model can't route
                    // (the backend skips a disabled router), so it must not stay flagged as one.
                    onCheckedChange = { form.enabled = it; if (!it) form.router = false },
                    enabled = !busy,
                )
            }
        }

        // Router toggle — styled as a tonal row within the card.
        // Title uses titleSmall + SemiBold (same weight as every section header — not an outlier).
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Use as router",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "This model decides which model runs each delegated task",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = form.router,
                    onCheckedChange = { form.router = it },
                    // A disabled model can't be the router (see the Enabled toggle above).
                    enabled = !busy && form.protocol == "anthropic" && form.enabled,
                )
            }
        }

        // Warn when setting a second router — only one model routes at a time.
        // Show who the current router is, so the user knows saving will replace it.
        if (form.router && currentRouter != null && currentRouter != form.name) {
            Text(
                "$currentRouter is currently the router — saving will replace it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        Button(
            onClick = onSubmit,
            enabled = !busy && form.valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Saving…" else if (form.editing) "Save changes" else "Add a model") }
    }
}

// ── Protocol selector ──────────────────────────────────────────────────────

private val PROTOCOLS = listOf("anthropic" to "Anthropic", "openai" to "OpenAI")

@Composable
private fun ProtocolSelector(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val interactionSources = remember { List(PROTOCOLS.size) { MutableInteractionSource() } }
    ButtonGroup(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        PROTOCOLS.forEachIndexed { i, (value, label) ->
            ToggleButton(
                checked = selected == value,
                onCheckedChange = { checked -> if (checked) onSelect(value) },
                enabled = enabled,
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    PROTOCOLS.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                interactionSource = interactionSources[i],
                modifier = Modifier.weight(1f).animateWidth(interactionSources[i]),
            ) {
                Text(label)
            }
        }
    }
}

// ── Claude Code official (native) models — collapsible per-family override section ──

@Composable
private fun NativeModelsSection() {
    val container = appContainer()
    val vm: NativeModelsViewModel = viewModel(
        factory = viewModelFactory { initializer { NativeModelsViewModel(container.providersRepo) } },
    )
    val ui by vm.uiState.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<NativeFamily?>(null) }

    SectionCard(
        title = "Claude models",
        trailing = {
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
        },
    ) {
        // Single Column child so a collapsed AnimatedVisibility doesn't double the card's gap.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Errors render regardless of expand state — a save/reset failure while collapsed
            // must not be silent.
            val err = ui.error
            if (err != null) {
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        // Spinner only on the FIRST load (empty list); a post-edit refresh updates
                        // the cards in place instead of flashing the whole list to a spinner.
                        ui.loading && ui.families.isEmpty() -> LoadingIndicator()
                        ui.families.isEmpty() -> Text(
                            "No native Claude models discovered.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> ui.families.forEach { fam ->
                            key(fam.family) {
                                NativeFamilyCard(fam, ui.busy, onEdit = { editing = fam })
                            }
                        }
                    }
                }
            }
        }
    }

    val target = editing
    if (target != null) {
        NativeOverrideDialog(
            family = target,
            busy = ui.busy,
            error = ui.error,
            onDismiss = { editing = null },
            onSave = { req -> vm.save(target.family, req) { e -> if (e == null) editing = null } },
            onReset = { vm.reset(target.family) { e -> if (e == null) editing = null } },
        )
    }
}

@Composable
private fun NativeFamilyCard(fam: NativeFamily, busy: Boolean, onEdit: () -> Unit) {
    // Same visual language as the sub-agent ProviderCard: blue tonal container, circular avatar,
    // titleLarge label, tinted metric rows. Native models are never the router, so always blue.
    val container = AccentBlueContainer
    val onContainer = OnAccentBlueContainer
    val muted = onContainer.copy(alpha = 0.75f)
    val capColor = AccentBlue
    val prioColor = onContainer
    val costColor = onContainer.copy(alpha = 0.85f)
    val track = onContainer.copy(alpha = 0.22f)

    Surface(
        color = container,
        shape = MaterialTheme.shapes.large,
        // Disabled families are dimmed — excluded from routing until re-enabled.
        modifier = Modifier.fillMaxWidth().alpha(if (fam.enabled) 1f else DISABLED_CARD_ALPHA),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(AccentBlue),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        fam.label.firstOrNull()?.uppercase() ?: "?",
                        color = OnAccentBlue,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            fam.label,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = onContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Yield to the Customized badge if the label is ever long (matches ProviderCard).
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (fam.customized) {
                            Spacer(Modifier.width(8.dp))
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp)).background(AccentBlue).padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Star, contentDescription = null, tint = OnAccentBlue, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Customized",
                                    color = OnAccentBlue,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                    if (fam.models.isNotEmpty()) {
                        Text(
                            fam.models.joinToString(", ") { it.id },
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (fam.editable) {
                    IconButton(onClick = onEdit, enabled = !busy, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Edit ${fam.label}", tint = onContainer)
                    }
                }
            }

            val desc = fam.description
            if (desc.isNotBlank()) {
                Surface(
                    color = onContainer.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyLarge,
                        color = onContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            MetricRow("Capability", fam.capability, capColor, track, muted, showLabel = true)
            MetricRow("Priority", fam.priority, prioColor, track, muted, showLabel = true)
            MetricRow("Cost", fam.cost, costColor, track, muted, showLabel = true)
        }
    }
}

@Composable
private fun NativeOverrideDialog(
    family: NativeFamily,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (NativeOverrideReq) -> Unit,
    onReset: () -> Unit,
) {
    var capability by remember(family.family) { mutableFloatStateOf(family.capability) }
    var priority by remember(family.family) { mutableFloatStateOf(family.priority) }
    var cost by remember(family.family) { mutableFloatStateOf(family.cost) }
    var description by remember(family.family) { mutableStateOf(family.description) }
    var enabled by remember(family.family) { mutableStateOf(family.enabled) }

    AlertDialog(
        // Don't let a tap-outside dismiss mid-request (would lose the in-flight save/reset feedback).
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("${family.label} routing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                FloatSliderField(label = "Capability", value = { capability }, onValueChange = { capability = it })
                FloatSliderField(
                    label = "Scheduling priority (higher = preferred)",
                    value = { priority },
                    onValueChange = { priority = it },
                )
                FloatSliderField(label = "Relative cost (0 = cheapest)", value = { cost }, onValueChange = { cost = it })
                AppTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = "Good at (the router reads this)",
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Enabled", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy)
                }
                if (family.customized) {
                    IconButton(onClick = onReset, enabled = !busy) {
                        Icon(
                            Icons.Rounded.RestartAlt,
                            contentDescription = "Reset to default",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    onSave(
                        NativeOverrideReq(
                            capability = capability,
                            priority = priority,
                            cost = cost,
                            description = description,
                            enabled = enabled,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
