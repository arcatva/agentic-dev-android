package dev.agentic.data.repo

import dev.agentic.data.log.AppLog
import dev.agentic.data.net.AgenticApi
import dev.agentic.data.net.CommitFile
import dev.agentic.data.net.FileDiff
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.RepoCommits
import dev.agentic.data.net.runCatchingOutcome

/**
 * Single access point for file operations: upload, download bytes, commit graph, discard.
 * - [upload]/[commits]/[commitFiles] return [Outcome] so callers can react.
 * - [fileBytes] lets exceptions propagate — caller (VM) owns progress + error UX.
 * - [discard] is best-effort fire-and-forget.
 */
class FilesRepository(private val api: AgenticApi) {

    suspend fun upload(id: String, bytes: ByteArray, name: String): Outcome<String> =
        runCatchingOutcome { api.uploadFile(id, bytes, name) }.also {
            when (it) {
                is Outcome.Success -> AppLog.d("File", "upload ok: $name (${bytes.size}B)")
                is Outcome.Failure -> AppLog.w("File", "upload failed: $name: ${it.error}")
            }
        }

    /** Throws on error — caller handles (snackbar). */
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

    /** Stream with automatic mid-transfer resume (HTTP Range). Throws on final failure. */
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

    suspend fun commits(id: String): Outcome<List<RepoCommits>> =
        runCatchingOutcome { api.commits(id) }.also {
            when (it) {
                is Outcome.Success -> AppLog.d("File", "commits ok")
                is Outcome.Failure -> AppLog.w("File", "commits failed: ${it.error}")
            }
        }

    suspend fun commitFiles(id: String, repo: String, sha: String): Outcome<List<CommitFile>> =
        runCatchingOutcome { api.commitFiles(id, repo, sha) }.also {
            when (it) {
                is Outcome.Success -> AppLog.d("File", "commitFiles ok: $repo@${sha.take(7)}")
                is Outcome.Failure -> AppLog.w("File", "commitFiles failed: $repo@${sha.take(7)}: ${it.error}")
            }
        }

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

    /** Best-effort; ignores errors. */
    suspend fun discard(id: String) {
        runCatchingOutcome { api.discard(id) }.also {
            when (it) {
                is Outcome.Success -> AppLog.d("File", "discard ok")
                is Outcome.Failure -> AppLog.w("File", "discard failed: ${it.error}")
            }
        }
    }
}
