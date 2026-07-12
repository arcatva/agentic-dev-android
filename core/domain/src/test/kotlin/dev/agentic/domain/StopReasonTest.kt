package dev.agentic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopReasonTest {

    // ── structured errorKind drives the label (no text guessing) ────────────────

    @Test fun usage_limit_kind() =
        assertEquals("⚠ Usage limit reached", stopReason("usage_limit", "anything", "failed"))

    // A transient rate-limit is distinct from the user's quota — different label, even though its
    // error text contains "limit".
    @Test fun rate_limited_kind() =
        assertEquals(
            "⏳ Rate limited — try again",
            stopReason("rate_limited", "Server is temporarily limiting requests (not your usage limit)", "failed"),
        )

    @Test fun claude_error_kind() =
        assertEquals("⚠ Claude error", stopReason("claude_error", "boom", "failed"))

    // A Claude-API 401 (host's OAuth creds expired) gets its own banner pointing at the host, distinct
    // from a generic claude_error — Resume alone can't fix it until the host re-authenticates.
    @Test fun auth_error_kind() =
        assertEquals(
            "🔑 Server's Claude login expired — log in on the host, then Resume",
            stopReason("auth_error", "API Error: 401 authentication_error", "running"),
        )

    @Test fun wall_timeout_kind() =
        assertEquals("⏱ Time cap reached", stopReason("wall_timeout", "turn watchdog: wall time ...", "failed"))

    @Test fun idle_timeout_kind() =
        assertEquals("⏱ Idle timeout", stopReason("idle_timeout", "turn watchdog: idle ...", "failed"))

    @Test fun crashed_kind() =
        assertEquals("Interrupted — crashed or killed", stopReason("crashed", "turn ended without completing", "failed"))

    @Test fun interrupted_kind() =
        assertEquals("Interrupted — server restart", stopReason("interrupted", "interrupted by server restart", "failed"))

    // A wall-time kill must NEVER be labeled a usage limit, even though its error text mentions a cap.
    @Test fun wall_timeout_is_not_a_usage_limit() {
        val label = stopReason("wall_timeout", "turn watchdog: wall time 7206s exceeded the 7200s cap", "failed")
        assertEquals("⏱ Time cap reached", label)
    }

    // ── null/unknown errorKind → legacy regex fallback on the error text ─────────

    @Test fun null_kind_with_limit_text_falls_back_to_usage_limit() =
        assertEquals("⚠ Usage limit reached", stopReason(null, "You've hit your session limit", "failed"))

    @Test fun null_kind_with_resets_text_falls_back_to_usage_limit() =
        assertEquals("⚠ Usage limit reached", stopReason(null, "resets at 3:30pm", "failed"))

    @Test fun null_kind_with_plain_error_is_interrupted() =
        assertEquals("Interrupted (failed)", stopReason(null, "some crash", "failed"))

    @Test fun null_kind_killed_no_error() =
        assertEquals("Interrupted (killed)", stopReason(null, null, "killed"))

    // Forward-compat: a kind this app version doesn't recognize falls back gracefully.
    @Test fun unknown_future_kind_falls_back() =
        assertEquals("Interrupted (failed)", stopReason("some_future_kind", "no keywords here", "failed"))

    // ── recoverAction: every terminal-not-done session offers a recover action ───
    // (regardless of error kind — the kind only sets the title; recovery is RESUME if there's a
    // claude session to continue, else RETRY to re-run the prompt fresh.)

    @Test fun recover_failed_with_session_resumes() =
        assertEquals(RecoverAction.RESUME, recoverAction("failed", "claude-1"))

    @Test fun recover_failed_without_session_retries() =
        assertEquals(RecoverAction.RETRY, recoverAction("failed", null))

    @Test fun recover_killed_with_session_resumes() =
        assertEquals(RecoverAction.RESUME, recoverAction("killed", "claude-1"))

    @Test fun recover_killed_without_session_retries() =
        assertEquals(RecoverAction.RETRY, recoverAction("killed", null))

    @Test fun recover_done_is_none() =
        assertEquals(RecoverAction.NONE, recoverAction("done", "claude-1"))

    // A "running" session reaches a recover button only when its last turn errored and it is now idle
    // (awaitingInput=true) — the engine clears the error on the next turn start. So it offers recovery:
    // RESUME if there's a claude session to continue (e.g. an idle "Not logged in" error), else RETRY.
    @Test fun recover_running_idle_error_with_session_resumes() =
        assertEquals(RecoverAction.RESUME, recoverAction("running", "claude-1"))

    @Test fun recover_running_idle_error_without_session_retries() =
        assertEquals(RecoverAction.RETRY, recoverAction("running", null))

    @Test fun recover_pending_is_none() =
        assertEquals(RecoverAction.NONE, recoverAction("pending", null))

    // ── isBenignCap: watchdog time/idle caps are not errors (render like a finished session) ─────

    @Test fun wall_timeout_is_a_benign_cap() = assertTrue(isBenignCap("wall_timeout"))
    @Test fun idle_timeout_is_a_benign_cap() = assertTrue(isBenignCap("idle_timeout"))
    @Test fun usage_limit_is_not_benign() = assertFalse(isBenignCap("usage_limit"))
    @Test fun rate_limited_is_not_benign() = assertFalse(isBenignCap("rate_limited"))
    @Test fun claude_error_is_not_benign() = assertFalse(isBenignCap("claude_error"))
    @Test fun auth_error_is_not_benign() = assertFalse(isBenignCap("auth_error"))
    @Test fun crashed_is_not_benign() = assertFalse(isBenignCap("crashed"))
    @Test fun interrupted_is_not_benign() = assertFalse(isBenignCap("interrupted"))
    @Test fun null_kind_is_not_benign() = assertFalse(isBenignCap(null))

    // ── hasError: surfaces idle-streaming errors (errorKind set while status is still "running") ──

    @Test fun running_idle_usage_limit_is_error() = assertTrue(hasError("running", "usage_limit"))
    @Test fun running_idle_rate_limited_is_error() = assertTrue(hasError("running", "rate_limited"))
    @Test fun running_idle_claude_error_is_error() = assertTrue(hasError("running", "claude_error"))
    @Test fun running_idle_auth_error_is_error() = assertTrue(hasError("running", "auth_error"))
    @Test fun failed_with_kind_is_error() = assertTrue(hasError("failed", "crashed"))
    @Test fun failed_null_kind_is_error() = assertTrue(hasError("failed", null))
    @Test fun running_wall_timeout_is_not_error() = assertFalse(hasError("running", "wall_timeout"))
    @Test fun failed_wall_timeout_is_not_error() = assertFalse(hasError("failed", "wall_timeout"))
    @Test fun running_clean_is_not_error() = assertFalse(hasError("running", null))
    @Test fun done_clean_is_not_error() = assertFalse(hasError("done", null))
    @Test fun killed_user_stop_is_not_error() = assertFalse(hasError("killed", null))

    // ── isStuckTerminal: a terminal session with NO claude session to resume cannot be continued the
    //    normal way (no canFollowUp, no live process). The watchdog now reaps a turn that stalled
    //    before Claude initialized as status="done" with no error and no claudeSessionId — leaving the
    //    detail screen with no Send button AND no error banner. Such a session must offer a Retry. ──

    @Test fun stuck_done_without_session() = assertTrue(isStuckTerminal("done", null))
    @Test fun stuck_killed_without_session() = assertTrue(isStuckTerminal("killed", null))
    @Test fun stuck_failed_without_session() = assertTrue(isStuckTerminal("failed", null))
    @Test fun not_stuck_done_with_session() = assertFalse(isStuckTerminal("done", "claude-1"))
    @Test fun not_stuck_running_is_not_terminal() = assertFalse(isStuckTerminal("running", null))
    @Test fun not_stuck_pending_is_not_terminal() = assertFalse(isStuckTerminal("pending", null))

    // ── isForkAwaitingFirstTurn: a fork (parentSessionId set) that hasn't run yet (terminal "done",
    //    no claudeSessionId) must be treated as continuable, NOT stuck — the user opens it and sends
    //    the first message. Distinguishes a fork-awaiting-input from a genuinely stalled terminal. ──

    @Test fun fork_done_no_session_with_parent_is_awaiting() =
        assertTrue(isForkAwaitingFirstTurn("done", null, "parent-1"))
    @Test fun fork_after_first_turn_has_session_not_awaiting() =
        assertFalse(isForkAwaitingFirstTurn("done", "claude-1", "parent-1"))
    @Test fun non_fork_stuck_terminal_is_not_awaiting() =
        assertFalse(isForkAwaitingFirstTurn("done", null, null))
    @Test fun fork_running_is_not_awaiting() =
        assertFalse(isForkAwaitingFirstTurn("running", null, "parent-1"))
}
