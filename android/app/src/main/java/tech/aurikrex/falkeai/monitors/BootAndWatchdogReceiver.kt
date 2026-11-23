package tech.aurikrex.falkeai.monitors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.executors.FalkeAlarmManager
import java.util.concurrent.TimeUnit

/**
 * Revives FalkeBridgeService (dataSync|remoteMessaging — Android 14/15 boot-safe) and reschedules
 * all Room exact alarms after phone reboot, app update, or Infinix XOS heartbeat pulse.
 */
class BootAndWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FalkeBridgeService.start(context)
        FalkeBridgeService.scheduleHeartbeatPulse(context)
        FalkeWatchdogWorker.schedulePeriodic(context)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FalkeAlarmManager.rescheduleAllEnabled(context, FalkeApplication.instance.db)
            } finally {
                pending.finish()
            }
        }
    }
}

class FalkeWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = FalkeApplication.instance
        if (app.prefs.isPaired()) {
            FalkeBridgeService.start(applicationContext)
            FalkeBridgeService.scheduleHeartbeatPulse(applicationContext)
        }
        FalkeAlarmManager.rescheduleAllEnabled(applicationContext, app.db)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "falkeai_xos_watchdog"

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<FalkeWatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
