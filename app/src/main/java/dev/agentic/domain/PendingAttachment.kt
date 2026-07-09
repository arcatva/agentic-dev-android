package dev.agentic.domain

import android.net.Uri

/**
 * A file the user picked from the device but hasn't sent yet. Held in the VM's [SessionUiState]
 * until the next `submit()` composes the trailing `[attached: ...]` marker. Lifecycle:
 *
 *   Pending → Uploading → Done(path)    (success — the path is what gets put in the marker)
 *                     ↘  Failed(reason) (terminal — stays in the list so the user can retry or remove)
 *
 * `id` is the URI's string form so equals/hashCode are stable across recompositions and the list can
 * be diffed cheaply. `localUri` is kept separate so the upload coroutine can re-open the stream even
 * if the URI string changes (it won't, but defensive).
 */
data class PendingAttachment(
    val id: String,
    val localUri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val state: UploadState,
) {
    companion object {
        fun of(uri: Uri, name: String, size: Long): PendingAttachment =
            PendingAttachment(uri.toString(), uri, name, size, UploadState.Pending)
    }
}

sealed interface UploadState {
    /** Just added — upload coroutine hasn't started yet (transient; usually <1 frame). */
    data object Pending : UploadState

    /** Bytes are being POSTed to /api/sessions/{id}/upload. */
    data object Uploading : UploadState

    /** Server confirmed; [path] is the relative path (e.g. `uploads/foo.png`) embedded in the next
     *  prompt's `[attached: ...]` marker.
     *
     *  [token] and [name] are set only for PRE-SESSION (New-request) staging uploads: they identify
     *  the staged file so the create request's `stagedUploads` can tell the backend which files to
     *  adopt into the new session's uploads/ dir. They stay null for in-session uploads (the session
     *  upload endpoint writes straight into the worktree, so only [path] is needed there). */
    data class Done(val path: String, val token: String? = null, val name: String? = null) : UploadState

    /** Upload failed; [reason] is the user-facing message. User can remove or retry. */
    data class Failed(val reason: String) : UploadState
}