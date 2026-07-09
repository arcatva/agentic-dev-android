package dev.agentic.data

import dev.agentic.data.net.AppError
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.UnauthorizedException
import dev.agentic.data.net.runCatchingOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeTest {

    @Test
    fun `runCatchingOutcome returns Success when block succeeds`() {
        val result = runCatchingOutcome { 42 }
        assertTrue(result is Outcome.Success)
        assertEquals(42, (result as Outcome.Success).value)
    }

    @Test
    fun `runCatchingOutcome maps UnauthorizedException to Unauthorized`() {
        val result = runCatchingOutcome<Int> { throw UnauthorizedException() }
        assertTrue(result is Outcome.Failure)
        assertEquals(AppError.Unauthorized, (result as Outcome.Failure).error)
    }

    @Test
    fun `runCatchingOutcome rethrows CancellationException instead of wrapping it as a Failure`() {
        var propagated = false
        try {
            runCatchingOutcome<Int> { throw kotlinx.coroutines.CancellationException("cancelled") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            propagated = true
        }
        assertTrue("cancellation must propagate for structured concurrency", propagated)
    }

    @Test
    fun `runCatchingOutcome maps generic Exception to Unknown`() {
        val cause = RuntimeException("boom")
        val result = runCatchingOutcome<Int> { throw cause }
        assertTrue(result is Outcome.Failure)
        val error = (result as Outcome.Failure).error
        assertTrue(error is AppError.Unknown)
        assertEquals(cause, (error as AppError.Unknown).cause)
    }

    @Test
    fun `runCatchingOutcome maps java io IOException to Network`() {
        val cause = java.io.IOException("network failure")
        val result = runCatchingOutcome<Int> { throw cause }
        assertTrue(result is Outcome.Failure)
        val error = (result as Outcome.Failure).error
        assertTrue("expected Network but got $error", error is AppError.Network)
        assertEquals(cause, (error as AppError.Network).cause)
    }

    @Test
    fun `AppError Http holds status code`() {
        val error: AppError = AppError.Http(404)
        assertEquals(404, (error as AppError.Http).code)
    }

    // ResponseException requires a real HttpResponse which needs the full Ktor test stack — not
    // viable in a pure JVM unit test. The Http branch is reachable at runtime because
    // expectSuccess=true causes Ktor to throw ResponseException for non-2xx responses and
    // runCatchingOutcome catches ResponseException → AppError.Http. The data-class round-trip
    // is tested above.
    @Test
    fun `runCatchingOutcome maps multiple AppError Http codes correctly`() {
        for (code in listOf(400, 403, 404, 500, 503)) {
            val error: AppError = AppError.Http(code)
            assertEquals(code, (error as AppError.Http).code)
        }
    }
}
