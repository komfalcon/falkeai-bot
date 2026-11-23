package tech.aurikrex.falkeai

import android.app.Application
import tech.aurikrex.falkeai.data.FalkeApiClient
import tech.aurikrex.falkeai.data.FalkeDatabase
import tech.aurikrex.falkeai.data.SecurePrefs
import tech.aurikrex.falkeai.monitors.FalkeWatchdogWorker
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
    lateinit var voiceController: VoiceSessionController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationChannels.ensureAll(this)
        prefs = SecurePrefs(this)
        db = FalkeDatabase.getInstance(this)
        api = FalkeApiClient(prefs)
        voiceController = VoiceSessionController(this, prefs, db, api)
        FalkeWatchdogWorker.schedulePeriodic(this)
    }

    companion object {
        @Volatile
        lateinit var instance: FalkeApplication
            private set
    }
}
