package dev.agentic.ui.globalsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.net.AgenticApi
import dev.agentic.data.net.ComponentInfo
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
}
