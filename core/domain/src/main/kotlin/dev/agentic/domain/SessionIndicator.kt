package dev.agentic.domain


fun indicatorStatus(session: SessionSnapshot): String = when {
    session.workflowRunning -> "running"
    hasError(session.status, session.errorKind) -> "failed"
    isBenignCap(session.errorKind) -> "done"
    else -> session.status
}

fun indicatorAwaitingInput(session: SessionSnapshot): Boolean? =
    if (session.workflowRunning) false else session.awaitingInput

/** Unread iff [SessionSnapshot.unreadEventId] > [SessionSnapshot.ackedEventId] (server-side cursor; no clock/fallback; ack via `PUT /api/sessions/:id/ack`). */
fun isSessionUnread(session: SessionSnapshot): Boolean {
    return session.unreadEventId > session.ackedEventId
}
