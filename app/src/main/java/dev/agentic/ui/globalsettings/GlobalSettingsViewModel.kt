package dev.agentic.ui.globalsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.net.AgenticApi
import dev.agentic.data.net.ComponentInfo
import dev.agentic.data.net.McpServerDef
import dev.agentic.ui.newrequest.McpDraft
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

    /** Reload the component list from the server. */
    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
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
     * MCP components ([ComponentInfo.kind] == "mcp") are read-only at the global level —
     * the backend returns 400 for them — so this function is a no-op for them.
     *
     * Toggle calls are serialized via [toggleMutex] so that responses from concurrent taps
     * are applied in order and cannot replace the full component list with a stale snapshot.
     */
    fun toggle(component: ComponentInfo) {
        // MCP global toggle is not supported by the backend; the switch is read-only.
        if (component.kind == "mcp") return

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
        if (_uiState.value.busy) return false
        _uiState.update { it.copy(busy = true, error = null) }
        return true
    }

    /** Add a skill globally. Calls the API and replaces the component list from the response.
     *  No-ops silently while another op is busy (the UI must also disable the submit button). */
    fun addSkill(name: String, description: String) {
        if (!acquireBusy()) return
        viewModelScope.launch {
            try {
                val refreshed = api.addSkill(name, description)
                _uiState.update { it.copy(components = refreshed, busy = false, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, error = "Failed to add skill: ${e.message}") }
            }
        }
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
    fun installPlugin(id: String) {
        if (!acquireBusy()) return
        viewModelScope.launch {
            try {
                val refreshed = api.installPlugin(id)
                _uiState.update { it.copy(components = refreshed, busy = false, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, error = "Failed to install plugin: ${e.message}") }
            }
        }
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
        if (!acquireBusy()) return null
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
