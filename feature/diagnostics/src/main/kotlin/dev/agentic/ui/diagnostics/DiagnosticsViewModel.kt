package dev.agentic.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.log.LogStore
import dev.agentic.data.log.AppLog
import dev.agentic.data.log.LogcatCollector
import dev.agentic.data.net.AgenticApi
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.Session
import dev.agentic.data.repo.SessionsRepository
import dev.agentic.domain.TERMINAL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Snapshot for [DiagnosticsScreen]. */
data class DiagUiState(
    val logText: String = "",
    val crashCount: Int = 0,
    val captureEnabled: Boolean = false,
    val loading: Boolean = true,
    /** Total bytes of the current log bundle (capture + rotations + crashes) for the picker header. */
    val bundleBytes: Long = 0,
    /** Sessions loaded when the attach-picker opens. */
    val sessions: List<Session> = emptyList(),
    /** Drives the session-picker ModalBottomSheet visibility. */
    val showSessionPicker: Boolean = false,
    /** True while fetching sessions for the picker. */
    val loadingSessions: Boolean = false,
    /** Non-null while a log-bundle upload is in flight — shows progress. */
    val attachingSessionId: String? = null,
)

/**
 * Prompt body for attach follow-up: `<message>` plus the trailing `[attached: <path>]` marker —
 * the SAME wire shape the session chat composes for its attachments (SessionViewModel's
 * composePromptWithMarker / the reducer's splitAttachments), so the transcript renders the bundle
 * as a regular attachment chip and the agent receives the path it can read.
 */
internal fun composeAttachPrompt(message: String, path: String): String {
    val marker = "[attached: $path]"
    val text = message.trim()
    return if (text.isEmpty()) "\n\n$marker" else "$text\n\n$marker"
}

/**
 * Backs the diagnostics/log screen. Reads the tail of the rolling capture, toggles background
 * capture, clears logs, builds the export zip the screen shares via FileProvider.
 *
 * Opening the screen marks existing crashes as "seen" so the next-launch crash prompt won't re-fire.
 *
 * All collector start/stop goes through [lifecycleMutex] and runs on [Dispatchers.IO]: keeps the
 * blocking start/stop off the main thread AND serializes them so a rapid capture toggle — or a toggle
 * racing a clear/export — can never run two lifecycle ops at once and momentarily open two writers
 * on the capture file. [Mutex] is fair; ops apply in the order requested.
 */
class DiagnosticsViewModel(
    private val store: LogStore,
    private val collector: LogcatCollector,
    private val api: AgenticApi,
    private val sessionsRepo: SessionsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagUiState())
    val state: StateFlow<DiagUiState> = _state.asStateFlow()

    private val lifecycleMutex = Mutex()

    /** One-shot Toast events for the screen to collect. */
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    /** One-shot "open this session" events, fired after successful attach+send so the user lands on
     *  the session and SEES the attachment message — the proof that the attach worked. */
    private val _openSession = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openSession: SharedFlow<String> = _openSession.asSharedFlow()

    init {
        // markCrashesSeen() lists crash files (disk IO), so off the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            AppLog.d("Diag", "markCrashesSeen")
            store.markCrashesSeen()
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val text = withContext(Dispatchers.IO) { store.tail() }
            val crashes = withContext(Dispatchers.IO) { store.crashReports().size }
            val bytes = withContext(Dispatchers.IO) {
                store.logsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
            AppLog.v("Diag", "tail read len=${text.length} crashes=$crashes bundleBytes=$bytes")
            _state.update {
                it.copy(
                    logText = text,
                    crashCount = crashes,
                    bundleBytes = bytes,
                    captureEnabled = store.captureEnabled,
                    loading = false,
                )
            }
        }
    }

    fun setCaptureEnabled(enabled: Boolean) {
        AppLog.d("Diag", "capture toggle enabled=$enabled")
        // One switch = "Verbose logging": flip verbose seams AND logcat→file capture together.
        dev.agentic.data.log.AppLog.verbose = enabled
        store.captureEnabled = enabled
        _state.update { it.copy(captureEnabled = enabled) }
        viewModelScope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                if (enabled) collector.start() else collector.stop()
            }
        }
    }

    fun clear() {
        AppLog.d("Diag", "log clear requested")
        viewModelScope.launch(Dispatchers.IO) {
            // Pause capture so the collector's open writer doesn't race the truncate (stale byte
            // counter → premature rotation), then resume. Serialized via the same lock.
            val wasOn = store.captureEnabled
            lifecycleMutex.withLock {
                if (wasOn) collector.stop()
                store.clear()
                if (wasOn) collector.start()
            }
            refresh()
        }
    }

    /** Builds the export zip off the main thread. Pauses the collector for the duration so zipping
     *  never races the live capture write. */
    suspend fun buildExport(): File = withContext(Dispatchers.IO) {
        AppLog.d("Diag", "export started")
        val wasOn = store.captureEnabled
        lifecycleMutex.withLock {
            if (wasOn) collector.stop()
            try {
                store.exportZip()
            } finally {
                if (wasOn) collector.start()
            }
        }
    }

    // ── Attach logs to a session ─────────────────────────────────────────────

    /** Open the session-picker sheet and load the session list. */
    fun showPicker() {
        _state.update { it.copy(showSessionPicker = true) }
        loadSessions()
    }

    /** Dismiss the session-picker sheet. */
    fun dismissPicker() {
        _state.update { it.copy(showSessionPicker = false) }
    }

    /** Fetch the session list from the backend for the picker. */
    private fun loadSessions() {
        viewModelScope.launch {
            _state.update { it.copy(loadingSessions = true) }
            try {
                val list = api.sessions()
                AppLog.d("Diag", "loadSessions OK count=${list.size}")
                _state.update { it.copy(sessions = list, loadingSessions = false) }
            } catch (e: Exception) {
                AppLog.w("Diag", "loadSessions FAILED: ${e.message}")
                _state.update { it.copy(loadingSessions = false) }
                _toast.tryEmit("Failed to load sessions: ${e.message}")
            }
        }
    }

    /**
     * Export the log bundle zip, upload to [sessionId], then SEND a follow-up carrying the
     * `[attached: <path>]` marker — bundle shows up in transcript as a normal attachment and
     * agent is told to read it. (Previously stopped after upload — zip landed in worktree's
     * uploads/ but session never saw it, which read as "attach did nothing".) Dismisses picker
     * first; posts Toast with result; on success, fires [openSession] so user lands on the
     * session and sees the message.
     */
    fun attachToSession(sessionId: String, message: String) {
        // Atomic claim (Codex P2): a double-tap — same row or two rows — before picker dismisses
        // must not start a second upload + a second agent turn. getAndUpdate CASes the claim in.
        val prev = _state.getAndUpdate {
            if (it.attachingSessionId == null) it.copy(showSessionPicker = false, attachingSessionId = sessionId)
            else it
        }
        if (prev.attachingSessionId != null) return
        viewModelScope.launch {
            var uploadedPath: String? = null
            try {
                val zip = buildExport()
                val bytes = withContext(Dispatchers.IO) { zip.readBytes() }
                AppLog.d("Diag", "attachToSession id=${sessionId.take(8)} bytes=${bytes.size}")
                val path = api.uploadFile(sessionId, bytes, zip.name)
                uploadedPath = path
                // Route through the repo, NOT api.followUp: the repo handles the classic backend's
                // stale-terminal race (polls until status leaves TERMINAL before reopening the stream),
                // so navigating into a just-resumed done session still streams the new turn.
                val r = sessionsRepo.followUp(sessionId, composeAttachPrompt(message, path), setTitle = false)
                if (r is Outcome.Failure) {
                    AppLog.w("Diag", "attachToSession send FAILED id=${sessionId.take(8)}: ${r.error}")
                    _toast.emit("Uploaded, but sending the message failed: ${r.error}")
                    return@launch
                }
                AppLog.d("Diag", "attachToSession id=${sessionId.take(8)} sent path=$path")
                // Codex P1: the repo's stale-terminal wait only runs when a transcript flow for this id
                // already exists (session opened before). When attaching to a never-opened done session,
                // /events can still report the OLD terminal status for a beat after the followUp POST —
                // navigating then makes SessionScreen load() skip opening a stream and user stares at the
                // old transcript. Wait until status leaves TERMINAL (bounded), THEN navigate. For
                // running sessions the first check breaks out.
                var tries = 0
                while (tries < 10) {
                    val s = runCatching { api.sessionEvents(sessionId, limit = 1) }.getOrNull()?.session
                    if (s == null || s.status !in TERMINAL) break
                    delay(400)
                    tries++
                }
                _toast.emit("Logs sent to the session")
                _openSession.tryEmit(sessionId)
            } catch (e: CancellationException) {
                throw e // don't swallow coroutine cancellation (screen closed mid-upload)
            } catch (e: Exception) {
                AppLog.w("Diag", "attachToSession FAILED (uploadedPath=$uploadedPath): ${e.message}")
                _toast.emit(
                    if (uploadedPath == null) "Upload failed: ${e.message}"
                    else "Uploaded, but sending the message failed: ${e.message}",
                )
            } finally {
                _state.update { it.copy(attachingSessionId = null) }
            }
        }
    }
}
