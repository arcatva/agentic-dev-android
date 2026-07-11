package dev.agentic.data.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Thrown for non-2xx download responses; [code] drives retry classification (only 5xx retries). */
class DownloadHttpException(val code: Int) : Exception("HTTP $code")

/** Parses the total from `Content-Range: bytes 5-9/10` → 10 (null when the total is `*`/absent). */
internal fun contentRangeTotal(header: String?): Long? {
    val h = header ?: return null
    if (!h.startsWith("bytes ")) return null
    return h.substringAfterLast('/', "").toLongOrNull()
}

/** Parses the start offset from `Content-Range: bytes 5-9/10` → 5 (null when the start is `*`). */
internal fun contentRangeStart(header: String?): Long? {
    val h = header ?: return null
    if (!h.startsWith("bytes ")) return null
    return h.removePrefix("bytes ").substringBefore('-').trim().toLongOrNull()
}

/**
 * Streams a GET response into a file, transparently RESUMING over transient mid-stream failures
 * (flaky Wi-Fi, backend deploy restarts) with `Range` + `If-Range` instead of failing the whole
 * transfer or restarting from byte 0.
 *
 * Behavior contract (mirrored by the server's `/file` route):
 * - A fresh 200 captures the ETag + Accept-Ranges; a mid-stream failure retries from the current
 *   offset with `Range: bytes=<received>-` and `If-Range: <etag>`.
 * - A 206 continuation must start exactly at the requested offset (anything else is a server bug
 *   and aborts rather than corrupt the file). A 200 answer to a resume means "file changed or
 *   ranges unsupported" — the partial temp is discarded and the transfer restarts cleanly.
 * - Retry budget: [maxRetries] CONSECUTIVE zero-progress failures give up; any attempt that
 *   advances the byte count resets the budget, so a long flaky window keeps inching forward while
 *   a hard-down server still fails promptly. Only transport errors and 5xx retry — 4xx fail fast.
 */
class ResumableDownloader(
    private val client: HttpClient,
    private val maxRetries: Int = 4,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val backoffMs: (failureIndex: Int) -> Long = { 500L * (1L shl it.coerceAtMost(3)) },
) {
    suspend fun download(
        url: String,
        dest: File,
        configure: HttpRequestBuilder.() -> Unit = {},
        onProgress: ((received: Long, total: Long?) -> Unit)? = null,
    ) {
        var received = 0L
        var total: Long? = null
        var etag: String? = null
        var resumable = false
        var failures = 0
        while (true) {
            val before = received
            try {
                client.prepareGet(url) {
                    configure()
                    // The production client sets expectSuccess=true, whose validator would throw
                    // ResponseException (an IllegalStateException, NOT an IOException) before this
                    // downloader ever saw the status — killing the 5xx-retry classification below.
                    // Opt this request out; WE own status handling here.
                    expectSuccess = false
                    if (received > 0 && resumable && etag != null) {
                        header(HttpHeaders.Range, "bytes=$received-")
                        header(HttpHeaders.IfRange, etag!!)
                    }
                }.execute { resp ->
                    if (!resp.status.isSuccess()) throw DownloadHttpException(resp.status.value)
                    if (resp.status == HttpStatusCode.PartialContent) {
                        val range = resp.headers[HttpHeaders.ContentRange]
                        val start = contentRangeStart(range)
                        check(start == received) { "server resumed at $start, expected $received" }
                        contentRangeTotal(range)?.let { total = it }
                    } else {
                        // Fresh (or restarted) full body: drop any partial half and re-capture the
                        // resume credentials — the file may have changed since the last attempt.
                        received = 0
                        etag = resp.headers[HttpHeaders.ETag]
                        resumable = resp.headers[HttpHeaders.AcceptRanges]?.contains("bytes") == true
                        total = resp.contentLength()
                    }
                    // RandomAccessFile pinned to the known-good offset — NOT append mode. A failed
                    // attempt can leave a short-written tail past `received` (e.g. ENOSPC mid-write);
                    // append would resume AFTER that garbage. setLength(received) truncates any such
                    // tail so on-disk length always equals the resume offset.
                    java.io.RandomAccessFile(dest, "rw").use { raf ->
                        raf.setLength(received)
                        raf.seek(received)
                        val ch = resp.bodyAsChannel()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ch.readAvailable(buf, 0, buf.size)
                            if (n == -1) break
                            if (n > 0) {
                                raf.write(buf, 0, n)
                                received += n
                                onProgress?.invoke(received, total)
                            }
                        }
                    }
                }
                val expected = total
                if (expected != null && received < expected) {
                    // Clean EOF but short body (e.g. server killed mid-flush) — treat as transient.
                    throw IOException("body ended early at $received/$expected")
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only ZERO-progress failures consume a strike; a progress-making failure resets
                // the budget and does not count itself (Codex review: reset-then-increment made a
                // progressing cut eat one strike, giving up an attempt early on flaky links).
                if (received > before) failures = 0 else failures++
                val transient = e is IOException || e is HttpRequestTimeoutException ||
                    (e is DownloadHttpException && e.code >= 500)
                if (!transient || failures > maxRetries) throw e
                sleep(backoffMs((failures - 1).coerceAtLeast(0)))
            }
        }
    }
}
