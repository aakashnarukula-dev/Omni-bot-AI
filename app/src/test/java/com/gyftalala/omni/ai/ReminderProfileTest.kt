package com.gyftalala.omni.ai

import com.gyftalala.omni.data.ReminderKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderProfileTest {
    @Test fun `recognizes reminder types from chat`() {
        assertEquals(ReminderKind.MEDICINE, ReminderProfile.kind("Remind me to take my tablet at 8 pm"))
        assertEquals(ReminderKind.APPOINTMENT, ReminderProfile.kind("Doctor appointment tomorrow"))
        assertEquals(ReminderKind.PAYMENT, ReminderProfile.kind("Pay electricity bill"))
        assertEquals(ReminderKind.EXERCISE, ReminderProfile.kind("Go to the gym"))
        assertEquals(ReminderKind.MEAL, ReminderProfile.kind("Have lunch"))
        assertEquals(ReminderKind.WAKE_UP, ReminderProfile.kind("Wake me up"))
        assertEquals(ReminderKind.TASK, ReminderProfile.kind("Send the report"))
    }
}
