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
        // App is always dark; force dark system-bar styling (light icons against dark surfaces).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        AppLog.i("Activity", "MainActivity created (data=${intent?.data})")
        setContent { AppTheme { AppNav() } }
    }

    /** Re-entry on deep-link tap (singleTop): publish intent before super so AppNav's listener can consume it. */
    override fun onNewIntent(intent: Intent) {
        AppLog.d("Activity", "onNewIntent (data=${intent.data})")
        // setIntent BEFORE super: AppNav's listener consumes the sticky intent (setIntent(Intent())) so a later NavController reset can't re-handle the stale URI.
        setIntent(intent)
        super.onNewIntent(intent)
    }
}
