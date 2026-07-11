package dev.agentic.data.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumableDownloaderTest {

    private val payload = "0123456789".toByteArray()
    private val etag = "\"tag-1\""

    private fun tempDest(): File = File.createTempFile("dl-test-", ".part").apply { deleteOnExit() }

    private fun okHeaders(len: Int = payload.size) = headersOf(
        HttpHeaders.ETag to listOf(etag),
        HttpHeaders.AcceptRanges to listOf("bytes"),
        HttpHeaders.ContentLength to listOf(len.toString()),
    )

    // A "mid-stream cut" is simulated by declaring the full Content-Length but delivering only a
    // prefix before EOF — exactly what a dropped connection looks like to the reader. (Ktor's
    // ByteChannel.close(cause) would DROP buffered unread bytes, so it can't model a partial body.)

    @Test
    fun `downloads whole file in one pass`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { req ->
            requests.add(req)
            respond(payload, HttpStatusCode.OK, okHeaders())
        })
        val dest = tempDest()
        var last: Pair<Long, Long?>? = null
        ResumableDownloader(client).download("http://x/f", dest) { r, t -> last = r to t }
        assertEquals(1, requests.size)
        assertNull(requests[0].headers[HttpHeaders.Range])
        assertTrue(payload.contentEquals(dest.readBytes()))
        assertEquals(10L to 10L, last)
    }

    @Test
    fun `resumes with range and if-range after a mid-stream cut`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { req ->
            requests.add(req)
            when (requests.size) {
                1 -> respond(payload.copyOfRange(0, 5), HttpStatusCode.OK, okHeaders(len = 10))
                else -> respond(
                    payload.copyOfRange(5, 10),
                    HttpStatusCode.PartialContent,
                    headersOf(
                        HttpHeaders.ETag to listOf(etag),
                        HttpHeaders.AcceptRanges to listOf("bytes"),
                        HttpHeaders.ContentRange to listOf("bytes 5-9/10"),
                        HttpHeaders.ContentLength to listOf("5"),
                    ),
                )
            }
        })
        val dest = tempDest()
        ResumableDownloader(client, sleep = {}).download("http://x/f", dest)
        assertEquals(2, requests.size)
        assertEquals("bytes=5-", requests[1].headers[HttpHeaders.Range])
        assertEquals(etag, requests[1].headers[HttpHeaders.IfRange])
        assertTrue(payload.contentEquals(dest.readBytes()))
    }

    @Test
    fun `restarts cleanly when the server answers a resume with 200`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { req ->
            requests.add(req)
            when (requests.size) {
                1 -> respond(payload.copyOfRange(0, 5), HttpStatusCode.OK, okHeaders(len = 10))
                else -> respond(payload, HttpStatusCode.OK, okHeaders())
            }
        })
        val dest = tempDest()
        ResumableDownloader(client, sleep = {}).download("http://x/f", dest)
        assertEquals(2, requests.size)
        // The second (full) response must REPLACE the partial half, not append to it.
        assertTrue(payload.contentEquals(dest.readBytes()))
    }

    @Test
    fun `retries a 5xx even with the production expectSuccess client config`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        // Mirror production: expectSuccess=true would throw ResponseException (an
        // IllegalStateException) BEFORE the downloader's status check unless the downloader
        // opts the request out — this test locks that opt-out in.
        val client = HttpClient(MockEngine { req ->
            requests.add(req)
            when (requests.size) {
                1 -> respond("boom", HttpStatusCode.BadGateway)
                else -> respond(payload, HttpStatusCode.OK, okHeaders())
            }
        }) { expectSuccess = true }
        val dest = tempDest()
        ResumableDownloader(client, sleep = {}).download("http://x/f", dest)
        assertEquals(2, requests.size)
        assertTrue(payload.contentEquals(dest.readBytes()))
    }

    @Test
    fun `fails fast on a client error without retrying`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { req ->
            requests.add(req)
            respond("nope", HttpStatusCode.NotFound)
        })
        val dest = tempDest()
        val e = runCatching { ResumableDownloader(client, sleep = {}).download("http://x/f", dest) }
        assertTrue(e.isFailure)
        assertEquals(1, requests.size)
    }

    @Test
    fun `gives up after consecutive failures without progress`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val slept = mutableListOf<Long>()
        val client = HttpClient(MockEngine { req ->
            requests.add(req)
            respond(ByteArray(0), HttpStatusCode.OK, okHeaders(len = 10))
        })
        val dest = tempDest()
        val e = runCatching {
            ResumableDownloader(client, maxRetries = 2, sleep = { slept.add(it) })
                .download("http://x/f", dest)
        }
        assertTrue(e.isFailure)
        assertEquals(3, requests.size) // first try + 2 retries
        assertEquals(2, slept.size)
    }

    @Test
    fun `progress resets the retry budget`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { req ->
            requests.add(req)
            when (requests.size) {
                1 -> respond(payload.copyOfRange(0, 4), HttpStatusCode.OK, okHeaders(len = 10))
                2 -> respond(
                    payload.copyOfRange(4, 7),
                    HttpStatusCode.PartialContent,
                    headersOf(
                        HttpHeaders.ETag to listOf(etag),
                        HttpHeaders.AcceptRanges to listOf("bytes"),
                        HttpHeaders.ContentRange to listOf("bytes 4-9/10"),
                    ),
                )
                else -> respond(
                    payload.copyOfRange(7, 10),
                    HttpStatusCode.PartialContent,
                    headersOf(
                        HttpHeaders.ETag to listOf(etag),
                        HttpHeaders.AcceptRanges to listOf("bytes"),
                        HttpHeaders.ContentRange to listOf("bytes 7-9/10"),
                        HttpHeaders.ContentLength to listOf("3"),
                    ),
                )
            }
        })
        val dest = tempDest()
        // maxRetries=1, but every attempt advances → the budget keeps resetting and all 3 attempts run.
        ResumableDownloader(client, maxRetries = 1, sleep = {}).download("http://x/f", dest)
        assertEquals(3, requests.size)
        assertEquals("bytes=4-", requests[1].headers[HttpHeaders.Range])
        assertEquals("bytes=7-", requests[2].headers[HttpHeaders.Range])
        assertTrue(payload.contentEquals(dest.readBytes()))
    }

    @Test
    fun `content-range helpers parse totals and starts`() {
        assertEquals(10L, contentRangeTotal("bytes 5-9/10"))
        assertEquals(10L, contentRangeTotal("bytes */10"))
        assertNull(contentRangeTotal("bytes 5-9/*"))
        assertNull(contentRangeTotal("garbage"))
        assertNull(contentRangeTotal(null))
        assertEquals(5L, contentRangeStart("bytes 5-9/10"))
        assertNull(contentRangeStart("bytes */10"))
        assertNull(contentRangeStart(null))
    }
}
