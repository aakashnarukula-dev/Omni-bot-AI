package com.gyftalala.omni

import android.provider.AlarmClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.alarms.ClockAlarm
import com.gyftalala.omni.data.OmniStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class ClockAlarmTest {
    @Before fun reset() = TestSupport.reset()
    @Test fun explicitAlarmBypassesAiAndPendingReminder() {
        val fake = FakeGemini("reminder"); val vm = TestSupport.vm(fake.client)
        TestSupport.await(vm.send("Call Suresh")); val before = vm.state.value.memories
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        TestSupport.await(vm.send("set a alarm for 12:55am"))
        assertEquals(before, vm.state.value.memories); assertTrue(vm.state.value.reminders.isEmpty()); assertEquals(0, fake.calls)
        val request = vm.state.value.clockLaunch!!
        val intent = request.alarm.intent()
        assertEquals(AlarmClock.ACTION_SET_ALARM, intent.action)
        assertEquals(0, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1)); assertEquals(55, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertFalse(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true)); assertFalse(intent.hasExtra(AlarmClock.EXTRA_DAYS))
        assertFalse(vm.state.value.messages.last().text.contains("I'll remind"))
        assertFalse(vm.state.value.messages.last().text.contains("alarm is set"))
    }
    @Test fun dailyClarificationSurvivesViewModelRecreationAndDoesNotScheduleReminder() {
        var vm = TestSupport.vm()
        TestSupport.await(vm.send("set an alarm every day")); assertNull(vm.state.value.clockLaunch)
        vm = TestSupport.vm(); TestSupport.await(vm.send("7 am"))
        val alarm = vm.state.value.clockLaunch!!.alarm
        assertTrue(alarm.daily); assertEquals((1..7).toList(), alarm.intent().getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS))
        assertTrue(vm.state.value.memories.isEmpty()); assertTrue(vm.state.value.reminders.isEmpty())
    }
    @Test fun handoffIsConsumedOnceAndHistoryDoesNotCreateAnotherAlarm() {
        val vm = TestSupport.vm(); TestSupport.await(vm.send("alarm at 11:59pm"))
        val request = vm.state.value.clockLaunch!!
        assertEquals(request, vm.takeClockLaunch(request.messageId)); assertNull(vm.takeClockLaunch(request.messageId))
        TestSupport.await(vm.clockResult(request))
        assertTrue(vm.state.value.messages.last().text.contains("Check that the alarm is saved and enabled"))
        TestSupport.await(vm.requestClock(request.messageId))
        assertEquals(AlarmClock.ACTION_SHOW_ALARMS, vm.state.value.clockLaunch!!.alarm.intent().action)
        assertNull(TestSupport.vm().state.value.clockLaunch)
        OmniStore(TestSupport.context).use { assertTrue(it.reminders().isEmpty()); assertTrue(it.memories().isEmpty()) }
    }
    @Test fun failureCanBeRetriedWithoutFalseConfirmation() {
        val vm = TestSupport.vm(); TestSupport.await(vm.send("alarm at 11:59pm"))
        val request = vm.takeClockLaunch(vm.state.value.clockLaunch!!.messageId)!!
        TestSupport.await(vm.clockResult(request, "No compatible Clock app is available."))
        assertTrue(vm.state.value.messages.last().text.contains("could not be opened"))
        TestSupport.await(vm.requestClock(request.messageId))
        assertEquals(AlarmClock.ACTION_SET_ALARM, vm.state.value.clockLaunch!!.alarm.intent().action)
    }
    @Test fun staleTimeAndChangedZoneCannotCreateWrongAlarm() {
        val now = ZonedDateTime.parse("2026-09-10T00:46:00+05:30[Asia/Kolkata]")
        val alarm = ClockAlarm(now.withMinute(55).toInstant().toEpochMilli(), zone = now.zone.id)
        assertThrows(IllegalArgumentException::class.java) { alarm.intent(now.plusHours(1)) }
        assertThrows(IllegalArgumentException::class.java) { alarm.intent(now.withZoneSameInstant(java.time.ZoneId.of("UTC"))) }
        assertEquals(alarm, ClockAlarm.decode(alarm.encode()))
        assertNull(ClockAlarm.decode("clock-alarm-v1:{}"))
        assertNull(ClockAlarm.decode("unrelated-action"))
    }
    @Test fun cancellingQuestionLeavesNoSavedItemOrAlarm() {
        val vm = TestSupport.vm(); TestSupport.await(vm.send("set an alarm")); TestSupport.await(vm.send("cancel"))
        assertNull(vm.state.value.clockLaunch); assertTrue(vm.state.value.memories.isEmpty()); assertTrue(vm.state.value.reminders.isEmpty())
        assertEquals("Alarm request cancelled. No alarm was created.", vm.state.value.messages.last().text)
    }
    @Test fun reminderRequestsStillUseOmniNotificationsAndManageAlarmsOnlyOpensClock() {
        val vm = TestSupport.vm(); TestSupport.await(vm.send("Remind me to call Sujatha in 20 minutes"))
        assertEquals(1, vm.state.value.reminders.size); assertNull(vm.state.value.clockLaunch)
        TestSupport.await(vm.send("delete alarms"))
        assertEquals(1, vm.state.value.reminders.size)
        assertEquals(AlarmClock.ACTION_SHOW_ALARMS, vm.state.value.clockLaunch!!.alarm.intent().action)
    }
}
