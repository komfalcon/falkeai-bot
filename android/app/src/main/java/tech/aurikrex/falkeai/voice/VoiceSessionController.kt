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
import android.speech.tts.Voice
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
import tech.aurikrex.falkeai.data.ChatHistoryEntity
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
import tech.aurikrex.falkeai.security.DeviceCredentialGate
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
    val screenVisionAttached: Boolean = false,
    val lastVoiceMatchScore: Float? = null,
    val voiceRejectedNeedsPin: Boolean = false
)

private data class RecordedVoiceCapture(
    val pcm16: ShortArray,
    val wavBytes: ByteArray
)

/**
 * Unified Voice-In -> Speaker-Verify -> Brain -> Own-Voice / Cloud / Local TTS Controller:
 * - On-device Speaker Verification (`SpeakerVerificationEngine`) blocks unauthorized voices before upload
 * - On-device Own-Voice VITS/Piper TTS (`CustomVoiceTtsEngine`) speaks in Falcon's trained voice for $0
 * - "Fall-K" pronunciation & transcript normalization (`PronunciationMapper`) across every engine
 * - Persistent Room `chat_history` so conversations survive app restarts cleanly
 */
class VoiceSessionController(
    private val context: Context,
    private val prefs: SecurePrefs,
    private val db: FalkeDatabase,
    private val api: FalkeApiClient,
    private val speakerVerifier: SpeakerVerificationEngine,
    private val customVoiceTts: CustomVoiceTtsEngine
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
                tts?.let { engine ->
                    engine.language = Locale.US
                    selectSweetJarvisFemaleVoice(engine)
                }
            }
        }
        // Load persisted chat history from Room on startup
        scope.launch(Dispatchers.IO) {
            if (prefs.persistChatHistory) {
                val saved = runCatching { db.chatDao().getRecentDesc(50).reversed() }.getOrDefault(emptyList())
                if (saved.isNotEmpty()) {
                    val mapped = saved.map { row ->
                        ChatMessage(
                            id = row.id,
                            isUser = row.isUser,
                            text = row.text,
                            actions = if (row.actionsJoined.isBlank()) emptyList() else row.actionsJoined.split("||"),
                            timestampMs = row.timestampMs,
                            isError = row.isError
                        )
                    }
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            chatHistory = mapped,
                            lastReply = mapped.lastOrNull { !it.isUser }?.text.orEmpty(),
                            lastTranscript = mapped.lastOrNull { it.isUser }?.text.orEmpty()
                        )
                    }
                }
            }
        }
    }

    fun startVoiceTurn(
        captureScreen: Boolean = false,
        followUpChain: Boolean = true,
        postCallPhone: String? = null,
        postCallName: String? = null,
        bypassVoiceLockOnce: Boolean = false
    ) {
        activeJob?.cancel()
        val myGen = ++sessionGen
        stopAudioPlayback()
        FalkeWakeWordService.setSessionPaused(true)

        activeJob = scope.launch {
            try {
                val screenshotB64 = if (captureScreen && prefs.screenVisionEnabled && FalkeAccessibilityService.isConnected()) {
                    FalkeAccessibilityService.captureScreenJpegBase64()
                } else null
                val screenText = if (prefs.screenVisionEnabled && FalkeAccessibilityService.isConnected()) {
                    FalkeAccessibilityService.readCurrentScreenText()
                } else null

                _uiState.value = _uiState.value.copy(
                    state = VoiceState.LISTENING,
                    statusLine = if (postCallPhone != null) "Recording note for ${postCallName ?: postCallPhone}…" else "Listening…",
                    screenVisionAttached = screenshotB64 != null,
                    voiceRejectedNeedsPin = false
                )

                val capture = recordUtteranceCapture(maxSeconds = 14, silenceStopMs = 1350L)
                if (capture == null) {
                    _uiState.value = _uiState.value.copy(
                        state = VoiceState.IDLE,
                        statusLine = "Didn't catch speech — tap to try again."
                    )
                    return@launch
                }

                // On-Device Speaker Verification Gate ("Only Falcon's Voice Works")
                val canBypass = bypassVoiceLockOnce || DeviceCredentialGate.isSensitiveActionRecentlyVerified()
                if (!canBypass && prefs.voiceLockEnabled && prefs.hasVoiceprint()) {
                    val outcome = withContext(Dispatchers.IO) {
                        speakerVerifier.verifyUtterance(capture.pcm16, EnergyVad.SAMPLE_RATE)
                    }
                    when (outcome) {
                        is SpeakerVerifyOutcome.Rejected -> {
                            val pct = (outcome.similarity * 100).toInt().coerceAtLeast(0)
                            val rejectMsg = "Voice not recognized ($pct% match). Fall-K responds only to Falcon's voice — unlock with PIN/Pattern to override."
                            _uiState.value = _uiState.value.copy(
                                state = VoiceState.IDLE,
                                statusLine = "Blocked unknown speaker ($pct%)",
                                lastVoiceMatchScore = outcome.similarity,
                                voiceRejectedNeedsPin = true,
                                lastReply = rejectMsg,
                                chatHistory = recordAndAppendMessages(
                                    ChatMessage(isUser = false, text = rejectMsg, isError = true)
                                )
                            )
                            return@launch
                        }
                        is SpeakerVerifyOutcome.Verified -> {
                            _uiState.value = _uiState.value.copy(
                                lastVoiceMatchScore = outcome.similarity,
                                voiceRejectedNeedsPin = false
                            )
                        }
                        else -> Unit
                    }
                }

                // Offline mode fallback if network unavailable or unpaired
                if ((!isNetworkOnline() || !prefs.isPaired()) && postCallPhone == null) {
                    val offlineSpokenRaw = OfflineSpeechBridge.captureSinglePhrase(context)
                    val offlineSpoken = offlineSpokenRaw?.let { PronunciationMapper.normalizeHeardTranscript(it) }
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
                            chatHistory = recordAndAppendMessages(
                                ChatMessage(isUser = true, text = offlineSpoken),
                                ChatMessage(isUser = false, text = reply, actions = listOf("Offline 0MB Voice"))
                            ),
                            statusLine = "Speaking…"
                        )
                        speakBestAvailable(reply, cloudAudioB64 = null, cloudMime = null)
                        _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, statusLine = "Ready")
                        return@launch
                    }
                    val fallbackMsg = "You're offline — type your offline command below."
                    _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, lastReply = fallbackMsg, statusLine = fallbackMsg)
                    return@launch
                }

                // Post-Call 1-Tap Voice Note upload
                if (postCallPhone != null) {
                    _uiState.value = _uiState.value.copy(state = VoiceState.THINKING, statusLine = "Saving call note…")
                    val audioB64 = Base64.encodeToString(capture.wavBytes, Base64.NO_WRAP)
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
                    speakBestAvailable(msg, cloudAudioB64 = null, cloudMime = null)
                    _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, statusLine = "Done.")
                    return@launch
                }

                _uiState.value = _uiState.value.copy(state = VoiceState.THINKING, statusLine = "Thinking…")

                // If Falcon's custom own-voice VITS model is active locally, skip cloud WAV download for faster response!
                val useLocalOwnVoice = customVoiceTts.inspectStatus().active
                val devCtx = buildLiveDeviceContext(screenText)
                val audioB64 = Base64.encodeToString(capture.wavBytes, Base64.NO_WRAP)
                val resp = api.chat(
                    PhoneChatRequest(
                        audioBase64 = audioB64,
                        audioMime = "audio/wav",
                        screenshotBase64 = screenshotB64,
                        screenshotMime = if (screenshotB64 != null) "image/jpeg" else null,
                        multilingual = prefs.bilingualMode,
                        wantTts = !useLocalOwnVoice,
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
        val text = PronunciationMapper.normalizeHeardTranscript(rawText.trim())
        if (text.isEmpty()) return
        val shouldSpeak = speakReply ?: prefs.speakTypedReplies

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
                val localCmd = LocalCommandRouter.route(text)
                if (localCmd != null && (!isNetworkOnline() || !prefs.isPaired() || isInstantLocalOnly(localCmd))) {
                    val res = actionExecutor.executeLocalCommand(localCmd)
                    _uiState.value = _uiState.value.copy(
                        state = if (shouldSpeak) VoiceState.SPEAKING else VoiceState.IDLE,
                        lastTranscript = text,
                        lastReply = res,
                        lastActions = listOf("Local Device"),
                        chatHistory = recordAndAppendMessages(
                            ChatMessage(isUser = true, text = text),
                            ChatMessage(isUser = false, text = res, actions = listOf("Local Device"))
                        ),
                        statusLine = if (shouldSpeak) "Speaking…" else "Ready"
                    )
                    if (shouldSpeak) speakBestAvailable(res, null, null)
                    _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, statusLine = "Ready")
                    return@launch
                }

                if (!prefs.isPaired()) {
                    val pairMsg = "Pair your phone in Settings first (send `pair android` in WhatsApp self-chat)."
                    _uiState.value = _uiState.value.copy(
                        state = VoiceState.IDLE,
                        lastTranscript = text,
                        lastReply = pairMsg,
                        chatHistory = recordAndAppendMessages(
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
                    chatHistory = recordAndAppendMessages(
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

                val useLocalOwnVoice = customVoiceTts.inspectStatus().active
                val resp = api.chat(
                    PhoneChatRequest(
                        text = text,
                        screenshotBase64 = screenshotB64,
                        screenshotMime = if (screenshotB64 != null) "image/jpeg" else null,
                        multilingual = prefs.bilingualMode,
                        wantTts = shouldSpeak && !useLocalOwnVoice,
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
                _uiState.value = _uiState.value.copy(state = VoiceState.SPEAKING, statusLine = "Speaking… tap to stop")
                speakBestAvailable(text, cloudAudioB64 = null, cloudMime = null)
            } finally {
                if (myGen == sessionGen) {
                    _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, statusLine = "Ready")
                }
            }
        }
    }

    fun clearHistory() {
        scope.launch(Dispatchers.IO) {
            runCatching { db.chatDao().clearAll() }
        }
        _uiState.value = _uiState.value.copy(
            chatHistory = emptyList(),
            lastTranscript = "",
            lastReply = "",
            lastActions = emptyList(),
            voiceRejectedNeedsPin = false,
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
            speakBestAvailable(text, cloudAudioB64 = null, cloudMime = null)
        }
    }

    fun stopSession() {
        ++sessionGen
        activeJob?.cancel()
        stopAudioPlayback()
        _uiState.value = _uiState.value.copy(state = VoiceState.IDLE, statusLine = "Ready")
        FalkeWakeWordService.setSessionPaused(false)
    }

    private fun recordAndAppendMessages(vararg added: ChatMessage): List<ChatMessage> {
        val valid = added.filter { it.text.isNotBlank() }
        if (valid.isNotEmpty() && prefs.persistChatHistory) {
            scope.launch(Dispatchers.IO) {
                for (m in valid) {
                    runCatching {
                        db.chatDao().insert(
                            ChatHistoryEntity(
                                isUser = m.isUser,
                                text = m.text,
                                actionsJoined = m.actions.joinToString("||"),
                                isError = m.isError,
                                timestampMs = m.timestampMs
                            )
                        )
                    }
                }
                runCatching { db.chatDao().pruneOld() }
            }
        }
        val out = (_uiState.value.chatHistory + valid)
        return if (out.size > 60) out.takeLast(60) else out
    }

    private suspend fun handleChatResponse(
        result: Result<tech.aurikrex.falkeai.data.PhoneChatResponse>,
        followUpChain: Boolean,
        speakReply: Boolean,
        alreadyAppendedUserText: Boolean
    ) {
        result.fold(
            onSuccess = { resp ->
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
                    recordAndAppendMessages(
                        ChatMessage(isUser = true, text = finalTranscript),
                        ChatMessage(isUser = false, text = resp.replyText, actions = allNotes)
                    )
                } else {
                    recordAndAppendMessages(
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
                        statusLine = "Speaking… tap to stop"
                    )
                    speakBestAvailable(resp.replyText, resp.ttsAudioBase64, resp.ttsMime)
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

                if (followUpChain) {
                    _uiState.value = _uiState.value.copy(
                        state = VoiceState.FOLLOW_UP_WINDOW,
                        statusLine = "Listening for follow-up (5s)…"
                    )
                    val heardFollowUp = detectSpeechWithinWindow(windowMs = 5_000L)
                    if (heardFollowUp) {
                        startVoiceTurn(captureScreen = false, followUpChain = true, bypassVoiceLockOnce = false)
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
                    chatHistory = recordAndAppendMessages(
                        ChatMessage(isUser = false, text = errText, isError = true)
                    ),
                    statusLine = "Connection issue"
                )
                FalkeWakeWordService.setSessionPaused(false)
            }
        )
    }

    /**
     * Priority Voice Synthesis Chain:
     * 1) Falcon's Own Trained Voice (`CustomVoiceTtsEngine` — 100% offline ONNX VITS/Piper, $0)
     * 2) Server Orpheus / Aura WAV (`cloudAudioB64` with "Fall-K" server pronunciation)
     * 3) On-Device Android `TextToSpeech` (with `PronunciationMapper.toSpokenFalke` "Fall-K" mapping)
     */
    private suspend fun speakBestAvailable(
        rawText: String,
        cloudAudioB64: String?,
        cloudMime: String?
    ) {
        if (rawText.isBlank() && cloudAudioB64.isNullOrBlank()) return
        requestDuckingAudioFocus()
        try {
            // 1) Try Falcon's Own Trained Voice first!
            if (rawText.isNotBlank() && customVoiceTts.inspectStatus().active) {
                val ownWav = withContext(Dispatchers.IO) { customVoiceTts.synthesizeToWavFile(rawText) }
                if (ownWav != null) {
                    val ok = playAudioFile(ownWav, deleteWhenDone = true)
                    if (ok) return
                }
            }
            // 2) Try Cloud TTS audio if present
            if (!cloudAudioB64.isNullOrBlank()) {
                val ok = playBase64Audio(cloudAudioB64, cloudMime ?: "audio/wav")
                if (ok) return
            }
            // 3) Fallback to Android System TTS with "Fall-K" pronunciation
            if (rawText.isNotBlank()) {
                speakWithAndroidTts(rawText)
            }
        } finally {
            abandonDuckingAudioFocus()
        }
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

    private suspend fun recordUtteranceCapture(maxSeconds: Int, silenceStopMs: Long): RecordedVoiceCapture? =
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
            val chunk = ShortArray(1600)
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
                        break
                    }
                }
            } finally {
                runCatching { recorder.stop(); recorder.release() }
            }

            if (!speechStarted || allSamples.size < sr / 4) return@withContext null
            val pcm = ShortArray(allSamples.size) { allSamples[it] }
            RecordedVoiceCapture(
                pcm16 = pcm,
                wavBytes = EnergyVad.encodePcm16ToWav(pcm, sr)
            )
        }

    /** Records a single 3.5-second enrollment phrase at 16 kHz for Speaker Verification setup. */
    suspend fun recordEnrollmentPhrasePcm16(): ShortArray? =
        recordUtteranceCapture(maxSeconds = 7, silenceStopMs = 1100L)?.pcm16

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
            playAudioFile(tmp, deleteWhenDone = true)
        }

    private suspend fun playAudioFile(file: File, deleteWhenDone: Boolean): Boolean =
        withContext(Dispatchers.IO) {
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
                                setDataSource(file.absolutePath)
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
                if (deleteWhenDone) runCatching { file.delete() }
            }
        }

    /**
     * Picks the warmest, cleanest female English neural voice available on the Android device
     * (preferring crisp British/US Google female voices for a refined female J.A.R.V.I.S. / F.R.I.D.A.Y. tone).
     */
    private fun selectSweetJarvisFemaleVoice(engine: TextToSpeech) {
        val voices = runCatching { engine.voices }.getOrNull() ?: return
        val englishVoices = voices.filter { v ->
            v.locale != null &&
                v.locale.language.equals("en", ignoreCase = true) &&
                v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        }
        if (englishVoices.isEmpty()) return

        // Well-known crisp Google female neural voice IDs across Android devices (en-GB & en-US)
        val preferredFemaleIds = listOf(
            "en-gb-x-gba",
            "en-gb-x-gbc",
            "en-gb-x-rjs",
            "en-us-x-tpf",
            "en-us-x-sfg",
            "en-us-x-iob",
            "en-us-x-tpc"
        )
        val maleHints = listOf("male", "-m-", "gbd", "gbb", "iom", "iol", "tpd", "sfb")

        fun score(v: Voice): Int {
            val name = v.name.lowercase(Locale.US)
            var s = 0
            // Strong boost for known refined female voices
            val prefIdx = preferredFemaleIds.indexOfFirst { name.contains(it) }
            if (prefIdx >= 0) s += 100 - (prefIdx * 5)
            if (name.contains("female") || name.contains("#female")) s += 60
            if (maleHints.any { name.contains(it) } && !name.contains("female")) s -= 80
            // Prefer refined UK / US English for J.A.R.V.I.S.-like articulation
            when (v.locale.country.uppercase(Locale.US)) {
                "GB" -> s += 20
                "US" -> s += 14
                "AU", "IE" -> s += 10
            }
            if (v.quality >= Voice.QUALITY_HIGH) s += 18
            else if (v.quality >= Voice.QUALITY_NORMAL) s += 8
            // Prefer local installed neural voices so offline fallback works instantaneously
            if (!v.isNetworkConnectionRequired) s += 12
            return s
        }

        val best = englishVoices.maxByOrNull { score(it) } ?: return
        runCatching { engine.voice = best }
    }

    private suspend fun speakWithAndroidTts(raw: String) {
        val engine = tts
        if (!ttsReady || engine == null) return
        val clean = PronunciationMapper.toSpokenFalke(raw, maxChars = 700)
        if (clean.isEmpty()) return
        selectSweetJarvisFemaleVoice(engine)
        engine.setPitch(1.03f)
        engine.setSpeechRate(prefs.speechSpeed)
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
