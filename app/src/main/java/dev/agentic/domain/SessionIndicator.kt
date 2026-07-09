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

/** Discord-style: a session is unread when its server-side [Session.unreadEventId] is strictly
 *  greater than the server-side [Session.ackedEventId]. Both fields come from the same server row
 *  — there is no client-side read-state persistence, no timestamp comparison, no fallback, no
 *  clock-skew. The client POSTs an ack to `PUT /api/sessions/:id/ack { eventId }` which updates
 *  `ackedEventId` on the server; the next poll tick picks up the updated value. */
fun isSessionUnread(session: Session): Boolean {
    val unread = session.unreadEventId > session.ackedEventId
    if (!unread && session.unreadEventId > 0) {
        AppLog.v("Read", "unreadCheck id=${session.id.take(8)} unreadEventId=${session.unreadEventId} acked=${session.ackedEventId}")
    }
    return unread
}
