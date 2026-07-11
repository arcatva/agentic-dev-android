package dev.agentic.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.log.AppLog
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.userMessage
import dev.agentic.data.net.PatchSessionReq
import dev.agentic.data.net.Session
import dev.agentic.data.repo.SessionsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionSettingsUiState(
    val session: Session? = null,
    val pendingModel: String? = null,
    val pendingEffort: String? = null,
    val pendingMode: String? = null,
    val pendingPermissionMode: String? = null,
    /** null = session not loaded yet; PATCH omits the field (never flip an unseen value). */
    val pendingAutoResume: Boolean? = null,
    val saving: Boolean = false,
    val error: String? = null,
    /** Set while the initial session fetch is in flight. */
    val loading: Boolean = true,
    /** A hand-off (detach) request is in flight. */
    val detaching: Boolean = false,
    /** `claude --resume …` command from the last successful hand-off, shown for copy. */
    val resumeCmd: String? = null,
)

class SessionSettingsViewModel(
    private val sessionsRepo: SessionsRepository,
    private val sessionId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionSettingsUiState())
    val uiState: StateFlow<SessionSettingsUiState> = _uiState.asStateFlow()

    init {
        // Best-effort: warm the session-start catalog so the Model slider has real options if this is the first consumer.
        viewModelScope.launch {
            try {
                sessionsRepo.sessionStartModelCatalog()
            } catch (e: Exception) {
                AppLog.d("VM", "session settings model catalog load failed: ${e.message}")
            }
        }
        viewModelScope.launch {
            when (val r = sessionsRepo.get(sessionId)) {
                is Outcome.Success -> {
                    val s = r.value
                    AppLog.d("VM", "session loaded id=$sessionId")
                    _uiState.update {
                        it.copy(
                            session = s,
                            // Pending values default to the session's values; user can override.
                            pendingModel = s.model,
                            pendingEffort = s.effort,
                            pendingMode = s.mode,
                            pendingPermissionMode = s.permissionMode,
                            pendingAutoResume = s.autoResume,
                            loading = false,
                        )
                    }
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "session load failed id=$sessionId err=${r.error}")
                    _uiState.update { it.copy(loading = false, error = r.error.toString()) }
                }
            }
        }
    }

    fun setPendingModel(m: String?) { _uiState.update { it.copy(pendingModel = m) } }
    fun setPendingEffort(e: String?) { _uiState.update { it.copy(pendingEffort = e) } }
    fun setPendingMode(m: String?) { _uiState.update { it.copy(pendingMode = m) } }
    fun setPendingPermissionMode(p: String?) { _uiState.update { it.copy(pendingPermissionMode = p) } }
    fun setPendingAutoResume(v: Boolean) { _uiState.update { it.copy(pendingAutoResume = v) } }

    /** PATCH /api/sessions/:id with the pending values. */
    fun saveToSession() {
        val s = _uiState.value
        if (s.saving) return
        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val req = PatchSessionReq(
                model = s.pendingModel,
                effort = s.pendingEffort,
                mode = s.pendingMode,
                permissionMode = s.pendingPermissionMode,
                autoResume = s.pendingAutoResume,
            )
            when (val r = sessionsRepo.patchSession(sessionId, req)) {
                is Outcome.Success -> {
                    AppLog.d("VM", "session saved id=$sessionId")
                    _uiState.update {
                        it.copy(saving = false, session = r.value, error = null)
                    }
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "session save failed id=$sessionId err=${r.error}")
                    _uiState.update {
                        it.copy(saving = false, error = r.error.toString())
                    }
                }
            }
        }
    }

    /** Hand this session off to a terminal `claude --resume` (POST /api/sessions/:id/detach). Server hard-stops the live process (single-writer). */
    fun detach() {
        if (_uiState.value.detaching) return
        _uiState.update { it.copy(detaching = true, error = null) }
        viewModelScope.launch {
            when (val r = sessionsRepo.detach(sessionId)) {
                is Outcome.Success -> {
                    AppLog.d("VM", "session detached id=$sessionId")
                    _uiState.update {
                        it.copy(
                            detaching = false,
                            resumeCmd = r.value.resumeCmd,
                            session = it.session?.copy(detached = true),
                        )
                    }
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "session detach failed id=$sessionId err=${r.error}")
                    _uiState.update { it.copy(detaching = false, error = r.error.userMessage()) }
                }
            }
        }
    }
}