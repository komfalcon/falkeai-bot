package tech.aurikrex.falkeai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object OfflineSpeechBridge {
    suspend fun captureSinglePhrase(context: Context): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext null
        withTimeoutOrNull(9_000L) {
            suspendCancellableCoroutine { cont ->
                val sr = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
                fun finishWith(result: String?) {
                    runCatching { sr.destroy() }
                    if (cont.isActive) cont.resume(result)
                }
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(v: Float) = Unit
                    override fun onBufferReceived(b: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(t: Int, p: Bundle?) = Unit
                    override fun onPartialResults(p: Bundle?) = Unit
                    override fun onError(code: Int) = finishWith(null)
                    override fun onResults(b: Bundle?) {
                        val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        finishWith(list?.firstOrNull()?.trim())
                    }
                })
                val cfg = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                runCatching { sr.startListening(cfg) }.onFailure { finishWith(null) }
                cont.invokeOnCancellation { runCatching { sr.cancel(); sr.destroy() } }
            }
        }
    }
}
