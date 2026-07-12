package dev.agentic.domain


/** User-picked file held in the VM until `submit()` appends `[attached: ...]`. Lifecycle: Pending → Uploading → Done(path) (success) / Failed(reason) (terminal, retryable). [id] = URI string for stable equals/hashCode. */
data class PendingAttachment(
    val id: String,
    val localUri: String,
    val displayName: String,
    val sizeBytes: Long,
    val state: UploadState,
) {
    companion object {
        fun of(uri: String, name: String, size: Long): PendingAttachment =
            PendingAttachment(uri.toString(), uri, name, size, UploadState.Pending)
    }
}

sealed interface UploadState {
    /** Just added — upload coroutine not yet started (transient; usually <1 frame). */
    data object Pending : UploadState

    /** Bytes POSTing to /api/sessions/{id}/upload. */
    data object Uploading : UploadState

    /** Server confirmed. [path] = relative path embedded in next prompt's `[attached: ...]` marker. [token]/[name] only for PRE-SESSION staging uploads (identify file to `stagedUploads`); null for in-session (writes straight into worktree). */
    data class Done(val path: String, val token: String? = null, val name: String? = null) : UploadState

    /** Upload failed; [reason] is user-facing. User can remove or retry. */
    data class Failed(val reason: String) : UploadState
}