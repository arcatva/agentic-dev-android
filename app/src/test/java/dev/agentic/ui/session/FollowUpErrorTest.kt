package dev.agentic.ui.session

import dev.agentic.data.net.AppError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [followUpErrorMessage] turns a failed follow-up [AppError] into honest user copy. A non-terminal
 * session now shows an enabled Send even when the server can't take the turn (e.g. an orphaned
 * "running" row): the server rejects that POST with EngineError::Busy -> HTTP 400. That must read as
 * "busy", not the misleading "check your connection" used for a real transport failure.
 */
class FollowUpErrorTest {

    @Test fun `http 400 reads as session busy`() =
        assertEquals(
            "This session is still busy — try again in a moment.",
            followUpErrorMessage(AppError.Http(400)),
        )

    @Test fun `other http codes report a server error`() =
        assertEquals(
            "Couldn't send — server error (HTTP 500).",
            followUpErrorMessage(AppError.Http(500)),
        )

    @Test fun `network failure asks to check the connection`() =
        assertEquals(
            "Couldn't send — check your connection and try again.",
            followUpErrorMessage(AppError.Network(java.io.IOException("x"))),
        )

    @Test fun `unauthorized asks to sign in`() =
        assertEquals(
            "Session expired — please sign in again.",
            followUpErrorMessage(AppError.Unauthorized),
        )

    @Test fun `unknown failure is a generic send error`() =
        assertEquals(
            "Couldn't send — something went wrong.",
            followUpErrorMessage(AppError.Unknown(RuntimeException("x"))),
        )
}
