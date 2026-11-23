package tech.aurikrex.falkeai.voice

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.NotificationChannels
import tech.aurikrex.falkeai.R
import tech.aurikrex.falkeai.assist.FalkeAssistActivity
import tech.aurikrex.falkeai.executors.PhoneMediaExecutor
import tech.aurikrex.falkeai.ui.MainActivity

/**
 * Split Foreground Service #2 (`microphone`):
 * Runs the ~35 MB on-device `sherpa-onnx` Multi-Wake-Word listener 24/7 while enabled.
 * Separated from `FalkeBridgeService` so Android 14/15 never rejects `BOOT_COMPLETED` startup.
 */
class FalkeWakeWordService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: SherpaWakeWordEngine? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        NotificationChannels.ensureAll(this)
        startMicForeground("Listening for Hey Falke, Jarvis, Buddy you there…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_KEYWORDS -> {
                val words = FalkeApplication.instance.prefs.wakeWords
                engine?.reloadKeywords(words)
                return START_STICKY
            }
            ACTION_SET_PAUSED -> {
                val paused = intent.getBooleanExtra(EXTRA_PAUSED, false)
                engine?.setSessionPaused(paused)
                return START_STICKY
            }
        }

        val app = FalkeApplication.instance
        if (!app.prefs.wakeWordEnabled ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (engine == null) {
            engine = SherpaWakeWordEngine(this) { matchedKeyword ->
                handleWakeWordMatch(matchedKeyword)
            }
            val started = engine?.start(serviceScope, app.prefs.wakeWords) == true
            _running.value = started
            if (!started) {
                startMicForeground("Wake-word standby (tap FalkeAI orb or hold Power/Home)")
            }
        }
        return START_STICKY
    }

    private fun handleWakeWordMatch(keyword: String) {
        val lower = keyword.lowercase()
        // Feature #3: Instant offline "Buddy, you there?" Find-My-Phone Siren & Torch Strobe!
        if (lower.contains("buddy")) {
            PhoneMediaExecutor.triggerFindMyPhoneSiren(this, durationSeconds = 12)
            FalkeApplication.instance.voiceController.speakQuickAnnouncement("I'm right here, boss!")
            engine?.setSessionPaused(false)
            return
        }

        // Launch the floating J.A.R.V.I.S. Assistant overlay & start listening immediately!
        val launch = Intent(this, FalkeAssistActivity::class.java).apply {
            putExtra(FalkeAssistActivity.EXTRA_WAKE_WORD, keyword)
            putExtra(FalkeAssistActivity.EXTRA_AUTO_LISTEN, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { startActivity(launch) }
    }

    private fun startMicForeground(text: String) {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, NotificationChannels.WAKE_FG)
            .setSmallIcon(R.drawable.ic_falke_bolt)
            .setContentTitle("FalkeAI Wake Word Active")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(WAKE_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(WAKE_NOTIF_ID, notif)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        engine?.stop()
        engine = null
        _running.value = false
        if (activeInstance === this) activeInstance = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val WAKE_NOTIF_ID = 3002
        const val ACTION_STOP = "tech.aurikrex.falkeai.WAKE_STOP"
        const val ACTION_RELOAD_KEYWORDS = "tech.aurikrex.falkeai.WAKE_RELOAD"
        const val ACTION_SET_PAUSED = "tech.aurikrex.falkeai.WAKE_SET_PAUSED"
        const val EXTRA_PAUSED = "paused"

        @Volatile
        private var activeInstance: FalkeWakeWordService? = null

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: Context) {
            val intent = Intent(context, FalkeWakeWordService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, FalkeWakeWordService::class.java).setAction(ACTION_STOP)
                )
            }
        }

        fun reloadKeywords(context: Context) {
            activeInstance?.let {
                it.engine?.reloadKeywords(FalkeApplication.instance.prefs.wakeWords)
            }
        }

        fun setSessionPaused(paused: Boolean) {
            activeInstance?.engine?.setSessionPaused(paused)
        }
    }
}
