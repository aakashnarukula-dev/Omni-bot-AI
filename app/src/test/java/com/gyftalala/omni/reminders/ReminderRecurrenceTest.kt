package com.gyftalala.omni.reminders

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderRecurrenceTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun instant(value: String) = ZonedDateTime.parse("$value+05:30[Asia/Kolkata]").toInstant().toEpochMilli()

    @Test fun `one time has no next occurrence`() {
        assertNull(ReminderRecurrence.next(instant("2026-09-21T08:00:00"), "none", instant("2026-09-21T09:00:00"), zone))
    }

    @Test fun `daily recurrence catches up without drifting`() {
        assertEquals(instant("2026-09-23T08:00:00"),
            ReminderRecurrence.next(instant("2026-09-21T08:00:00"), "daily", instant("2026-09-22T09:00:00"), zone))
    }

    @Test fun `weekdays skip weekend`() {
        assertEquals(instant("2026-09-28T08:00:00"),
            ReminderRecurrence.next(instant("2026-09-25T08:00:00"), "weekdays", instant("2026-09-25T09:00:00"), zone))
    }

    @Test fun `weekly recurrence preserves weekday and time`() {
        assertEquals(instant("2026-09-28T08:00:00"),
            ReminderRecurrence.next(instant("2026-09-21T08:00:00"), "weekly:MONDAY", instant("2026-09-21T09:00:00"), zone))
    }
}
