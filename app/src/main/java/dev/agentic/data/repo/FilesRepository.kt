package dev.agentic.data.repo

import dev.agentic.data.log.AppLog
import dev.agentic.data.net.AgenticApi
import dev.agentic.data.net.CommitFile
import dev.agentic.data.net.FileDiff
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.RepoCommits
import dev.agentic.data.net.runCatchingOutcome

/**
 * Single access point for file operations: upload, download bytes, commit graph, and discard.
 *
 * Each method delegates directly to [AgenticApi]; error handling is intentionally minimal:
 * - [upload], [commits], and [commitFiles] return [Outcome] so callers can react to failures.
 * - [fileBytes] lets exceptions propagate — the caller (ViewModel) owns progress + error UX.
 * - [discard] is best-effort fire-and-forget; no Outcome needed.
 */
class FilesRepository(private val api: AgenticApi) {

    /** Upload [bytes] as [name] for session [id]. Returns [Outcome.Success] with the server-side
     *  path on success, or [Outcome.Failure] on any error. */
    suspend fun upload(id: String, bytes: ByteArray, name: String): Outcome<String> =
        runCatchingOutcome { api.uploadFile(id, bytes, name) }.also {
            when (it) {
                is Outcome.Success -> AppLog.d("File", "upload ok: $name (${bytes.size}B)")
                is Outcome.Failure -> AppLog.w("File", "upload failed: $name: ${it.error}")
            }
        }

    /** Download file bytes for [path] within session [id], with optional [onProgress] callback.
     *  Throws on error — callers should handle exceptions (e.g. show a snackbar). */
    suspend fun fileBytes(
        id: String,
        path: String,
        onProgress: ((Float?) -> Unit)? = null,
    ): ByteArray {
        AppLog.d("File", "download: $path")
        return try {
            api.fileBytes(id, path, onProgress)
        } catch (e: Exception) {
            AppLog.w("File", "download $path: ${e.message}", e)
            throw e
        }
    }

    /** Stream the file at [path] into [dest] with automatic mid-transfer resume (HTTP Range).
     *  [onProgress] reports cumulative (bytesReceived, totalBytes-or-null). Throws on final failure —
     *  the caller owns progress + error UX. */
    suspend fun downloadTo(
        id: String,
        path: String,
        dest: java.io.File,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ) {
        AppLog.d("File", "download (stream): $path")
        try {
            api.downloadFileTo(id, path, dest, onProgress)
        } catch (e: Exception) {
            AppLog.w("File", "download (stream) $path: ${e.message}", e)
            throw e
        }
    }

    /** Returns the per-repo commit history for session [id], or [Outcome.Failure] on error. */
    suspend fun commits(id: String): Outcome<List<RepoCommits>> =
        runCatchingOutcome { api.commits(id) }.also {
            when (it) {
                is Outcome.Success -> AppLog.d("File", "commits ok")
                is Outcome.Failure -> AppLog.w("File", "commits failed: ${it.error}")
            }
        }

    /** Returns the changed files for [sha] in [repo] of session [id], or [Outcome.Failure] on error. */
    suspend fun commitFiles(id: String, repo: String, sha: String): Outcome<List<CommitFile>> =
        runCatchingOutcome { api.commitFiles(id, repo, sha) }.also {
            when (it) {
                is Outcome.Success -> AppLog.d("File", "commitFiles ok: $repo@${sha.take(7)}")
                is Outcome.Failure -> AppLog.w("File", "commitFiles failed: $repo@${sha.take(7)}: ${it.error}")
            }
        }

    /** Returns the line-level diff for one [path] in [sha] of [repo], or [Outcome.Failure] on error. */
    suspend fun commitDiff(id: String, repo: String, sha: String, path: String): Outcome<FileDiff> =
        runCatchingOutcome { api.commitDiff(id, repo, sha, path) }.also {
            when (it) {
                is Outcome.Success -> AppLog.d("File", "commitDiff ok: $repo@${sha.take(7)} $path")
                is Outcome.Failure -> AppLog.w("File", "commitDiff failed: $repo@${sha.take(7)} $path: ${it.error}")
            }
        }

    /** Restores session [id]'s working tree to the snapshot before [turnIndex] (code only). */
    suspend fun rewind(id: String, turnIndex: Int): Outcome<Unit> =
        runCatchingOutcome { api.rewind(id, turnIndex) }.also {
            when (it) {
                is Outcome.Success -> AppLog.d("File", "rewind ok: t$turnIndex")
                is Outcome.Failure -> AppLog.w("File", "rewind failed: t$turnIndex: ${it.error}")
            }
        }

    /** Discards pending changes for session [id]. Best-effort; ignores errors. */
    suspend fun discard(id: String) {
        runCatchingOutcome { api.discard(id) }.also {
            when (it) {
                is Outcome.Success -> AppLog.d("File", "discard ok")
                is Outcome.Failure -> AppLog.w("File", "discard failed: ${it.error}")
            }
        }
    }
}
