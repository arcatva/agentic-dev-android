package dev.agentic

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.agentic.data.log.AppLog
import dev.agentic.ui.AppTheme
import dev.agentic.ui.nav.AppNav

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so force dark system-bar styling — light icons against our dark
        // surfaces. Matches the old MainActivity's enableEdgeToEdge call exactly.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        AppLog.i("Activity", "MainActivity created (data=${intent?.data})")
        setContent { AppTheme { AppNav() } }
    }

    /**
     * Called when the activity is already running (android:launchMode="singleTop") and a new
     * notification tap arrives — e.g. a deep-link intent for agentic://session/<id>.
     *
     * Navigation-Compose picks up deep links from the activity's intent automatically.
     * Updating via setIntent() makes the new intent visible to the NavController when it
     * re-reads intent on recomposition.
     */
    override fun onNewIntent(intent: Intent) {
        AppLog.d("Activity", "onNewIntent (data=${intent.data})")
        // Publish the intent BEFORE super dispatches to the OnNewIntentListeners: AppNav's warm
        // deep-link listener handles agentic://session links itself and then CONSUMES the sticky
        // intent (setIntent(Intent())) so a later NavController graph reset can't re-handle the
        // stale uri. With the old order (super first, setIntent after) that consume was silently
        // overwritten right here.
        setIntent(intent)
        super.onNewIntent(intent)
    }
}
