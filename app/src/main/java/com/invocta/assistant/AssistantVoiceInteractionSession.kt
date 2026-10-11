package com.invocta.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.invocta.assistant.ui.AssistantOverlayActivity

/**
 * Voice interaction session that handles the actual assistant interaction
 */
class AssistantVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setUiEnabled(false)
        super.onPrepareShow(args, showFlags)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        val intent = Intent(context, AssistantOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_VOICE_LAUNCH, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startAssistantActivity(intent)
        } else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hide()
        }
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        // Handle assist-related data if needed
    }

    companion object {
        const val EXTRA_VOICE_LAUNCH = "extra_voice_launch"
    }
}
