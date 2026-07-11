package dev.agentic.domain

import dev.agentic.data.log.AppLog
import dev.agentic.data.net.Session

fun indicatorStatus(session: Session): String = when {
    session.workflowRunning -> "running"
    hasError(session.status, session.errorKind) -> "failed"
    isBenignCap(session.errorKind) -> "done"
    else -> session.status
}

fun indicatorAwaitingInput(session: Session): Boolean? =
    if (session.workflowRunning) false else session.awaitingInput

/** Unread iff [Session.unreadEventId] > [Session.ackedEventId] (server-side cursor; no clock/fallback; ack via `PUT /api/sessions/:id/ack`). */
fun isSessionUnread(session: Session): Boolean {
    val unread = session.unreadEventId > session.ackedEventId
    if (!unread && session.unreadEventId > 0) {
        AppLog.v("Read", "unreadCheck id=${session.id.take(8)} unreadEventId=${session.unreadEventId} acked=${session.ackedEventId}")
    }
    return unread
}
