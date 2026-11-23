package tech.aurikrex.falkeai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.aurikrex.falkeai.executors.LocalCommandRouter
import tech.aurikrex.falkeai.executors.LocalDeviceCommand

class LocalCommandRouterTest {
    @Test
    fun routesOfflineCellularCommandsDeterministically() {
        val call = LocalCommandRouter.route("Call Chidi")
        assertTrue(call is LocalDeviceCommand.CallContact)
        assertEquals("Chidi", (call as LocalDeviceCommand.CallContact).target)

        val sms = LocalCommandRouter.route("Send SMS to Mom: Just landed in Lagos")
        assertTrue(sms is LocalDeviceCommand.SendSms)
        assertEquals("Mom", (sms as LocalDeviceCommand.SendSms).target)
        assertEquals("Just landed in Lagos", sms.message)

        val alarm = LocalCommandRouter.route("Set an alarm for 6:30am morning workout")
        assertTrue(alarm is LocalDeviceCommand.SetAlarm)
        assertEquals(6, (alarm as LocalDeviceCommand.SetAlarm).hour)
        assertEquals(30, alarm.minute)

        val edit = LocalCommandRouter.route("Edit my 6:30am alarm to 7:15am")
        assertTrue(edit is LocalDeviceCommand.EditAlarm)
        assertEquals(7, (edit as LocalDeviceCommand.EditAlarm).newHour)
        assertEquals(15, edit.newMinute)

        val del = LocalCommandRouter.route("Delete my 7am alarm")
        assertTrue(del is LocalDeviceCommand.DeleteAlarm)

        val timer = LocalCommandRouter.route("Set a timer for 25 minutes")
        assertTrue(timer is LocalDeviceCommand.SetTimer)
        assertEquals(1500, (timer as LocalDeviceCommand.SetTimer).seconds)

        val torch = LocalCommandRouter.route("Turn on flashlight")
        assertEquals(LocalDeviceCommand.ToggleFlashlight(true), torch)

        val dnd = LocalCommandRouter.route("Turn on DND")
        assertEquals(LocalDeviceCommand.SetDnd(true, "priority"), dnd)

        val buddy = LocalCommandRouter.route("Buddy, you there?")
        assertEquals(LocalDeviceCommand.FindPhoneSiren, buddy)

        val dictate = LocalCommandRouter.route("Type this: Good morning team, pushing the build now.")
        assertTrue(dictate is LocalDeviceCommand.DictateText)
        assertEquals("Good morning team, pushing the build now.", (dictate as LocalDeviceCommand.DictateText).text)
    }
}
