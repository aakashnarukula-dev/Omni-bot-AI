package com.gyftalala.omni.ai

import org.junit.Assert.*
import org.junit.Test

class ReminderCommandTest {
    @Test fun broadRequestsSelectFromSavedReminders() {
        listOf("delete reminders", "Remove all my reminders.", "clear the saved reminders", "Could you please cancel my reminders?")
            .forEach { assertEquals(it, "", ReminderCommand.parse(it)?.query) }
    }
    @Test fun namedRequestsKeepTheirTarget() {
        assertEquals("call Sujatha madam", ReminderCommand.parse("delete the reminder to call Sujatha madam")?.query)
        assertEquals("Call Suresh", ReminderCommand.parse("remove my Call Suresh reminder")?.query)
        assertEquals("puja", ReminderCommand.parse("cancel reminder for puja")?.query)
    }
    @Test fun embeddedTasksAreNotDeletionCommands() {
        listOf("Remind me to delete reminders", "Do puja", "Call Sujatha madam", "Don't delete reminders",
            "How do I delete reminders?", "Save a note: delete reminders", "Cancel my appointment tomorrow")
            .forEach { assertNull(it, ReminderCommand.parse(it)) }
    }
    @Test fun unsupportedQualifiersDoNotBecomeDeleteAll() {
        assertEquals("except puja", ReminderCommand.parse("delete all reminders except puja")?.query)
        assertEquals("completed", ReminderCommand.parse("delete completed reminders")?.query)
    }
    @Test fun voiceSplitsAnExplicitDeletionFromNewTasks() {
        assertEquals(listOf("Do puja", "delete reminders"), VoiceParser.split("Do puja and delete reminders"))
    }
}
