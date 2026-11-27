package tech.aurikrex.falkeai.monitors

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.sse.EventSource
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.NotificationChannels
import tech.aurikrex.falkeai.R
import tech.aurikrex.falkeai.data.TelemetryRequest
import tech.aurikrex.falkeai.executors.CalendarBridge
import tech.aurikrex.falkeai.executors.PhoneMediaExecutor
import tech.aurikrex.falkeai.ui.MainActivity

/**
 * Split Foreground Service #1 (`dataSync|remoteMessaging` — safe on BOOT_COMPLETED):
 * Keeps the 24/7 SSE Phone Bridge alive on Infinix Hot 10T (Android 11 / XOS 7.6),
 * pushes periodic battery/calendar/notification telemetry, and dispatches server alarms,
 * Find-My-Phone sirens, and scheduled SMS messages instantaneously.
 */
class FalkeBridgeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private var eventSource: EventSource? = null
    private var reconnectJob: Job? = null
    private var telemetryJob: Job? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var backoffMs: Long = 2_000L

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureAll(this)
        startForegroundSafe("Connecting to FalkeAI brain…")
        registerBatteryMonitor()
        registerNetworkMonitor()
        scheduleHeartbeatPulse(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connectSseIfPaired()
        startTelemetryLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Infinix XOS swipe-from-recents self-heal
        scheduleHeartbeatPulse(this, delayMs = 3_000L)
    }

    override fun onDestroy() {
        eventSource?.cancel()
        eventSource = null
        unregisterMonitors()
        FalkeApplication.instance.api.markSseDisconnected()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafe(statusText: String) {
        val notif = buildForegroundNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FG_NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FG_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FG_NOTIF_ID, notif)
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NotificationChannels.BRIDGE_FG)
            .setSmallIcon(R.drawable.ic_falke_bolt)
            .setContentTitle("FalkeAI Live Bridge")
            .setContentText(statusText)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(FG_NOTIF_ID, buildForegroundNotification(statusText))
    }

    private fun connectSseIfPaired() {
        val app = FalkeApplication.instance
        if (!app.prefs.isPaired()) {
            updateNotification("Not paired yet — open FalkeAI to pair")
            return
        }
        reconnectJob?.cancel()
        eventSource?.cancel()
        eventSource = app.api.openSseStream(
            onEvent = { eventId, type, data ->
                backoffMs = 2_000L
                updateNotification("Connected • Listening for urgent alarms & commands")
                handleIncomingServerEvent(eventId, type, data)
            },
            onDisconnected = {
                updateNotification("Reconnecting to FalkeAI…")
                scheduleReconnect()
            }
        )
        if (eventSource != null) {
            updateNotification("Live • Connected to FalkeAI")
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2L).coerceAtMost(60_000L)
            connectSseIfPaired()
        }
    }

    private fun handleIncomingServerEvent(eventId: Long, type: String, rawJson: String) {
        val obj = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return
        fun field(vararg keys: String): String {
            for (k in keys) {
                val v = obj[k]?.jsonPrimitive?.contentOrNull?.trim()
                if (!v.isNullOrEmpty()) return v
            }
            return ""
        }
        val eventAtMs = obj["at"]?.jsonPrimitive?.longOrNull ?: 0L
        val isStale = eventAtMs > 0L && (System.currentTimeMillis() - eventAtMs) > 30 * 60_000L

        when (type.lowercase()) {
            "urgent_alarm" -> {
                if (isStale) return
                val source = field("source").ifBlank { "whatsapp" }
                val sender = field("sender", "phone")
                val who = field("contact_name", "sender").ifBlank { "Urgent Contact" }
                val subject = field("subject", "request", "summary").ifBlank { "Needs your immediate attention" }
                val title = if (source == "gmail") "🚨 URGENT EMAIL: $who" else "🚨 URGENT: $who"
                fireFullScreenUrgentAlarm(title, subject, source, sender)
            }
            "find_phone" -> {
                if (isStale) return
                PhoneMediaExecutor.triggerFindMyPhoneSiren(this, durationSeconds = 15)
                FalkeApplication.instance.voiceController.speakQuickAnnouncement(
                    field("message").ifBlank { "I'm right here, boss!" }
                )
            }
            "send_sms" -> {
                val prefs = FalkeApplication.instance.prefs
                if (prefs.hasProcessedSmsEvent(eventId)) return
                val phone = field("phone", "target")
                val msg = field("message")
                if (phone.isNotBlank() && msg.isNotBlank() && PhoneMediaExecutor.isSafeDialOrSmsNumber(phone)) {
                    prefs.markSmsEventProcessed(eventId)
                    PhoneMediaExecutor.sendSmsNow(this, phone, msg, confirmUnknownNumbers = false)
                }
            }
        }
    }

    private fun fireFullScreenUrgentAlarm(title: String, summary: String, source: String, sender: String) {
        val launchIntent = UrgentAlarmActivity.buildIntent(this, title, summary, source, sender)
        val fullScreenPi = PendingIntent.getActivity(
            this,
            (System.currentTimeMillis() and 0x7FFF).toInt(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, NotificationChannels.URGENT_ALARM)
            .setSmallIcon(R.drawable.ic_falke_bolt)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPi, true)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(URGENT_NOTIF_ID, notif)
        runCatching { startActivity(launchIntent) }
    }

    private fun startTelemetryLoop() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = serviceScope.launch {
            while (isActive) {
                val app = FalkeApplication.instance
                if (app.prefs.isPaired()) {
                    val hw = BluetoothAndBatteryMonitor.snapshot(this@FalkeBridgeService)
                    val upcoming = CalendarBridge.formatUpcomingStrings(this@FalkeBridgeService)
                    val recent = runCatching {
                        app.db.notificationDao().getRecent(12).map {
                            "${it.appLabel}: ${it.title} — ${it.bodyRedacted.take(80)}"
                        }
                    }.getOrDefault(emptyList())
                    app.api.sendTelemetry(
                        TelemetryRequest(
                            batteryPct = hw.batteryPct,
                            isCharging = hw.isCharging,
                            dndActive = hw.dndActive,
                            btConnected = hw.btConnected,
                            calendarUpcoming = upcoming,
                            recentNotifications = recent
                        )
                    )
                }
                delay(15 * 60_000L)
            }
        }
    }

    private fun registerBatteryMonitor() {
        val receiver = BluetoothAndBatteryMonitor.createBatteryReceiver()
        batteryReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun registerNetworkMonitor() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                backoffMs = 2_000L
                connectSseIfPaired()
            }
        }
        networkCallback = cb
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(req, cb) }
    }

    private fun unregisterMonitors() {
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        batteryReceiver = null
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    companion object {
        const val ACTION_HEARTBEAT_PULSE = "tech.aurikrex.falkeai.ACTION_HEARTBEAT_PULSE"
        private const val FG_NOTIF_ID = 3001
        const val URGENT_NOTIF_ID = 3099

        fun start(context: Context) {
            val intent = Intent(context, FalkeBridgeService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FalkeBridgeService::class.java)) }
        }

        fun scheduleHeartbeatPulse(context: Context, delayMs: Long = 10 * 60_000L) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                context,
                9100,
                Intent(context, BootAndWatchdogReceiver::class.java).apply {
                    action = ACTION_HEARTBEAT_PULSE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + delayMs
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            }
        }
    }
}
