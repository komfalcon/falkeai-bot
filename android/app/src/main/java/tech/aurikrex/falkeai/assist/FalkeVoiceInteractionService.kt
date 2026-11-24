package tech.aurikrex.falkeai.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Registers FalkeAI as a first-class Android Default Digital Assistant (Settings -> Default Apps ->
 * Digital Assistant App -> FalkeAI), replacing Google Assistant / Gemini on Home / Power long-press!
 */
class FalkeVoiceInteractionService : VoiceInteractionService()

class FalkeVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return FalkeVoiceInteractionSession(this)
    }
}

class FalkeRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}

class FalkeVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, FalkeAssistActivity::class.java).apply {
            putExtra(FalkeAssistActivity.EXTRA_AUTO_LISTEN, true)
            putExtra(FalkeAssistActivity.EXTRA_CAPTURE_SCREEN, true)
            putExtra(
                FalkeAssistActivity.EXTRA_INTERNAL_TOKEN,
                tech.aurikrex.falkeai.FalkeApplication.instance.internalLaunchToken
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { context.startActivity(intent) }
        finish()
    }
}
