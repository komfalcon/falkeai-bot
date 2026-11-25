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
import kotlinx.coroutines.withTimeoutOrNull
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

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val isUser: Boolean,
    val text: String,
    val actions: List<String> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis(),
    val isError: Boolean = false
)

data class VoiceUiState(
    val state: VoiceState = VoiceState.IDLE,
    val statusLine: String = "Tap the orb or type below",
    val lastTranscript: String = "",
    val lastReply: String = "",
    val lastActions: List<String> = emptyList(),
    val chatHistory: List<ChatMessage> = emptyList(),
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
    private var sessionGen: Long = 0L
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
        val myGen = ++sessionGen
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
                            chatHistory = appendChatMessages(
                                _uiState.value.chatHistory,
                                ChatMessage(isUser = true, text = offlineSpoken),
                                ChatMessage(isUser = false, text = reply, actions = listOf("Offline 0MB Voice"))
                            ),
                            statusLine = "Speaking…"
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

                handleChatResponse(
                    result = resp,
                    followUpChain = followUpChain,
                    speakReply = true,
                    alreadyAppendedUserText = false
                )
            } finally {
                if (myGen == sessionGen && _uiState.value.state != VoiceState.FOLLOW_UP_WINDOW) {
                    if (_uiState.value.state != VoiceState.IDLE) {
                        _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, statusLine = "Ready")
                    }
                    FalkeWakeWordService.setSessionPaused(false)
                }
            }
        }
    }

    fun sendTextCommand(
        rawText: String,
        captureScreen: Boolean = false,
        speakReply: Boolean? = null
    ) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val shouldSpeak = speakReply ?: prefs.speakTypedReplies

        // Handle "Cancel" / "Undo" immediately during 6-second Undo window (Feature J)
        if (_uiState.value.pendingUndo != null &&
            text.lowercase() in setOf("cancel", "undo", "stop", "don't send", "abort")
        ) {
            cancelPendingUndo()
            return
        }

        activeJob?.cancel()
        val myGen = ++sessionGen
        stopAudioPlayback()
        activeJob = scope.launch {
            try {
                // Feature M: Try instant <80ms LocalCommandRouter first when offline OR for pure local toggles!
                val localCmd = LocalCommandRouter.route(text)
                if (localCmd != null && (!isNetworkOnline() || !prefs.isPaired() || isInstantLocalOnly(localCmd))) {
                    val res = actionExecutor.executeLocalCommand(localCmd)
                    _uiState.value = _uiState.value.copy(
                        state = if (shouldSpeak) VoiceState.SPEAKING else VoiceState.IDLE,
                        lastTranscript = text,
                        lastReply = res,
                        lastActions = listOf("Offline Local Executor"),
                        chatHistory = appendChatMessages(
                            _uiState.value.chatHistory,
                            ChatMessage(isUser = true, text = text),
                            ChatMessage(isUser = false, text = res, actions = listOf("Offline Local Executor"))
                        ),
                        statusLine = if (shouldSpeak) "Speaking…" else "Ready"
                    )
                    if (shouldSpeak) speakWithAndroidTts(res)
                    _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, statusLine = "Ready")
                    return@launch
                }

                if (!prefs.isPaired()) {
                    val pairMsg = "Pair your phone in Setup first (send `pair android` in WhatsApp self-chat)."
                    _uiState.value = _uiState.value.copy(
                        state = VoiceState.IDLE,
                        lastTranscript = text,
                        lastReply = pairMsg,
                        chatHistory = appendChatMessages(
                            _uiState.value.chatHistory,
                            ChatMessage(isUser = true, text = text),
                            ChatMessage(isUser = false, text = pairMsg, isError = true)
                        ),
                        statusLine = "Not paired"
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    state = VoiceState.THINKING,
                    lastTranscript = text,
                    chatHistory = appendChatMessages(
                        _uiState.value.chatHistory,
                        ChatMessage(isUser = true, text = text)
                    ),
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
                        wantTts = shouldSpeak,
                        deviceContext = buildLiveDeviceContext(screenText)
                    )
                )
                handleChatResponse(
                    result = resp,
                    followUpChain = false,
                    speakReply = shouldSpeak,
                    alreadyAppendedUserText = true
                )
            } finally {
                if (myGen == sessionGen && _uiState.value.state != VoiceState.IDLE) {
                    _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, statusLine = "Ready")
                }
            }
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

    fun replayMessage(text: String) {
        if (text.isBlank()) return
        activeJob?.cancel()
        val myGen = ++sessionGen
        stopAudioPlayback()
        activeJob = scope.launch {
            try {
                _uiState.value = _uiState.value.copy(state = VoiceState.SPEAKING, statusLine = "Speaking… tap orb to stop")
                requestDuckingAudioFocus()
                try {
                    speakWithAndroidTts(text)
                } finally {
                    abandonDuckingAudioFocus()
                }
            } finally {
                if (myGen == sessionGen) {
                    _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, statusLine = "Ready")
                }
            }
        }
    }

    fun clearHistory() {
        _uiState.value = _uiState.value.copy(
            chatHistory = emptyList(),
            lastTranscript = "",
            lastReply = "",
            lastActions = emptyList(),
            statusLine = "Ready"
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
        ++sessionGen
        activeJob?.cancel()
        stopAudioPlayback()
        _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, statusLine = "Ready")
        FalkeWakeWordService.setSessionPaused(false)
    }

    private fun appendChatMessages(
        existing: List<ChatMessage>,
        vararg added: ChatMessage
    ): List<ChatMessage> {
        val out = existing.toMutableList()
        for (m in added) {
            if (m.text.isNotBlank()) out.add(m)
        }
        return if (out.size > 40) out.takeLast(40) else out
    }

    private suspend fun handleChatResponse(
        result: Result<tech.aurikrex.falkeai.data.PhoneChatResponse>,
        followUpChain: Boolean,
        speakReply: Boolean,
        alreadyAppendedUserText: Boolean
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
                val finalTranscript = resp.transcript.ifBlank { _uiState.value.lastTranscript }

                val updatedHistory = if (!alreadyAppendedUserText && finalTranscript.isNotBlank()) {
                    appendChatMessages(
                        _uiState.value.chatHistory,
                        ChatMessage(isUser = true, text = finalTranscript),
                        ChatMessage(isUser = false, text = resp.replyText, actions = allNotes)
                    )
                } else {
                    appendChatMessages(
                        _uiState.value.chatHistory,
                        ChatMessage(isUser = false, text = resp.replyText, actions = allNotes)
                    )
                }

                if (speakReply) {
                    _uiState.value = _uiState.value.copy(
                        state = VoiceState.SPEAKING,
                        lastTranscript = finalTranscript,
                        lastReply = resp.replyText,
                        lastActions = allNotes,
                        chatHistory = updatedHistory,
                        statusLine = "Speaking… tap orb to stop"
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
                } else {
                    _uiState.value = _uiState.value.copy(
                        state = VoiceState.IDLE,
                        lastTranscript = finalTranscript,
                        lastReply = resp.replyText,
                        lastActions = allNotes,
                        chatHistory = updatedHistory,
                        statusLine = "Ready"
                    )
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
                val errText = "Couldn't reach FalkeAI server: $msg"
                _uiState.value = _uiState.value.copy(
                    state = VoiceState.IDLE,
                    lastReply = errText,
                    chatHistory = appendChatMessages(
                        _uiState.value.chatHistory,
                        ChatMessage(isUser = false, text = errText, isError = true)
                    ),
                    statusLine = "Connection issue"
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
                withTimeoutOrNull(45_000L) {
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
                } ?: false
            } finally {
                runCatching { activePlayer?.stop(); activePlayer?.release(); activePlayer = null }
                runCatching { tmp.delete() }
            }
        }

    private suspend fun speakWithAndroidTts(raw: String) {
        val engine = tts
        if (!ttsReady || engine == null) return
        val clean = raw.replace(Regex("[*_`#•]"), " ").replace(Regex("\\s+"), " ").trim().take(700)
        if (clean.isEmpty()) return
        // Bounded timeout + immediate resume on TextToSpeech.ERROR so the orb can NEVER stick in SPEAKING!
        withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                val id = "falke_${System.currentTimeMillis()}"
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(Unit)
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (utteranceId == id && cont.isActive) cont.resume(Unit)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(Unit)
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id && cont.isActive) cont.resume(Unit)
                    }
                })
                val result = runCatching {
                    engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id)
                }.getOrDefault(TextToSpeech.ERROR)
                if (result != TextToSpeech.SUCCESS && cont.isActive) {
                    cont.resume(Unit)
                }
                cont.invokeOnCancellation { runCatching { engine.stop() } }
            }
        }
        runCatching { engine.stop() }
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
