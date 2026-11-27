package tech.aurikrex.falkeai.ui

import android.app.Application
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.assist.FalkeOverlayService
import tech.aurikrex.falkeai.data.DashboardResponse
import tech.aurikrex.falkeai.data.ServerSettingsDto
import tech.aurikrex.falkeai.monitors.FalkeBridgeService
import tech.aurikrex.falkeai.monitors.OemSurvivalHelper
import tech.aurikrex.falkeai.monitors.SurvivalHealthReport
import tech.aurikrex.falkeai.voice.CustomVoiceStatus
import tech.aurikrex.falkeai.voice.EnrollmentSummary
import tech.aurikrex.falkeai.voice.FalkeWakeWordService

data class LocalSettingsSnapshot(
    val isPaired: Boolean,
    val serverUrl: String,
    val deviceName: String,
    val keystoreResetNotice: Boolean,
    val speakTypedReplies: Boolean,
    val persistChatHistory: Boolean,
    val screenVisionEnabled: Boolean,
    val btAutoReadoutEnabled: Boolean,
    val bilingualMode: Boolean,
    val confirmUnknownNumbers: Boolean,
    val appLockEnabled: Boolean,
    val requireLockForSensitive: Boolean,
    val floatingBubbleEnabled: Boolean,
    val wakeWordEnabled: Boolean,
    val wakeWords: List<String>,
    val voiceLockEnabled: Boolean,
    val hasVoiceprint: Boolean,
    val enrolledSampleCount: Int,
    val voiceMatchThreshold: Float,
    val customVoiceEnabled: Boolean,
    val speechSpeed: Float,
    val infinixAutoStartConfirmed: Boolean,
    val hasFallbackPin: Boolean
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FalkeApplication
    private val prefs = app.prefs

    private val _localSettings = MutableStateFlow(readLocalSnapshot())
    val localSettings: StateFlow<LocalSettingsSnapshot> = _localSettings

    private val _health = MutableStateFlow(
        OemSurvivalHelper.inspectHealth(app, prefs.infinixAutoStartConfirmed)
    )
    val health: StateFlow<SurvivalHealthReport> = _health

    private val _dashboard = MutableStateFlow<DashboardResponse?>(null)
    val dashboard: StateFlow<DashboardResponse?> = _dashboard

    private val _dashLoading = MutableStateFlow(false)
    val dashLoading: StateFlow<Boolean> = _dashLoading

    private val _dashError = MutableStateFlow<String?>(null)
    val dashError: StateFlow<String?> = _dashError

    private val _serverSettings = MutableStateFlow<ServerSettingsDto?>(null)
    val serverSettings: StateFlow<ServerSettingsDto?> = _serverSettings

    private val _serverHealthy = MutableStateFlow<Boolean?>(null)
    val serverHealthy: StateFlow<Boolean?> = _serverHealthy

    private val _customVoiceStatus = MutableStateFlow(app.customVoiceTts.inspectStatus())
    val customVoiceStatus: StateFlow<CustomVoiceStatus> = _customVoiceStatus

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner

    private fun readLocalSnapshot(): LocalSettingsSnapshot = LocalSettingsSnapshot(
        isPaired = prefs.isPaired(),
        serverUrl = prefs.serverUrl,
        deviceName = prefs.deviceName,
        keystoreResetNotice = prefs.keystoreResetNoticePending,
        speakTypedReplies = prefs.speakTypedReplies,
        persistChatHistory = prefs.persistChatHistory,
        screenVisionEnabled = prefs.screenVisionEnabled,
        btAutoReadoutEnabled = prefs.btAutoReadoutEnabled,
        bilingualMode = prefs.bilingualMode,
        confirmUnknownNumbers = prefs.confirmUnknownNumbers,
        appLockEnabled = prefs.appLockEnabled,
        requireLockForSensitive = prefs.requireLockForSensitive,
        floatingBubbleEnabled = prefs.floatingBubbleEnabled,
        wakeWordEnabled = prefs.wakeWordEnabled,
        wakeWords = prefs.wakeWords,
        voiceLockEnabled = prefs.voiceLockEnabled,
        hasVoiceprint = prefs.hasVoiceprint(),
        enrolledSampleCount = prefs.enrolledSampleCount,
        voiceMatchThreshold = prefs.voiceMatchThreshold,
        customVoiceEnabled = prefs.customVoiceEnabled,
        speechSpeed = prefs.speechSpeed,
        infinixAutoStartConfirmed = prefs.infinixAutoStartConfirmed,
        hasFallbackPin = prefs.hasFallbackPin()
    )

    fun syncLocalState() {
        _localSettings.value = readLocalSnapshot()
        _health.value = OemSurvivalHelper.inspectHealth(app, prefs.infinixAutoStartConfirmed)
        _customVoiceStatus.value = app.customVoiceTts.inspectStatus()
    }

    /** Called on `ON_RESUME` so every permission, overlay, assistant, and cloud setting reflects live OS/server state! */
    fun refreshAllLiveState(fetchRemote: Boolean = true) {
        syncLocalState()
        if (prefs.floatingBubbleEnabled && Settings.canDrawOverlays(app)) {
            FalkeOverlayService.start(app)
        }
        if (fetchRemote && prefs.isPaired()) {
            refreshDashboard()
            refreshServerSettings()
        }
    }

    fun dismissBanner() {
        _statusBanner.value = null
        if (prefs.keystoreResetNoticePending) {
            prefs.keystoreResetNoticePending = false
            syncLocalState()
        }
    }

    fun postBanner(msg: String) {
        _statusBanner.value = msg
    }

    fun refreshDashboard() {
        if (!prefs.isPaired()) return
        _dashLoading.value = true
        _dashError.value = null
        viewModelScope.launch {
            app.api.fetchDashboard()
                .onSuccess {
                    _dashboard.value = it
                    _dashError.value = null
                    _serverHealthy.value = true
                }
                .onFailure {
                    _dashError.value = it.message ?: "Could not sync briefing"
                }
            _dashLoading.value = false
        }
    }

    fun refreshServerSettings() {
        if (!prefs.isPaired()) return
        viewModelScope.launch {
            app.api.fetchServerSettings().onSuccess {
                _serverSettings.value = it
                _serverHealthy.value = true
            }
        }
    }

    fun pingServerHealth(urlOverride: String? = null) {
        viewModelScope.launch {
            app.api.checkServerHealth(urlOverride)
                .onSuccess { ok ->
                    _serverHealthy.value = ok
                    postBanner(if (ok) "Server /health is ONLINE" else "Server responded unexpectedly")
                }
                .onFailure {
                    _serverHealthy.value = false
                    postBanner("Health check failed: ${it.message}")
                }
        }
    }

    fun updateServerSetting(
        digestHourLagos: Int? = null,
        replyMode: String? = null,
        selfVoiceMode: String? = null
    ) {
        viewModelScope.launch {
            app.api.updateServerSettings(digestHourLagos, replyMode, selfVoiceMode)
                .onSuccess {
                    _serverSettings.value = it
                    postBanner("Saved cloud setting")
                }
                .onFailure {
                    postBanner("Failed saving cloud setting: ${it.message}")
                }
        }
    }

    fun pairDevice(serverUrl: String, pairCode: String, deviceLabel: String, onDone: () -> Unit) {
        viewModelScope.launch {
            app.api.pair(serverUrl, pairCode, deviceLabel)
                .onSuccess {
                    FalkeBridgeService.start(app)
                    if (prefs.wakeWordEnabled) FalkeWakeWordService.start(app)
                    syncLocalState()
                    refreshDashboard()
                    refreshServerSettings()
                    postBanner("Phone paired & encrypted in Android Keystore")
                    onDone()
                }
                .onFailure {
                    postBanner("Pairing failed: ${it.message}")
                }
        }
    }

    fun unpairThisPhone(revokeAll: Boolean = false) {
        viewModelScope.launch {
            app.api.unpairThisDevice(revokeAll)
            FalkeBridgeService.stop(app)
            _dashboard.value = null
            _serverSettings.value = null
            syncLocalState()
            postBanner("Phone unpaired and token revoked")
        }
    }

    // ---- Reactive Preference Updaters ----

    fun setSpeakTypedReplies(enabled: Boolean) {
        prefs.speakTypedReplies = enabled
        syncLocalState()
    }

    fun setPersistChatHistory(enabled: Boolean) {
        prefs.persistChatHistory = enabled
        if (!enabled) app.voiceController.clearHistory()
        syncLocalState()
    }

    fun setScreenVision(enabled: Boolean) {
        prefs.screenVisionEnabled = enabled
        syncLocalState()
    }

    fun setBtReadout(enabled: Boolean) {
        prefs.btAutoReadoutEnabled = enabled
        syncLocalState()
    }

    fun setBilingualMode(enabled: Boolean) {
        prefs.bilingualMode = enabled
        syncLocalState()
    }

    fun setConfirmUnknownNumbers(enabled: Boolean) {
        prefs.confirmUnknownNumbers = enabled
        syncLocalState()
    }

    fun setAppLock(enabled: Boolean) {
        prefs.appLockEnabled = enabled
        syncLocalState()
    }

    fun setRequireSensitiveLock(enabled: Boolean) {
        prefs.requireLockForSensitive = enabled
        syncLocalState()
    }

    fun setFallbackPin(pin: String) {
        prefs.setFallbackPin(pin)
        syncLocalState()
        postBanner("Fallback PIN saved")
    }

    fun setFloatingBubble(enabled: Boolean) {
        if (enabled && !Settings.canDrawOverlays(app)) {
            OemSurvivalHelper.openOverlaySettings(app)
            postBanner("Grant 'Display over other apps' first, then enable Floating Bar")
            return
        }
        prefs.floatingBubbleEnabled = enabled
        if (enabled) FalkeOverlayService.start(app) else FalkeOverlayService.stop(app)
        syncLocalState()
    }

    fun setInfinixAutoStartConfirmed(confirmed: Boolean) {
        prefs.infinixAutoStartConfirmed = confirmed
        syncLocalState()
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        prefs.wakeWordEnabled = enabled
        if (enabled) FalkeWakeWordService.start(app) else FalkeWakeWordService.stop(app)
        syncLocalState()
    }

    fun addWakePhrase(phrase: String) {
        val clean = phrase.trim()
        if (clean.isEmpty()) return
        val updated = (prefs.wakeWords + clean).distinct()
        prefs.wakeWords = updated
        FalkeWakeWordService.reloadKeywords(app)
        syncLocalState()
    }

    fun removeWakePhrase(phrase: String) {
        if (prefs.wakeWords.size <= 1) return
        prefs.wakeWords = prefs.wakeWords.filterNot { it == phrase }
        FalkeWakeWordService.reloadKeywords(app)
        syncLocalState()
    }

    // ---- Voice Lock & Own-Voice Studio Actions ----

    fun setVoiceLockEnabled(enabled: Boolean) {
        if (enabled && !prefs.hasVoiceprint()) {
            postBanner("Enroll your voice first (5 quick phrases)")
            return
        }
        prefs.voiceLockEnabled = enabled
        syncLocalState()
    }

    fun setVoiceMatchThreshold(threshold: Float) {
        prefs.voiceMatchThreshold = threshold
        syncLocalState()
    }

    fun enrollVoiceprintFromPcmList(samples: List<ShortArray>, onResult: (Result<EnrollmentSummary>) -> Unit) {
        viewModelScope.launch {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.speakerVerifier.enrollFromUtterances(samples)
            }
            syncLocalState()
            onResult(res)
        }
    }

    fun enrollVoiceprintFromStudioTakes(onResult: (Result<EnrollmentSummary>) -> Unit) {
        viewModelScope.launch {
            val takes = app.datasetRecorder.loadRecordedTakesAs16kPcm(maxCount = 8)
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.speakerVerifier.enrollFromUtterances(takes)
            }
            syncLocalState()
            onResult(res)
        }
    }

    fun clearVoiceprint() {
        prefs.clearVoiceprint()
        syncLocalState()
        postBanner("Cleared enrolled voiceprint")
    }

    fun importCustomVoiceZip(uri: Uri) {
        viewModelScope.launch {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.customVoiceTts.importVoiceZipFromUri(uri)
            }
            syncLocalState()
            res.fold(
                onSuccess = { st -> postBanner("Imported your custom voice (${st.modelSizeKb} KB)!") },
                onFailure = { e -> postBanner("Voice import failed: ${e.message}") }
            )
        }
    }

    fun setCustomVoiceEnabled(enabled: Boolean) {
        prefs.customVoiceEnabled = enabled
        syncLocalState()
    }

    fun setSpeechSpeed(speed: Float) {
        prefs.speechSpeed = speed
        syncLocalState()
    }

    fun removeCustomVoice() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            app.customVoiceTts.deleteCustomVoice()
            syncLocalState()
            postBanner("Removed imported custom voice")
        }
    }

    fun clearCapturedNotifications() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            app.db.notificationDao().clearAll()
            postBanner("Cleared local notification feed")
        }
    }
}
