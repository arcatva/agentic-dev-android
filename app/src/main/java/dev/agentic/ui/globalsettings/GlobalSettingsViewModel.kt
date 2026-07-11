package dev.agentic.ui.globalsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.net.AgenticApi
import dev.agentic.data.net.CatalogSkill
import dev.agentic.data.net.ComponentInfo
import dev.agentic.data.net.McpServerDef
import dev.agentic.ui.newrequest.McpDraft
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GlobalSettingsUiState(
    val loading: Boolean = true,
    val components: List<ComponentInfo> = emptyList(),
    /** Transient error message shown in a snackbar; null when no error. */
    val error: String? = null,
    /** Set of component ids currently mid-toggle (prevents double-tap). */
    val toggling: Set<String> = emptySet(),
    /**
     * True while ANY mutating CRUD op (add/delete skill, install/uninstall plugin, add/delete MCP)
     * is in flight. The UI must disable Add buttons, submit buttons, and delete long-press while
     * this is true. Replaces the old per-feature pluginBusy — a single flag is simpler and correct
     * because these ops are serialized one-at-a-time (parallel CRUD on the same list is unsafe).
     */
    val busy: Boolean = false,
    // ── External skill store (GET /api/skills/catalog + /api/skills/sources) ──
    /** null = not fetched yet; loaded lazily when the Install mode is first shown. */
    val catalog: List<CatalogSkill>? = null,
    /** Per-source scan failures — the rest of the store still renders. */
    val catalogErrors: List<String> = emptyList(),
    val catalogLoading: Boolean = false,
    /** Transport-level failure (the whole catalog request failed). */
    val catalogError: String? = null,
    /** Configured store sources; null = not fetched yet. */
    val sources: List<String>? = null,
)

/**
 * ViewModel for the Global Settings screen.
 *
 * Fetches the full component list on creation and exposes it grouped by kind (skills, plugins, mcp).
 * A [toggle] call optimistically updates the switch, sends the request, applies the returned
 * refreshed list on success, or reverts and surfaces a transient [error] on failure.
 */
class GlobalSettingsViewModel(
    private val api: AgenticApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GlobalSettingsUiState())
    val uiState: StateFlow<GlobalSettingsUiState> = _uiState.asStateFlow()

    /**
     * Serializes toggle API calls so responses are applied in order and cannot
     * clobber each other. Because a settings screen toggles infrequently, strict
     * serialization is simpler and safer than optimistic concurrent requests.
     */
    private val toggleMutex = Mutex()

    init {
        load()
    }

    /** In-flight [load] job — a second call while one is running is dropped (on first entry
     *  the VM init and the screen's LaunchedEffect both call load()). */
    private var loadJob: Job? = null

    /** Reload the component list from the server. The full-screen loading flag is only raised
     *  while we have nothing to show — a re-load (e.g. returning from the skill store, which
     *  runs its own ViewModel) refreshes quietly behind the existing content. */
    fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = it.components.isEmpty(), error = null) }
            try {
                val list = api.getGlobalSettings()
                _uiState.update { it.copy(loading = false, components = list) }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = "Failed to load settings: ${e.message}") }
            }
        }
    }

    /**
     * Flip [component]'s enabled state: optimistically update, call the API, apply the refreshed
     * list, or revert + surface error on failure.
     *
     * Supports all three kinds — the backend toggles skills/plugins via settings.local.json
     * and MCP servers by parking their definition in .claude.json (mcpServersDisabled).
     *
     * Toggle calls are serialized via [toggleMutex] so that responses from concurrent taps
     * are applied in order and cannot replace the full component list with a stale snapshot.
     */
    fun toggle(component: ComponentInfo) {
        // Don't interleave with a CRUD op (add/delete): both paths replace [components] with a
        // full refreshed list from their response, so a stale late arrival could overwrite the
        // newer mutation. CRUD is blocked while toggles are in flight too (see acquireBusy).
        if (_uiState.value.busy) return

        val toggleKey = "${component.kind}:${component.id}"
        // Ignore if already toggling this component.
        if (_uiState.value.toggling.contains(toggleKey)) return

        val newEnabled = !component.globalEnabled

        // Optimistic update (applied immediately, before the coroutine acquires the mutex).
        _uiState.update { s ->
            s.copy(
                toggling = s.toggling + toggleKey,
                components = s.components.map {
                    if (it.kind == component.kind && it.id == component.id)
                        it.copy(globalEnabled = newEnabled)
                    else it
                },
            )
        }

        viewModelScope.launch {
            // Serialize API calls: a second toggle waits for the first to complete before
            // sending its own request, so the full-list response from each call is applied
            // in the order the calls were made.
            toggleMutex.withLock {
                try {
                    val refreshed = api.toggleGlobalComponent(component.kind, component.id, newEnabled)
                    _uiState.update { s ->
                        s.copy(toggling = s.toggling - toggleKey, components = refreshed)
                    }
                } catch (e: Exception) {
                    // Revert the optimistic change and show a transient error.
                    _uiState.update { s ->
                        s.copy(
                            toggling = s.toggling - toggleKey,
                            components = s.components.map {
                                if (it.kind == component.kind && it.id == component.id)
                                    it.copy(globalEnabled = component.globalEnabled)
                                else it
                            },
                            error = "Failed to toggle ${component.name}: ${e.message}",
                        )
                    }
                }
            }
        }
    }

    /** Dismiss the current transient error (called after the snackbar is shown). */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ── CRUD actions ─────────────────────────────────────────────────────────────

    /**
     * Guard: return true and set busy=true+clear previous error if no op is in flight.
     * Return false (caller must not proceed) when already busy.
     * Must be called on the main thread (StateFlow.update is main-thread safe).
     */
    private fun acquireBusy(): Boolean {
        // Also refuse while any toggle is in flight — a CRUD response and a toggle response
        // both carry a full component list, and whichever lands second would clobber the first.
        if (_uiState.value.busy || _uiState.value.toggling.isNotEmpty()) return false
        _uiState.update { it.copy(busy = true, error = null) }
        return true
    }

    /** Load the external skill store — catalog (aggregated across sources) + the source list.
     *  Without [force], no-ops when already loaded; [force] bypasses the server-side cache too. */
    fun loadCatalog(force: Boolean = false) {
        val s = _uiState.value
        if (s.catalogLoading) return
        if (!force && s.catalog != null && s.catalogError == null) return
        _uiState.update { it.copy(catalogLoading = true, catalogError = null) }
        viewModelScope.launch {
            try {
                // Independent requests — fetch concurrently.
                val (resp, sources) = coroutineScope {
                    val respDeferred = async { api.getSkillCatalog(refresh = force) }
                    val sourcesDeferred = async { api.getSkillSources() }
                    respDeferred.await() to sourcesDeferred.await()
                }
                _uiState.update {
                    it.copy(
                        catalog = resp.skills,
                        catalogErrors = resp.errors,
                        sources = sources,
                        catalogLoading = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(catalogLoading = false, catalogError = "Couldn't load the store: ${e.message}") }
            }
        }
    }

    /** Add a store source, then rescan the catalog so its skills appear immediately. */
    fun addSource(source: String) {
        if (!acquireBusy()) return
        viewModelScope.launch {
            try {
                val sources = api.addSkillSource(source)
                _uiState.update { it.copy(sources = sources, busy = false, error = null) }
                loadCatalog(force = true)
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, error = "Failed to add source: ${e.message}") }
            }
        }
    }

    /** Remove a store source, then rescan the catalog so its skills disappear. */
    fun removeSource(source: String) {
        if (!acquireBusy()) return
        viewModelScope.launch {
            try {
                val sources = api.deleteSkillSource(source)
                _uiState.update { it.copy(sources = sources, busy = false, error = null) }
                loadCatalog(force = true)
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, error = "Failed to remove source: ${e.message}") }
            }
        }
    }

    /** Install a skill from a GitHub source (catalog entry or user-supplied URL/ref).
     *  [update] replaces an existing install of the same name. */
    fun installSkill(source: String, update: Boolean = false): Boolean {
        if (!acquireBusy()) return false
        viewModelScope.launch {
            try {
                val refreshed = api.installSkill(source, update)
                _uiState.update { it.copy(components = refreshed, busy = false, error = null) }
                // Re-pull the catalog so per-entry updateAvailable reflects the fresh install
                // (server cache hit — the annotation is computed per request, so this is cheap;
                // without it an Update button would linger after a successful update).
                try {
                    val resp = api.getSkillCatalog(refresh = false)
                    _uiState.update { it.copy(catalog = resp.skills, catalogErrors = resp.errors) }
                } catch (_: Exception) {
                    // Stale Update button until the next manual refresh — not worth surfacing.
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, error = "Failed to install skill: ${e.message}") }
            }
        }
        return true
    }

    /** Delete a skill by name globally. No-ops while busy. */
    fun deleteSkill(name: String) {
        if (!acquireBusy()) return
        viewModelScope.launch {
            try {
                val refreshed = api.deleteSkill(name)
                _uiState.update { it.copy(components = refreshed, busy = false, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, error = "Failed to delete skill: ${e.message}") }
            }
        }
    }

    /** Install a plugin globally (slow — CLI). Sets busy while in flight.
     *  No-ops while another op is already in flight. */
    fun installPlugin(id: String): Boolean {
        if (!acquireBusy()) return false
        viewModelScope.launch {
            try {
                val refreshed = api.installPlugin(id)
                _uiState.update { it.copy(components = refreshed, busy = false, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, error = "Failed to install plugin: ${e.message}") }
            }
        }
        return true
    }

    /** Uninstall a plugin globally (slow). Sets busy while in flight. */
    fun uninstallPlugin(id: String) {
        if (!acquireBusy()) return
        viewModelScope.launch {
            try {
                val refreshed = api.uninstallPlugin(id)
                _uiState.update { it.copy(busy = false, components = refreshed, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, error = "Failed to uninstall plugin: ${e.message}") }
            }
        }
    }

    /**
     * Add an MCP server globally.
     *
     * Returns a validation error string synchronously (for the form to show inline), or null when
     * validation passes (the async API call has been enqueued). API errors land in [uiState.error]
     * (shown via snackbar) — this keeps the form open so the user can retry after fixing the input.
     * No-ops while busy (returns null — the UI is expected to disable the button anyway).
     */
    fun addMcpServer(draft: McpDraft): String? {
        val err = draft.validationError
        if (err != null) return err
        // Busy-refusal must NOT look like success (null) - the caller arms its
        // close-on-success flag on null and would later close on an unrelated op.
        if (!acquireBusy()) return "Another operation is in progress - try again"
        val def = buildMcpServerDef(draft)
        viewModelScope.launch {
            try {
                val refreshed = api.addMcpServer(def)
                _uiState.update { it.copy(components = refreshed, busy = false, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, error = "Failed to add MCP server: ${e.message}") }
            }
        }
        return null
    }

    /** Delete an MCP server globally by name. No-ops while busy. */
    fun deleteMcpServer(name: String) {
        if (!acquireBusy()) return
        viewModelScope.launch {
            try {
                val refreshed = api.deleteMcpServer(name)
                _uiState.update { it.copy(components = refreshed, busy = false, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, error = "Failed to delete MCP server: ${e.message}") }
            }
        }
    }

    // Mirrors NewRequestViewModel.buildMcpServerDef so we don't duplicate the parse logic in the UI layer.
    private fun buildMcpServerDef(draft: McpDraft): McpServerDef {
        val envMap = draft.env.lines()
            .map { it.trim() }.filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
            .ifEmpty { null }
        val headersMap = draft.headers.lines()
            .map { it.trim() }.filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
            .ifEmpty { null }
        return if (draft.transport == "stdio") {
            McpServerDef(
                name = draft.name.trim(),
                command = draft.command.trim(),
                args = draft.args.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.ifEmpty { null },
                env = envMap,
            )
        } else {
            McpServerDef(
                name = draft.name.trim(),
                type = draft.httpType,
                url = draft.url.trim(),
                headers = headersMap,
            )
        }
    }
}
