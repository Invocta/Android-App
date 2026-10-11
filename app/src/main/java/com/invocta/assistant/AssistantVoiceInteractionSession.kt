package com.invocta.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.invocta.assistant.ui.AssistantOverlayActivity

/**
 * Voice interaction session that handles the actual assistant interaction
 */
class AssistantVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        // Launch the overlay assistant activity when the assistant is invoked
        val intent = Intent(context, AssistantOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_VOICE_LAUNCH, true)
        }
        context.startActivity(intent)

        // Hide this session's UI immediately since we're using our own Activity
        hide()
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        // Handle assist-related data if needed
    }

    companion object {
        const val EXTRA_VOICE_LAUNCH = "extra_voice_launch"
    }
}
