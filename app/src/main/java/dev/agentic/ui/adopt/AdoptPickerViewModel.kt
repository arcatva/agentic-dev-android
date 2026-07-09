package dev.agentic.ui.adopt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.log.AppLog
import dev.agentic.data.net.Adoptable
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.userMessage
import dev.agentic.data.repo.SessionsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the adopt picker: a list of Claude Code sessions on disk that the user can import
 * into this server as native sessions.
 *
 * - [loading] is true while the initial GET /api/adoptable is in flight; the picker shows a
 *   spinner.
 * - [items] is sorted newest-first by [Adoptable.mtimeMs] so freshly-finished CLI sessions are at
 *   the top of the list.
 * - [error] is a user-facing message ("Couldn't load Claude Code sessions…") for transient fetch
 *   failures; non-null and the picker shows a retry banner over an empty body.
 * - [adoptingCsid] is the [Adoptable.sessionId] currently being POSTed (drives the row-level
 *   spinner). null = idle.
 * - [adoptedId] is the new server session id returned by a successful adopt; the picker pops the
 *   picker + navigates to this id (consumed by the screen, which calls [acknowledgeAdopt]).
 */
data class AdoptPickerUiState(
    val loading: Boolean = true,
    val items: List<Adoptable> = emptyList(),
    val error: String? = null,
    val adoptingCsid: String? = null,
    val adoptedId: String? = null,
)

/**
 * ViewModel for the adopt picker. Stateless aside from [uiState] / one-shot flags.
 *
 * Lifecycle:
 * - [load] is fire-once (called from `LaunchedEffect(Unit)` in the picker screen, so a
 *   re-composition never double-fires the GET).
 * - [adopt] POSTs and, on success, writes the new id to [AdoptPickerUiState.adoptedId] for the
 *   screen to navigate to. The screen calls [acknowledgeAdopt] once the nav has happened so the
 *   picker isn't re-triggered on recomposition.
 *
 * `viewModelScope` ties both operations to the composable lifecycle: navigating away cancels an
 * in-flight load or adopt, matches the per-screen-scoped VM convention used by the rest of the app.
 */
class AdoptPickerViewModel(
    private val sessionsRepo: SessionsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdoptPickerUiState())
    val uiState: StateFlow<AdoptPickerUiState> = _uiState.asStateFlow()

    /**
     * Fetch the adoptable candidates from the server (GET /api/adoptable). A failed fetch leaves
     * [AdoptPickerUiState.items] empty and surfaces a [AdoptPickerUiState.error] banner; the picker
     * shows a retry button.
     *
     * Idempotent enough to be called again from a "Retry" tap — it overwrites previous items/error
     * and flips `loading` back on for one tick so the spinner reappears.
     */
    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            when (val o = sessionsRepo.adoptable()) {
                is Outcome.Success -> {
                    val sorted = o.value.sortedByDescending { it.mtimeMs }
                    AppLog.d("VM", "adoptPicker loaded ${sorted.size} candidates")
                    _uiState.update { it.copy(loading = false, items = sorted, error = null) }
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "adoptPicker load failed: ${o.error}")
                    _uiState.update {
                        it.copy(loading = false, items = emptyList(), error = "Couldn't load Claude Code sessions. Tap to retry.")
                    }
                }
            }
        }
    }

    /**
     * POST /api/sessions/adopt for the given candidate. Marked `resumable == false` adoptions are
     * still allowed — they just become read-only server sessions; the screen reflects that with a
     * "read-only" hint underneath the row instead of disabling the tap.
     *
     * On success the new server session id flows into [AdoptPickerUiState.adoptedId] so the
     * picker can navigate. `loading` stays off (the row spinner is [adoptingCsid] instead, so the
     * user can scroll / tap another row while the POST is in flight).
     */
    fun adopt(candidate: Adoptable) {
        val csid = candidate.sessionId
        if (csid.isBlank() || _uiState.value.adoptingCsid != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(adoptingCsid = csid, error = null) }
            when (val o = sessionsRepo.adoptSession(csid, candidate.cwd)) {
                is Outcome.Success -> {
                    AppLog.d("VM", "adoptPicker: adopted csid=$csid -> ${o.value}")
                    _uiState.update { it.copy(adoptingCsid = null, adoptedId = o.value) }
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "adoptPicker: adopt failed: ${o.error}")
                    _uiState.update {
                        it.copy(
                            adoptingCsid = null,
                            error = "Couldn't adopt session: ${o.error.userMessage()}",
                        )
                    }
                }
            }
        }
    }

    /** Clear [AdoptPickerUiState.adoptedId] after the screen has navigated to it. Keeps the picker
     *  from re-firing its LaunchedEffect on the next recomposition. */
    fun acknowledgeAdopt() {
        _uiState.update { it.copy(adoptedId = null) }
    }

    /** True iff the given candidate's JSONL ended without a terminal status — those rows become
     *  read-only server sessions when adopted, so the screen renders a small hint and the tap is
     *  still allowed (the server happily imports them, just without live-resume). */
    fun canResume(candidate: Adoptable): Boolean = candidate.resumable
}
