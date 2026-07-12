package dev.agentic.domain

// Legacy heuristic: backend used to send only free-text error — fallback for null/unknown errorKind (old sessions / version skew).
private val LEGACY_LIMIT_RE = Regex("limit|resets", RegexOption.IGNORE_CASE)

/** Banner title for a stopped session. Driven by [errorKind] (structured); falls back to legacy regex on [error] when [errorKind] null/unknown. [status] used only by fallback. */
fun stopReason(errorKind: String?, error: String?, status: String): String = when (errorKind) {
    // auth_error: host's OAuth token rejected (401); Resume/Retry alone won't fix — host must log in first.
    "auth_error" -> "🔑 Server's Claude login expired — log in on the host, then Resume"
    "usage_limit" -> "⚠ Usage limit reached"
    "rate_limited" -> "⏳ Rate limited — try again"
    "claude_error" -> "⚠ Claude error"
    "wall_timeout" -> "⏱ Time cap reached"
    "idle_timeout" -> "⏱ Idle timeout"
    "crashed" -> "Interrupted — crashed or killed"
    "interrupted" -> "Interrupted — server restart"
    else ->
        if (LEGACY_LIMIT_RE.containsMatchIn(error ?: "")) "⚠ Usage limit reached"
        else "Interrupted ($status)"
}

// RecoverAction: errorKind only drives *title* ([stopReason]); recovery depends solely on whether a claude conversation exists to continue.
enum class RecoverAction { RESUME, RETRY, NONE }

/** RESUME = `--resume` ([claudeSessionId] exists); RETRY = no claude session, re-run prompt; NONE = nothing to recover. "running" included because an errored-turn leaves session running+idle (awaitingInput=true) — without this, an idle-streaming error showed banner but NO Resume/Retry button. A user-Stop ("killed") stays recoverable here but its list-row dot stays muted (only genuine errors paint red). */
fun recoverAction(status: String, claudeSessionId: String?): RecoverAction = when {
    status != "failed" && status != "killed" && status != "running" -> RecoverAction.NONE
    claudeSessionId != null -> RecoverAction.RESUME
    else -> RecoverAction.RETRY
}

/** Watchdog "cap" (wall/idle timeout) — NOT a failure; turn just stopped, session resumes on next prompt. UI renders capped session like a finished one (no red/error banner). */
fun isBenignCap(errorKind: String?): Boolean = errorKind == "wall_timeout" || errorKind == "idle_timeout"

/** Show error (red dot + banner) iff there's a classified error. Streaming subtlety: a usage/rate/claude error leaves session idle (running+awaitingInput) — backend only sets "failed" if process dies — so we surface from [errorKind], not just [status], else an idle-but-errored session looks normal. Excludes [isBenignCap] and user-Stop (errorKind=null). */
fun hasError(status: String, errorKind: String?): Boolean = when {
    isBenignCap(errorKind) -> false
    errorKind != null -> true          // classified error — terminal OR idle-streaming
    status == "failed" -> true         // terminal failure with no/legacy errorKind
    else -> false
}

/** Terminal session with no [claudeSessionId] to `--resume` and no live process to type into — "stuck", only Retry recovers. (Watchdog reaps a stalled turn as "done" with no error/claudeSessionId — even less obvious.) Terminal + claudeSessionId → resumable via input bar (canFollowUp), never stuck. */
fun isStuckTerminal(status: String, claudeSessionId: String?): Boolean =
    status in TERMINAL && claudeSessionId == null

/** Forked session waiting for its FIRST turn ([parentSessionId] set, [claudeSessionId] null). `fork_session` creates it idle ("done") so the user opens it to send the first message — but with no claudeSessionId, generic stuck/canFollowUp misclassifies as un-continuable (input bar hidden, bogus Retry card). It IS continuable — server accepts first follow-up (idle, not busy). */
fun isForkAwaitingFirstTurn(status: String, claudeSessionId: String?, parentSessionId: String?): Boolean =
    status in TERMINAL && claudeSessionId == null && parentSessionId != null
