package tech.aurikrex.falkeai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.aurikrex.falkeai.executors.FalkeAlarmManager
import java.util.Calendar

class AlarmScheduleTest {
    @Test
    fun computesNextAlarmTriggerAccurately() {
        val base = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 24, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Future time same day (14:30)
        val sameDay = FalkeAlarmManager.computeNextTriggerMs(14, 30, 0, base.timeInMillis)
        assertTrue(sameDay > base.timeInMillis)
        val diffHours = (sameDay - base.timeInMillis) / 3_600_000.0
        assertEquals(4.5, diffHours, 0.01)

        // Past time today (06:30) rolls to tomorrow 06:30 (+20.5 hours)
        val nextDay = FalkeAlarmManager.computeNextTriggerMs(6, 30, 0, base.timeInMillis)
        val diffNext = (nextDay - base.timeInMillis) / 3_600_000.0
        assertEquals(20.5, diffNext, 0.01)

        assertEquals("6:30 AM", FalkeAlarmManager.formatAlarmTime(6, 30))
        assertEquals("12:00 PM", FalkeAlarmManager.formatAlarmTime(12, 0))
        assertEquals("11:45 PM", FalkeAlarmManager.formatAlarmTime(23, 45))
    }
}
