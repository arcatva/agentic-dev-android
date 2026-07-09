package dev.agentic

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.agentic.data.log.AppLog
import kotlinx.coroutines.launch

/**
 * Receives FCM push messages from the agentic-dev backend.
 *
 * Expected message data payload:
 *   type      = "session_finish"
 *   sessionId = "<uuid>"
 *   status    = "done" | "failed"
 *   cost      = "$0.42"   (present when status == "done")
 *   error     = "…"       (present when status == "failed")
 *
 * Tapping the notification deep-links into the session detail via the
 * agentic://session/<id> intent URI handled by MainActivity.
 */
class AgenticMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "agentic_sessions"
        private var notifId = 0
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    /** Called when FCM assigns a new registration token (first run or token rotation). */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        AppLog.i("FCM", "onNewToken")
        val container = (applicationContext as AgenticApp).container
        container.appScope.launch { runCatching { container.authRepo.registerFcm(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        AppLog.d("FCM", "message: type=${data["type"]} session=${data["sessionId"]} status=${data["status"]}")
        if (data["type"] != "session_finish") return
        val sessionId = data["sessionId"] ?: return
        val status = data["status"] ?: "done"

        val title = buildString {
            append(sessionId.take(8))   // first 8 chars of the UUID as short label
            append(": ")
            append(status)
        }
        val body = when (status) {
            "done" -> data["cost"]?.let { "Cost: $it" } ?: "Completed"
            else   -> data["error"] ?: "Session $status"
        }

        // Deep-link intent — handled by MainActivity via the agentic://session/<id> scheme.
        val deepLink = Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("agentic://session/$sessionId"),
            applicationContext,
            MainActivity::class.java,
        ).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            applicationContext, sessionId.hashCode(), deepLink,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync) // replace with app icon resource later
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(applicationContext).notify(++notifId, notif)
        }
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Session updates", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Notified when an agentic-dev session finishes"
                }
            )
        }
    }
}
