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
     */
    fun toggle(component: ComponentInfo) {
        val toggleKey = "${component.kind}:${component.id}"
        // Ignore if already toggling this component.
        if (_uiState.value.toggling.contains(toggleKey)) return

        val newEnabled = !component.globalEnabled

        // Optimistic update.
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

    /** Dismiss the current transient error (called after the snackbar is shown). */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
