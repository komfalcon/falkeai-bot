package tech.aurikrex.falkeai.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import tech.aurikrex.falkeai.data.CallNoteRequest
import tech.aurikrex.falkeai.data.DeviceContextPayload
import tech.aurikrex.falkeai.data.FalkeApiClient
import tech.aurikrex.falkeai.data.FalkeDatabase
import tech.aurikrex.falkeai.data.PhoneChatRequest
import tech.aurikrex.falkeai.data.SecurePrefs
import tech.aurikrex.falkeai.executors.ActionPlanExecutor
import tech.aurikrex.falkeai.executors.CalendarBridge
import tech.aurikrex.falkeai.executors.FalkeAlarmManager
import tech.aurikrex.falkeai.executors.LocalCommandRouter
import tech.aurikrex.falkeai.executors.PendingUndoAction
import tech.aurikrex.falkeai.monitors.BluetoothAndBatteryMonitor
import tech.aurikrex.falkeai.monitors.FalkeAccessibilityService
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

enum class VoiceState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    FOLLOW_UP_WINDOW
}

data class VoiceUiState(
    val state: VoiceState = VoiceState.IDLE,
    val statusLine: String = "Ready — say \"Hey Falke\", \"Jarvis\", or tap the orb",
    val lastTranscript: String = "",
    val lastReply: String = "",
    val lastActions: List<String> = emptyList(),
    val pendingUndo: PendingUndoAction? = null,
    val undoSecondsLeft: Int = 0,
    val screenVisionAttached: Boolean = false
)

/**
 * Full Voice-In -> Voice-Out Controller powering:
 * - VAD voice recording -> Groq Whisper STT -> Rust J.A.R.V.I.S. Brain -> Groq Orpheus WAV TTS
 * - Feature #1: Silent Android 11 Screen Vision (`takeScreenshot()`) when asked about screen
 * - Feature #2: 5-second Auto Follow-Up Conversation Window after TTS completes
 * - Feature #8: Hands-Free Interactive Morning Commute Briefing
 * - Feature B: Post-Call 1-Tap Voice Note recording -> Turso `contact_memory`
 * - Feature J: 6-second Cancel/Undo countdown before outgoing voice SMS fires
 * - Feature M: 0 MB Offline Cellular Mode fallback via `LocalCommandRouter`
 */
class VoiceSessionController(
    private val context: Context,
    private val prefs: SecurePrefs,
    private val db: FalkeDatabase,
    private val api: FalkeApiClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(VoiceUiState())
    val uiState: StateFlow<VoiceUiState> = _uiState

    private var activeJob: Job? = null
    private var undoJob: Job? = null
    private var activePlayer: MediaPlayer? = null
    private var activeFocusRequest: AudioFocusRequest? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val actionExecutor = ActionPlanExecutor(context, prefs, db) { pending ->
        stageUndoableAction(pending)
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.US
            }
        }
    }

    fun startVoiceTurn(
        captureScreen: Boolean = false,
        followUpChain: Boolean = true,
        postCallPhone: String? = null,
        postCallName: String? = null
    ) {
        activeJob?.cancel()
        stopAudioPlayback()
        FalkeWakeWordService.setSessionPaused(true)

        activeJob = scope.launch {
            try {
                // Capture silent Android 11 screenshot BEFORE the overlay obscures anything if requested
                val screenshotB64 = if (captureScreen && prefs.screenVisionEnabled && FalkeAccessibilityService.isConnected()) {
                    FalkeAccessibilityService.captureScreenJpegBase64()
                } else {
                    null
                }
                val screenText = if (prefs.screenVisionEnabled && FalkeAccessibilityService.isConnected()) {
                    FalkeAccessibilityService.readCurrentScreenText()
                } else {
                    null
                }

                // Feature M: True 0 MB Offline Spoken Voice Mode via on-device SpeechRecognizer + LocalCommandRouter!
                if ((!isNetworkOnline() || !prefs.isPaired()) && postCallPhone == null) {
                    _uiState.value = _uiState.value.copy(
                        state = VoiceState.LISTENING,
                        statusLine = "Offline voice mode (0 MB data) — speak command…"
                    )
                    val offlineSpoken = OfflineSpeechBridge.captureSinglePhrase(context)
                    if (!offlineSpoken.isNullOrBlank()) {
                        val cmd = LocalCommandRouter.route(offlineSpoken)
                        val reply = if (cmd != null) {
                            actionExecutor.executeLocalCommand(cmd)
                        } else {
                            "You're offline. Offline voice supports Calls, SMS, Alarms, Timers, DND, Torch, and Dictation."
                        }
                        _uiState.value = _uiState.value.copy(
                            state = VoiceState.SPEAKING,
                            lastTranscript = offlineSpoken,
                            lastReply = reply,
                            lastActions = listOf("Offline 0MB Voice"),
                            statusLine = reply
                        )
                        speakWithAndroidTts(reply)
                        _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, statusLine = "Ready")
                        return@launch
                    }
                    val fallbackMsg = "Offline and couldn't recognize speech — try typing your command below."
                    _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, lastReply = fallbackMsg, statusLine = fallbackMsg)
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    state = VoiceState.LISTENING,
                    statusLine = if (postCallPhone != null) "Recording call note for ${postCallName ?: postCallPhone}…" else "Listening… speak naturally",
                    screenVisionAttached = screenshotB64 != null
                )

                val wavBytes = recordUtteranceWav(maxSeconds = 14, silenceStopMs = 1350L)
                if (wavBytes == null) {
                    _uiState.value = _uiState.value.copy(
                        state = VoiceState.IDLE,
                        statusLine = "Didn't catch any speech — tap to try again."
                    )
                    return@launch
                }

                // Feature B: Post-Call 1-Tap Voice Note upload
                if (postCallPhone != null) {
                    _uiState.value = _uiState.value.copy(state = VoiceState.THINKING, statusLine = "Saving call note to FalkeAI memory…")
                    val audioB64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                    val res = api.sendCallNote(
                        CallNoteRequest(
                            phone = postCallPhone,
                            contactName = postCallName,
                            audioBase64 = audioB64,
                            audioMime = "audio/wav"
                        )
                    )
                    val msg = res.fold(
                        onSuccess = { "Saved your call note for ${postCallName ?: postCallPhone}." },
                        onFailure = { "Couldn't save call note: ${it.message}" }
                    )
                    _uiState.value = _uiState.value.copy(state = VoiceState.SPEAKING, lastReply = msg, statusLine = msg)
                    speakWithAndroidTts(msg)
                    _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, statusLine = "Done.")
                    return@launch
                }

                _uiState.value = _uiState.value.copy(state = VoiceState.THINKING, statusLine = "Thinking…")

                val devCtx = buildLiveDeviceContext(screenText)
                val audioB64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                val resp = api.chat(
                    PhoneChatRequest(
                        audioBase64 = audioB64,
                        audioMime = "audio/wav",
                        screenshotBase64 = screenshotB64,
                        screenshotMime = if (screenshotB64 != null) "image/jpeg" else null,
                        multilingual = prefs.bilingualMode,
                        wantTts = true,
                        deviceContext = devCtx
                    )
                )

                handleChatResponse(resp, followUpChain)
            } finally {
                if (_uiState.value.state != VoiceState.FOLLOW_UP_WINDOW) {
                    FalkeWakeWordService.setSessionPaused(false)
                }
            }
        }
    }

    fun sendTextCommand(rawText: String, captureScreen: Boolean = false, speakReply: Boolean = true) {
        val text = rawText.trim()
        if (text.isEmpty()) return

        // Handle "Cancel" / "Undo" immediately during 6-second Undo window (Feature J)
        if (_uiState.value.pendingUndo != null &&
            text.lowercase() in setOf("cancel", "undo", "stop", "don't send", "abort")
        ) {
            cancelPendingUndo()
            return
        }

        activeJob?.cancel()
        stopAudioPlayback()
        activeJob = scope.launch {
            // Feature M: Try instant <80ms LocalCommandRouter first when offline OR for pure local toggles!
            val localCmd = LocalCommandRouter.route(text)
            if (localCmd != null && (!isNetworkOnline() || !prefs.isPaired() || isInstantLocalOnly(localCmd))) {
                val res = actionExecutor.executeLocalCommand(localCmd)
                _uiState.value = _uiState.value.copy(
                    state = VoiceState.SPEAKING,
                    lastTranscript = text,
                    lastReply = res,
                    lastActions = listOf("Offline Local Executor"),
                    statusLine = res
                )
                if (speakReply) speakWithAndroidTts(res)
                _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, statusLine = "Ready")
                return@launch
            }

            if (!prefs.isPaired()) {
                _uiState.value = _uiState.value.copy(
                    state = VoiceState.IDLE,
                    lastTranscript = text,
                    lastReply = "Pair your Infinix phone in Setup first (send `pair android` in WhatsApp self-chat)."
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                state = VoiceState.THINKING,
                lastTranscript = text,
                statusLine = "Thinking…"
            )

            val wantsScreen = captureScreen || looksLikeScreenQuestion(text)
            val screenshotB64 = if (wantsScreen && prefs.screenVisionEnabled && FalkeAccessibilityService.isConnected()) {
                FalkeAccessibilityService.captureScreenJpegBase64()
            } else null
            val screenText = if (prefs.screenVisionEnabled && FalkeAccessibilityService.isConnected()) {
                FalkeAccessibilityService.readCurrentScreenText()
            } else null

            val resp = api.chat(
                PhoneChatRequest(
                    text = text,
                    screenshotBase64 = screenshotB64,
                    screenshotMime = if (screenshotB64 != null) "image/jpeg" else null,
                    multilingual = prefs.bilingualMode,
                    wantTts = speakReply,
                    deviceContext = buildLiveDeviceContext(screenText)
                )
            )
            handleChatResponse(resp, followUpChain = false)
        }
    }

    /** Feature #8: Hands-Free Interactive Morning Commute Briefing */
    fun startCommuteBriefing() {
        sendTextCommand(
            "Give me my concise spoken interactive morning commute briefing covering urgent alerts, priority Gmail, WhatsApp approvals, ready follow-ups, bank alerts, and today's calendar.",
            captureScreen = false,
            speakReply = true
        )
    }

    fun cancelPendingUndo() {
        undoJob?.cancel()
        undoJob = null
        val cancelled = _uiState.value.pendingUndo?.summary
        _uiState.value = _uiState.value.copy(
            pendingUndo = null,
            undoSecondsLeft = 0,
            statusLine = if (cancelled != null) "Cancelled: $cancelled" else "Cancelled."
        )
        speakQuickAnnouncement("Cancelled, not sent.")
    }

    fun speakQuickAnnouncement(text: String) {
        if (text.isBlank()) return
        scope.launch {
            speakWithAndroidTts(text)
        }
    }

    fun stopSession() {
        activeJob?.cancel()
        stopAudioPlayback()
        _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, statusLine = "Ready")
        FalkeWakeWordService.setSessionPaused(false)
    }

    private suspend fun handleChatResponse(
        result: Result<tech.aurikrex.falkeai.data.PhoneChatResponse>,
        followUpChain: Boolean
    ) {
        result.fold(
            onSuccess = { resp ->
                // Check if user said "cancel" or "undo" during an active 6s undo window!
                if (_uiState.value.pendingUndo != null &&
                    resp.transcript.trim().lowercase() in setOf("cancel", "undo", "stop", "abort")
                ) {
                    cancelPendingUndo()
                    return
                }

                val deviceNotes = actionExecutor.executeServerActions(resp.deviceActions)
                val allNotes = resp.cloudActionsDone + deviceNotes

                _uiState.value = _uiState.value.copy(
                    state = VoiceState.SPEAKING,
                    lastTranscript = resp.transcript.ifBlank { _uiState.value.lastTranscript },
                    lastReply = resp.replyText,
                    lastActions = allNotes,
                    statusLine = "Speaking…"
                )

                // Duck background music (e.g. Spotify) smoothly while FalkeAI speaks!
                requestDuckingAudioFocus()
                try {
                    val playedCloudAudio = if (!resp.ttsAudioBase64.isNullOrBlank()) {
                        playBase64Audio(resp.ttsAudioBase64, resp.ttsMime ?: "audio/wav")
                    } else {
                        false
                    }
                    if (!playedCloudAudio && resp.replyText.isNotBlank()) {
                        speakWithAndroidTts(resp.replyText)
                    }
                } finally {
                    abandonDuckingAudioFocus()
                }

                // Feature #2: 5-second Auto Follow-Up Window (keep listening briefly without wake word!)
                if (followUpChain) {
                    _uiState.value = _uiState.value.copy(
                        state = VoiceState.FOLLOW_UP_WINDOW,
                        statusLine = "Listening for follow-up (5s)…"
                    )
                    val heardFollowUp = detectSpeechWithinWindow(windowMs = 5_000L)
                    if (heardFollowUp) {
                        startVoiceTurn(captureScreen = false, followUpChain = true)
                        return
                    }
                }
                _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, statusLine = "Ready")
                FalkeWakeWordService.setSessionPaused(false)
            },
            onFailure = { err ->
                val msg = err.message ?: "Network error"
                _uiState.value = _uiState.value.copy(
                    state = VoiceState.IDLE,
                    lastReply = "Couldn't reach FalkeAI server: $msg",
                    statusLine = "Error: $msg"
                )
                FalkeWakeWordService.setSessionPaused(false)
            }
        )
    }

    private fun stageUndoableAction(pending: PendingUndoAction) {
        undoJob?.cancel()
        _uiState.value = _uiState.value.copy(pendingUndo = pending, undoSecondsLeft = 6)
        undoJob = scope.launch {
            for (sec in 6 downTo 1) {
                _uiState.value = _uiState.value.copy(undoSecondsLeft = sec)
                delay(1000L)
            }
            _uiState.value = _uiState.value.copy(pendingUndo = null, undoSecondsLeft = 0)
            val result = withContext(Dispatchers.IO) { pending.execute() }
            _uiState.value = _uiState.value.copy(statusLine = result)
        }
    }

    private suspend fun buildLiveDeviceContext(screenText: String?): DeviceContextPayload =
        withContext(Dispatchers.IO) {
            val hw = BluetoothAndBatteryMonitor.snapshot(context)
            val cal = CalendarBridge.formatUpcomingStrings(context)
            val alarms = db.alarmDao().getEnabled().map {
                "${FalkeAlarmManager.formatAlarmTime(it.hour, it.minute)} ${it.label}"
            }
            val notifs = db.notificationDao().getRecent(10).map {
                "${it.appLabel}: ${it.title} — ${it.bodyRedacted.take(80)}"
            }
            DeviceContextPayload(
                batteryPct = hw.batteryPct,
                isCharging = hw.isCharging,
                dndActive = hw.dndActive,
                btConnected = hw.btConnected,
                calendarUpcoming = cal,
                recentNotifications = notifs,
                activeAlarms = alarms,
                screenText = screenText
            )
        }

    private fun isInstantLocalOnly(cmd: tech.aurikrex.falkeai.executors.LocalDeviceCommand): Boolean {
        return cmd is tech.aurikrex.falkeai.executors.LocalDeviceCommand.ToggleFlashlight ||
            cmd is tech.aurikrex.falkeai.executors.LocalDeviceCommand.FindPhoneSiren ||
            cmd is tech.aurikrex.falkeai.executors.LocalDeviceCommand.MediaControl ||
            cmd is tech.aurikrex.falkeai.executors.LocalDeviceCommand.DictateText
    }

    private fun looksLikeScreenQuestion(text: String): Boolean {
        val l = text.lowercase()
        return l.contains("on my screen") || l.contains("this screen") || l.contains("read this") ||
            l.contains("what is this") || l.contains("reply to this") || l.contains("summarize this")
    }

    private fun isNetworkOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun recordUtteranceWav(maxSeconds: Int, silenceStopMs: Long): ByteArray? =
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return@withContext null
            }
            val sr = EnergyVad.SAMPLE_RATE
            val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(3200)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sr,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@withContext null
            }

            val allSamples = ArrayList<Short>(sr * 4)
            val chunk = ShortArray(1600) // 100ms
            var speechStarted = false
            var silenceMs = 0L
            val startAt = System.currentTimeMillis()

            try {
                recorder.startRecording()
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - startAt
                    if (elapsed > maxSeconds * 1000L) break
                    val read = recorder.read(chunk, 0, chunk.size)
                    if (read <= 0) continue

                    val rms = EnergyVad.rmsEnergy(chunk, read)
                    for (i in 0 until read) allSamples.add(chunk[i])

                    if (rms > 0.015) {
                        speechStarted = true
                        silenceMs = 0L
                    } else if (speechStarted) {
                        silenceMs += 100L
                        if (silenceMs >= silenceStopMs) break
                    } else if (elapsed > 4_500L) {
                        break // No speech within initial 4.5s
                    }
                }
            } finally {
                runCatching { recorder.stop(); recorder.release() }
            }

            if (!speechStarted || allSamples.size < sr / 4) return@withContext null
            val pcm = ShortArray(allSamples.size) { allSamples[it] }
            EnergyVad.encodePcm16ToWav(pcm, sr)
        }

    private suspend fun detectSpeechWithinWindow(windowMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return@withContext false
            }
            val sr = EnergyVad.SAMPLE_RATE
            val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(3200)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sr,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@withContext false
            }
            val chunk = ShortArray(1600)
            val startAt = System.currentTimeMillis()
            var consecutiveVoiceFrames = 0
            try {
                recorder.startRecording()
                while (isActive && System.currentTimeMillis() - startAt < windowMs) {
                    val read = recorder.read(chunk, 0, chunk.size)
                    if (read > 0 && EnergyVad.rmsEnergy(chunk, read) > 0.018) {
                        consecutiveVoiceFrames++
                        if (consecutiveVoiceFrames >= 2) return@withContext true
                    } else {
                        consecutiveVoiceFrames = 0
                    }
                }
            } finally {
                runCatching { recorder.stop(); recorder.release() }
            }
            false
        }

    private suspend fun playBase64Audio(b64: String, mime: String): Boolean =
        withContext(Dispatchers.IO) {
            val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return@withContext false
            val ext = if (mime.contains("ogg")) "ogg" else if (mime.contains("mp3") || mime.contains("mpeg")) "mp3" else "wav"
            val tmp = File(context.cacheDir, "falke_tts_${System.currentTimeMillis()}.$ext")
            tmp.writeBytes(bytes)
            try {
                suspendCancellableCoroutine { cont ->
                    runCatching {
                        val mp = MediaPlayer().apply {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            setDataSource(tmp.absolutePath)
                            setOnCompletionListener {
                                release()
                                if (activePlayer === this) activePlayer = null
                                if (cont.isActive) cont.resume(true)
                            }
                            setOnErrorListener { _, _, _ ->
                                release()
                                if (activePlayer === this) activePlayer = null
                                if (cont.isActive) cont.resume(false)
                                true
                            }
                            prepare()
                            start()
                        }
                        activePlayer = mp
                        cont.invokeOnCancellation {
                            runCatching { mp.stop(); mp.release() }
                            if (activePlayer === mp) activePlayer = null
                        }
                    }.onFailure {
                        if (cont.isActive) cont.resume(false)
                    }
                }
            } finally {
                runCatching { tmp.delete() }
            }
        }

    private suspend fun speakWithAndroidTts(raw: String) {
        if (!ttsReady) return
        val clean = raw.replace(Regex("[*_`#•]"), " ").trim().take(700)
        if (clean.isEmpty()) return
        suspendCancellableCoroutine<Unit> { cont ->
            val id = "falke_${System.currentTimeMillis()}"
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
            })
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { tts?.stop() }
        }
    }

    private fun requestDuckingAudioFocus() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        activeFocusRequest = req
        runCatching { am.requestAudioFocus(req) }
    }

    private fun abandonDuckingAudioFocus() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        activeFocusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        activeFocusRequest = null
    }

    private fun stopAudioPlayback() {
        abandonDuckingAudioFocus()
        runCatching {
            activePlayer?.stop()
            activePlayer?.release()
            activePlayer = null
        }
        runCatching { tts?.stop() }
    }
}
