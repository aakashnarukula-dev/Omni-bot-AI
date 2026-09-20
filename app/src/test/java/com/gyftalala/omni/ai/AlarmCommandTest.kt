package com.gyftalala.omni.ai

import java.time.Instant
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class AlarmCommandTest {
    private val now = ZonedDateTime.parse("2026-09-10T00:46:23+05:30[Asia/Kolkata]")
    private fun command(text: String): AlarmCommand {
        val result = AlarmCommand.parse(text, now); assertNotNull(text, result); return result!!
    }
    private fun target(text: String) = Instant.ofEpochMilli(command(text).time!!.at).atZone(now.zone)
    @Test fun screenshotRequestMeansTodayAfterMidnight() {
        assertEquals(now.withMinute(55).withSecond(0), target("set a alarm for 12:55am"))
    }
    @Test fun amPmNoonMidnightAnd24HourRemainDistinct() {
        assertEquals(12, target("set an alarm for 12:55 pm").hour)
        assertEquals(0, target("alarm at midnight").hour)
        assertEquals(now.dayOfMonth + 1, target("alarm at midnight").dayOfMonth)
        assertEquals(12, target("wake me up at noon").hour)
        assertEquals(23, target("alarm at 23:55").hour)
        assertEquals(7, target("please set me an alarm for 7 a.m.").hour)
    }
    @Test fun missingAmbiguousAndInvalidTimesAskInsteadOfGuessing() {
        listOf("set alarm", "alarm at 7", "alarm tomorrow", "alarm at 25:99", "alarm at 13 pm", "alarm tonight",
            "alarm at 7 am or 8 am", "alarm every week at 8 am", "alarm at 7 am PST").forEach {
            assertNotNull(it, command(it).question); assertNull(it, command(it).time)
        }
    }
    @Test fun cannotSilentlyLoseExplicitFutureDate() {
        assertNull(command("alarm tomorrow at 7 am").time)
        assertNull(command("alarm on 2026-09-15 at 7 am").time)
        assertNotNull(command("alarm tomorrow at 12:30am").time)
    }
    @Test fun relativeMinutesRoundUpToAvoidRingingEarly() {
        assertEquals(now.plusMinutes(6).withSecond(0), target("set an alarm in 5 minutes"))
        assertNull(command("set an alarm in 2 days").time)
    }
    @Test fun dailyAlarmAndMissingDailyTimeKeepRepeat() {
        assertTrue(command("set alarm every day at 7 am").daily)
        assertEquals("daily", command("set alarm every day at 7 am").time!!.repeat)
        assertTrue(command("set alarm every day").daily)
    }
    @Test fun unrelatedTasksProductsAndQuestionsAreNotClockCommands() {
        listOf("Remind me to set an alarm tomorrow at 8 pm", "Buy an alarm clock", "Do puja", "Call Sujatha",
            "How do I set an alarm?", "disable my house alarm", "set alarmingly high price").forEach { assertNull(it, AlarmCommand.parse(it, now)) }
    }
    @Test fun manageCommandsOpenClockWithoutDeletingAnything() {
        listOf("show my alarms", "delete alarms", "open alarms", "cancel all alarms").forEach {
            assertTrue(it, command(it).showClock); assertNull(command(it).time)
        }
    }
    @Test fun dstGapNeverShiftsRequestedClockTime() {
        assertNull(AlarmCommand.parse("alarm at 2:30 am", ZonedDateTime.parse("2026-03-08T00:01:00-05:00[America/New_York]"))!!.time)
    }
}
