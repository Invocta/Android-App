package com.invocta.assistant

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.invocta.assistant.ui.AssistantOverlayActivity

/**
 * Assistant Activity - Handles assistant launch shortcuts.
 * Redirects to the overlay assistant UI when triggered via voice command or long-press home.
 */
class AssistantActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Launch the overlay assistant activity with optimized flags
        val overlayIntent = Intent(this, AssistantOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION) // Skip transition animation from this activity
            // Pass along any extras from the original intent
            intent.extras?.let { putExtras(it) }
        }
        startActivity(overlayIntent)
        
        // Finish immediately without animation
        finishAffinity()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overridePendingTransition(0, 0)
        }
    }
}
