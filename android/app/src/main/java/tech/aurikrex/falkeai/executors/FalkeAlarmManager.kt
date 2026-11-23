package tech.aurikrex.falkeai.executors

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.data.FalkeAlarmEntity
import tech.aurikrex.falkeai.data.FalkeDatabase
import tech.aurikrex.falkeai.monitors.UrgentAlarmActivity
import java.util.Calendar
import java.util.Locale

/**
 * Dual-Strategy Alarm CRUD Engine:
 * 1) Full Set / Edit / Delete / Toggle / List managed cleanly in Room + AlarmManager.setAlarmClock()
 *    (guaranteed exact wake through Doze & Infinix XOS 7.6!).
 * 2) Optional system Clock intent bridge for timers (`ACTION_SET_TIMER`).
 */
object FalkeAlarmManager {
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_ALARM_LABEL = "alarm_label"

    /** Pure deterministic calculation for unit testing & exact scheduling. */
    fun computeNextTriggerMs(
        hour: Int,
        minute: Int,
        daysMask: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (daysMask == 0) {
            if (cal.timeInMillis <= nowMs) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }
        for (offset in 0..7) {
            val probe = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
            val dayBit = 1 shl (probe.get(Calendar.DAY_OF_WEEK) - 1) // Sun=1 -> bit 0
            if ((daysMask and dayBit) != 0 && probe.timeInMillis > nowMs) {
                return probe.timeInMillis
            }
        }
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun formatAlarmTime(hour: Int, minute: Int): String {
        val amPm = if (hour >= 12) "PM" else "AM"
        val h12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format(Locale.US, "%d:%02d %s", h12, minute, amPm)
    }

    suspend fun createAlarm(
        context: Context,
        db: FalkeDatabase,
        hour: Int,
        minute: Int,
        label: String,
        daysMask: Int = 0
    ): FalkeAlarmEntity {
        val triggerMs = computeNextTriggerMs(hour, minute, daysMask)
        val draft = FalkeAlarmEntity(
            hour = hour.coerceIn(0, 23),
            minute = minute.coerceIn(0, 59),
            label = label.ifBlank { "FalkeAI Alarm" },
            daysMask = daysMask,
            enabled = true,
            nextTriggerAtMs = triggerMs
        )
        val id = db.alarmDao().insert(draft)
        val saved = draft.copy(id = id)
        scheduleSystemAlarm(context, saved)
        return saved
    }

    suspend fun editAlarm(
        context: Context,
        db: FalkeDatabase,
        query: String,
        newHour: Int,
        newMinute: Int,
        newLabel: String? = null
    ): FalkeAlarmEntity? {
        val all = db.alarmDao().getAll()
        val match = findMatchingAlarm(all, query) ?: return null
        val triggerMs = computeNextTriggerMs(newHour, newMinute, match.daysMask)
        val updated = match.copy(
            hour = newHour.coerceIn(0, 23),
            minute = newMinute.coerceIn(0, 59),
            label = newLabel?.takeIf { it.isNotBlank() } ?: match.label,
            enabled = true,
            nextTriggerAtMs = triggerMs,
            updatedAtMs = System.currentTimeMillis()
        )
        db.alarmDao().update(updated)
        scheduleSystemAlarm(context, updated)
        return updated
    }

    suspend fun toggleAlarm(context: Context, db: FalkeDatabase, alarmId: Long, enabled: Boolean) {
        val existing = db.alarmDao().getById(alarmId) ?: return
        val nextMs = if (enabled) computeNextTriggerMs(existing.hour, existing.minute, existing.daysMask) else 0L
        val updated = existing.copy(enabled = enabled, nextTriggerAtMs = nextMs, updatedAtMs = System.currentTimeMillis())
        db.alarmDao().update(updated)
        if (enabled) scheduleSystemAlarm(context, updated) else cancelSystemAlarm(context, alarmId)
    }

    suspend fun deleteAlarmById(context: Context, db: FalkeDatabase, alarmId: Long) {
        cancelSystemAlarm(context, alarmId)
        db.alarmDao().deleteById(alarmId)
    }

    suspend fun deleteMatchingAlarm(context: Context, db: FalkeDatabase, query: String): FalkeAlarmEntity? {
        val all = db.alarmDao().getAll()
        val match = findMatchingAlarm(all, query) ?: return null
        deleteAlarmById(context, db, match.id)
        return match
    }

    suspend fun rescheduleAllEnabled(context: Context, db: FalkeDatabase) {
        val now = System.currentTimeMillis()
        for (alarm in db.alarmDao().getEnabled()) {
            val nextMs = computeNextTriggerMs(alarm.hour, alarm.minute, alarm.daysMask, now)
            val updated = alarm.copy(nextTriggerAtMs = nextMs)
            db.alarmDao().update(updated)
            scheduleSystemAlarm(context, updated)
        }
    }

    fun findMatchingAlarm(alarms: List<FalkeAlarmEntity>, query: String): FalkeAlarmEntity? {
        if (alarms.isEmpty()) return null
        val q = query.trim().lowercase()
        if (q.isEmpty() || q == "next" || q == "all") return alarms.firstOrNull { it.enabled } ?: alarms.first()
        val parsedTime = LocalCommandRouter.parseTime24(q)
        if (parsedTime != null) {
            alarms.firstOrNull { it.hour == parsedTime.first && it.minute == parsedTime.second }?.let { return it }
            alarms.firstOrNull { it.hour == parsedTime.first }?.let { return it }
        }
        return alarms.firstOrNull { it.label.lowercase().contains(q) }
            ?: alarms.firstOrNull { formatAlarmTime(it.hour, it.minute).lowercase().contains(q) }
    }

    fun startSystemTimer(context: Context, seconds: Int, label: String): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(5))
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "FalkeAI Timer" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun scheduleSystemAlarm(context: Context, alarm: FalkeAlarmEntity) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmPendingIntent(context, alarm.id, alarm.label)
        val showPi = PendingIntent.getActivity(
            context,
            (alarm.id + 50_000).toInt(),
            UrgentAlarmActivity.buildIntent(
                context = context,
                title = "FalkeAI Alarm — ${formatAlarmTime(alarm.hour, alarm.minute)}",
                summary = alarm.label,
                source = "alarm",
                sender = ""
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            val info = AlarmManager.AlarmClockInfo(alarm.nextTriggerAtMs, showPi)
            am.setAlarmClock(info, pi)
        }.onFailure {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.nextTriggerAtMs, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, alarm.nextTriggerAtMs, pi)
            }
        }
    }

    fun cancelSystemAlarm(context: Context, alarmId: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(alarmPendingIntent(context, alarmId, ""))
    }

    private fun alarmPendingIntent(context: Context, alarmId: Long, label: String): PendingIntent {
        val intent = Intent(context, AlarmRingReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_ALARM_LABEL, label)
        }
        return PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class AlarmRingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(FalkeAlarmManager.EXTRA_ALARM_ID, -1L)
        val label = intent.getStringExtra(FalkeAlarmManager.EXTRA_ALARM_LABEL) ?: "FalkeAI Alarm"

        val launch = UrgentAlarmActivity.buildIntent(
            context = context,
            title = "⏰ FalkeAI Alarm",
            summary = label,
            source = "alarm",
            sender = ""
        )
        context.startActivity(launch)

        if (alarmId > 0) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = FalkeApplication.instance.db
                    val rec = db.alarmDao().getById(alarmId)
                    if (rec != null) {
                        if (rec.daysMask == 0) {
                            db.alarmDao().update(rec.copy(enabled = false))
                        } else {
                            val next = FalkeAlarmManager.computeNextTriggerMs(
                                rec.hour,
                                rec.minute,
                                rec.daysMask,
                                System.currentTimeMillis() + 61_000L
                            )
                            val updated = rec.copy(nextTriggerAtMs = next)
                            db.alarmDao().update(updated)
                            FalkeAlarmManager.scheduleSystemAlarm(context, updated)
                        }
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
