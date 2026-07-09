package dev.agentic.domain

/** Legacy heuristic: the backend used to send only a free-text error, and the client guessed "usage
 *  limit" by matching this against it. Kept ONLY as the fallback for null/unknown errorKind (old
 *  sessions, an older backend, or a future kind this app version doesn't know). */
private val LEGACY_LIMIT_RE = Regex("limit|resets", RegexOption.IGNORE_CASE)

/** Human-facing banner title for why a terminal (failed/killed) session stopped.
 *
 * Driven by the backend's structured [errorKind] so the client no longer guesses from error text.
 * Falls back to the legacy regex on [error] when [errorKind] is null or unrecognized, which keeps
 * old sessions and version-skewed backends working. [status] is the session status ("failed"/"killed")
 * used only by the fallback. The full [error] text is still shown separately below this title. */
fun stopReason(errorKind: String?, error: String?, status: String): String = when (errorKind) {
    // The server's Claude credentials (OAuth token on the host) expired/were rejected with a 401.
    // Resume/Retry alone won't fix it — the host must log in again first — so the title points there.
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

/** What recover action a terminal session offers in the error banner. Every error kind is treated the
 *  same — the [errorKind] only drives the banner *title* ([stopReason]); recovery depends solely on
 *  whether there's a claude conversation to continue. */
enum class RecoverAction { RESUME, RETRY, NONE }

/** Recovery for an errored session that is no longer making progress:
 *  - RESUME: continue the prior turn via Claude's --resume (a [claudeSessionId] exists).
 *  - RETRY:  no claude session to resume (the turn died before Claude initialized) — re-run the prompt.
 *  - NONE:   pending, or a cleanly-finished (done) turn — nothing to recover.
 *
 *  "running" is included: a turn that ends with an error leaves the session "running" + idle
 *  (awaitingInput=true) with the error set, and the engine CLEARS the error when the next turn starts — so
 *  a running session only ever reaches a recover button (via hasError()) when its last turn errored and it
 *  is idle-and-resumable, never mid-generation. Without this, an idle-streaming error (e.g. "Not logged
 *  in") showed the error banner but NO Resume/Retry button.
 *
 *  A user-initiated Stop ends as "killed" and is still recoverable here (it shows the banner), but its
 *  list-row status dot stays muted — a deliberate Stop is not an error, so only genuine errors
 *  (status "failed") paint the red warning dot. */
fun recoverAction(status: String, claudeSessionId: String?): RecoverAction = when {
    status != "failed" && status != "killed" && status != "running" -> RecoverAction.NONE
    claudeSessionId != null -> RecoverAction.RESUME
    else -> RecoverAction.RETRY
}

/** A watchdog "cap" — the server reaped a turn for running past its wall-clock or idle limit. These
 *  are NOT failures: the turn simply ran long / stalled and stopped, and the session continues by just
 *  typing the next prompt (it resumes). The UI therefore renders a capped session like a normal
 *  finished session — no red error dot, no error banner — unlike a genuine error (usage limit, rate
 *  limit, claude error, crash), which keeps the red warning + recover banner. */
fun isBenignCap(errorKind: String?): Boolean = errorKind == "wall_timeout" || errorKind == "idle_timeout"

/** Should the UI surface an ERROR for this session — a red status dot in the list and the error
 *  banner in the detail view?
 *
 *  Streaming subtlety: a turn that ends with a usage/rate/claude error does NOT make the session
 *  terminal. The persistent claude process stays alive, so the backend leaves the session idle
 *  (status="running", awaitingInput=true) with [errorKind] set — it only becomes "failed" if the
 *  process itself dies. So an error must be surfaced from [errorKind], not just from a terminal
 *  [status]; otherwise an idle-but-errored streaming session looks like a normal idle one and the
 *  error (e.g. a hit usage limit) is invisible.
 *
 *  Excludes benign watchdog caps ([isBenignCap]); a user Stop ("killed", errorKind=null) is not an
 *  error either. */
fun hasError(status: String, errorKind: String?): Boolean = when {
    isBenignCap(errorKind) -> false
    errorKind != null -> true          // a classified error — terminal OR idle-streaming
    status == "failed" -> true         // terminal failure with no/legacy errorKind
    else -> false
}

/** A terminal session that cannot be continued the normal way: it has no [claudeSessionId] to
 *  `--resume`, and (being terminal) no live process to type into. The watchdog now reaps a turn that
 *  stalled before Claude initialized as status="done" with no error and no claudeSessionId — which left
 *  the detail screen with neither a Send button (no canFollowUp) nor an error banner (no error). Such a
 *  session is "stuck"; the only recovery is to re-run the original prompt fresh (Retry). A terminal
 *  session WITH a claudeSessionId is resumable via the input bar (canFollowUp), so it is never stuck. */
fun isStuckTerminal(status: String, claudeSessionId: String?): Boolean =
    status in TERMINAL && claudeSessionId == null

/** A forked session waiting for its FIRST turn: it has a [parentSessionId] but no [claudeSessionId]
 *  yet. `fork_session` creates the fork idle (terminal "done") so the user can open it and send the
 *  first message — but with no claudeSessionId, the generic [isStuckTerminal] / canFollowUp checks
 *  (which require a claudeSessionId to `--resume`) would misclassify it as un-continuable: the input
 *  bar is hidden and a bogus "This turn stopped… Retry" card shows. It IS continuable — the server
 *  accepts the first follow-up (status "done" is idle, not busy) and seeds it with the source
 *  session's transcript. Used to keep such a fork follow-up-able (and thus NOT stuck). */
fun isForkAwaitingFirstTurn(status: String, claudeSessionId: String?, parentSessionId: String?): Boolean =
    status in TERMINAL && claudeSessionId == null && parentSessionId != null
