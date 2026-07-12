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
    /** Transient snackbar error; null when no error. */
    val error: String? = null,
    /** Component ids currently mid-toggle (prevents double-tap). */
    val toggling: Set<String> = emptySet(),
    /** True while any mutating CRUD op (add/delete skill, install/uninstall plugin, add/delete MCP) is in flight.
     *  UI must disable Add/submit/delete-long-press while true. Serialized — one at a time. */
    val busy: Boolean = false,
    /** External skill store — null = not fetched yet. */
    val catalog: List<CatalogSkill>? = null,
    /** Per-source scan failures; rest of the store still renders. */
    val catalogErrors: List<String> = emptyList(),
    val catalogLoading: Boolean = false,
    /** Transport-level failure (whole catalog request failed). */
    val catalogError: String? = null,
    /** Configured store sources; null = not fetched yet. */
    val sources: List<String>? = null,
)

/**
 * ViewModel for the Global Settings screen. Fetches the full component list, exposes it
 * grouped by kind; toggles apply optimistically and revert on failure.
 */
class GlobalSettingsViewModel(
    private val api: AgenticApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GlobalSettingsUiState())
    val uiState: StateFlow<GlobalSettingsUiState> = _uiState.asStateFlow()

    /** Serializes toggle API calls so responses apply in order and can't clobber each other.
     *  Settings toggles are infrequent — strict serialization is simpler than optimistic concurrency. */
    private val toggleMutex = Mutex()

    init {
        load()
    }

    /** In-flight [load]; a second call while one is running is dropped (init + LaunchedEffect both call it). */
    private var loadJob: Job? = null

    /** Reload the component list. Full-screen loading only while we have nothing to show —
     *  a re-load (e.g. returning from the skill store) refreshes quietly behind existing content. */
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

    /** Flip [component]'s enabled state: optimistic update, call API, apply refreshed list or revert. */
    fun toggle(component: ComponentInfo) {
        // Don't interleave with a CRUD op: both paths replace [components] with a full refreshed
        // list from their response, so a stale late arrival could overwrite the newer mutation.
        // CRUD is also blocked while toggles are in flight (see acquireBusy).
        if (_uiState.value.busy) return

        val toggleKey = "${component.kind}:${component.id}"
        if (_uiState.value.toggling.contains(toggleKey)) return

        val newEnabled = !component.globalEnabled

        // Optimistic update (applied before the coroutine acquires the mutex).
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
            // Serialize: a second toggle waits for the first to finish before sending its own
            // request, so the full-list responses apply in call order.
            toggleMutex.withLock {
                try {
                    val refreshed = api.toggleGlobalComponent(component.kind, component.id, newEnabled)
                    _uiState.update { s ->
                        s.copy(toggling = s.toggling - toggleKey, components = refreshed)
                    }
                } catch (e: Exception) {
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

    /** Guard: returns true and sets busy=true+clears previous error if no op is in flight;
     *  false when already busy. Must be called on the main thread. */
    private fun acquireBusy(): Boolean {
        // Also refuse while any toggle is in flight — CRUD and toggle responses both carry a
        // full component list, and whichever lands second would clobber the first.
        if (_uiState.value.busy || _uiState.value.toggling.isNotEmpty()) return false
        _uiState.update { it.copy(busy = true, error = null) }
        return true
    }

    /** Load the external skill store. [force] bypasses the server-side cache; no-ops when loaded
     *  unless [force] (or a previous catalog error). Catalog + sources fetched concurrently. */
    fun loadCatalog(force: Boolean = false) {
        val s = _uiState.value
        if (s.catalogLoading) return
        if (!force && s.catalog != null && s.catalogError == null) return
        _uiState.update { it.copy(catalogLoading = true, catalogError = null) }
        viewModelScope.launch {
            try {
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

    /** Add a store source, then rescan the catalog. */
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

    /** Remove a store source, then rescan the catalog. */
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

    /** Install a skill from a GitHub source. [update] replaces an existing install of the same name. */
    fun installSkill(source: String, update: Boolean = false): Boolean {
        if (!acquireBusy()) return false
        viewModelScope.launch {
            try {
                val refreshed = api.installSkill(source, update)
                _uiState.update { it.copy(components = refreshed, busy = false, error = null) }
                // Re-pull the catalog so per-entry updateAvailable reflects the fresh install
                // (cheap server-cache hit; without it an Update button lingers post-update).
                try {
                    val resp = api.getSkillCatalog(refresh = false)
                    _uiState.update { it.copy(catalog = resp.skills, catalogErrors = resp.errors) }
                } catch (_: Exception) {
                    // Stale Update button until next manual refresh — not worth surfacing.
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

    /** Install a plugin globally (slow — CLI). No-ops while another op is in flight. */
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
     * Add an MCP server globally. Returns validation error (for inline display) or null when
     * validation passes and the API call is enqueued. API errors land in [uiState.error]; the
     * form stays open so the user can retry. No-ops while busy (returns an error string — must
     * not look like success or the caller's close-on-success flag would close on an unrelated op).
     */
    fun addMcpServer(draft: McpDraft): String? {
        val err = draft.validationError
        if (err != null) return err
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

    // Mirrors NewRequestViewModel.buildMcpServerDef — keep parse logic in one place (the VM, not UI).
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
