package tech.aurikrex.falkeai

import android.app.Application
import tech.aurikrex.falkeai.assist.FalkeOverlayService
import tech.aurikrex.falkeai.data.FalkeApiClient
import tech.aurikrex.falkeai.data.FalkeDatabase
import tech.aurikrex.falkeai.data.SecurePrefs
import tech.aurikrex.falkeai.monitors.FalkeWatchdogWorker
import tech.aurikrex.falkeai.voice.CustomVoiceTtsEngine
import tech.aurikrex.falkeai.voice.DatasetRecorderManager
import tech.aurikrex.falkeai.voice.SpeakerVerificationEngine
import tech.aurikrex.falkeai.voice.VoiceSessionController
import java.util.UUID

class FalkeApplication : Application() {
    /** Per-process token preventing third-party apps from spoofing exported FalkeAssistActivity extras. */
    val internalLaunchToken: String = UUID.randomUUID().toString()

    lateinit var prefs: SecurePrefs
        private set
    lateinit var db: FalkeDatabase
        private set
    lateinit var api: FalkeApiClient
        private set
    lateinit var speakerVerifier: SpeakerVerificationEngine
        private set
    lateinit var customVoiceTts: CustomVoiceTtsEngine
        private set
    lateinit var datasetRecorder: DatasetRecorderManager
        private set
    lateinit var voiceController: VoiceSessionController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationChannels.ensureAll(this)
        prefs = SecurePrefs(this)
        db = FalkeDatabase.getInstance(this)
        api = FalkeApiClient(prefs)
        speakerVerifier = SpeakerVerificationEngine(this, prefs)
        customVoiceTts = CustomVoiceTtsEngine(this, prefs)
        datasetRecorder = DatasetRecorderManager(this)
        voiceController = VoiceSessionController(
            context = this,
            prefs = prefs,
            db = db,
            api = api,
            speakerVerifier = speakerVerifier,
            customVoiceTts = customVoiceTts
        )
        FalkeWatchdogWorker.schedulePeriodic(this)
        if (prefs.floatingBubbleEnabled && android.provider.Settings.canDrawOverlays(this)) {
            FalkeOverlayService.start(this)
        }
    }

    companion object {
        @Volatile
        lateinit var instance: FalkeApplication
            private set
    }
}
