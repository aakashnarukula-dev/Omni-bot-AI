package com.gyftalala.omni

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.data.*
import com.gyftalala.omni.reminders.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReminderDeletionTest {
    @Before fun reset() = TestSupport.reset()

    @Test fun deletionIsLocalAndCancelKeepsScheduledAndUnscheduledReminders() {
        val fake = FakeGemini("reminder"); val vm = TestSupport.vm(fake.client)
        TestSupport.await(vm.send("Call Suresh")); val id = vm.state.value.memories.single().id
        TestSupport.await(vm.schedule(id, System.currentTimeMillis() + 3600000))
        TestSupport.await(vm.send("Do puja"))
        val before = vm.state.value.memories
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        TestSupport.await(vm.send("delete reminders"))
        assertEquals(before, vm.state.value.memories); assertEquals(0, fake.calls)
        val request = vm.state.value.reminderDeletion!!
        assertEquals(before.map { it.id }.toSet(), request.candidates.map { it.id }.toSet())
        TestSupport.await(vm.cancelReminderDeletion(request.id))
        assertNull(vm.state.value.reminderDeletion); assertEquals(before, vm.state.value.memories)
        assertEquals(1, vm.state.value.reminders.size)
    }

    @Test fun selectedDeletionCancelsAlarmNotificationAndRemovesEncryptedOriginalAndLinkedMessages() {
        val vm = TestSupport.vm()
        val file = TestSupport.file("call-details.txt").second
        TestSupport.await(vm.send("Call Suresh", listOf(file), Category.REMINDER))
        val target = vm.state.value.memories.single()
        TestSupport.await(vm.schedule(target.id, System.currentTimeMillis() + 3600000))
        val notifications = TestSupport.context.getSystemService(NotificationManager::class.java)
        notifications.notify(target.id, 0, NotificationCompat.Builder(TestSupport.context, ReminderScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_omni).setContentTitle("Synthetic reminder").build())
        TestSupport.await(vm.send("Do puja")); TestSupport.await(vm.send("USB cable"))
        TestSupport.await(vm.send("delete reminders"))
        val request = vm.state.value.reminderDeletion!!
        TestSupport.await(vm.confirmReminderDeletion(request.id, setOf(target.id)))
        assertNull(vm.state.value.error); assertNull(vm.state.value.reminderDeletion)
        assertEquals(2, vm.state.value.memories.size)
        assertTrue(vm.state.value.memories.none { it.id == target.id })
        assertTrue(vm.state.value.messages.none { it.attachmentId == target.id })
        assertTrue(vm.state.value.reminders.isEmpty())
        assertTrue(target.files.all { !File(TestSupport.context.noBackupFilesDir, "vault/${it.id}").exists() })
        val alarm = PendingIntent.getBroadcast(TestSupport.context, 0,
            Intent(TestSupport.context, ReminderReceiver::class.java).setData(Uri.parse("omni://reminder/${target.id}")),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNull(alarm)
        TestSupport.waitUntil { notifications.activeNotifications.none { it.tag == target.id } }
        TestSupport.await(vm.confirmReminderDeletion(request.id, setOf(target.id)))
        assertEquals(2, vm.state.value.memories.size)
        assertEquals(1, vm.state.value.messages.count { it.text == "Deleted 1 reminder." })
        OmniStore(TestSupport.context).use { assertEquals(2, it.memories().size); assertTrue(it.reminders().isEmpty()) }
    }

    @Test fun namedAndMissingRequestsNeverCreateMemoriesButExplicitTaskStillDoes() {
        val vm = TestSupport.vm()
        TestSupport.await(vm.send("Call Suresh")); TestSupport.await(vm.send("Do puja"))
        TestSupport.await(vm.send("Delete reminder for Suresh"))
        assertEquals("Call Suresh", vm.state.value.reminderDeletion!!.candidates.single().title)
        TestSupport.await(vm.cancelReminderDeletion(vm.state.value.reminderDeletion!!.id))
        TestSupport.await(vm.send("delete reminder for someone missing"))
        assertNull(vm.state.value.reminderDeletion); assertEquals(2, vm.state.value.memories.size)
        assertEquals("No matching reminders found. Nothing deleted.", vm.state.value.messages.last().text)
        TestSupport.await(vm.send("Remind me to delete reminders tomorrow at 8 pm"))
        assertEquals(3, vm.state.value.memories.size); assertEquals(1, vm.state.value.reminders.size)
    }

    @Test fun staleChangedAndOutOfScopeSelectionsCannotDeleteAnything() {
        val vm = TestSupport.vm(); TestSupport.await(vm.send("Do puja"))
        val target = vm.state.value.memories.single()
        TestSupport.await(vm.send("delete reminders")); val old = vm.state.value.reminderDeletion!!
        TestSupport.await(vm.send("delete reminders")); val current = vm.state.value.reminderDeletion!!
        TestSupport.await(vm.confirmReminderDeletion(old.id, setOf(target.id)))
        assertEquals(current.id, vm.state.value.reminderDeletion?.id)
        TestSupport.await(vm.send("Call doctor")); val added = vm.state.value.memories.first { it.id != target.id }
        TestSupport.await(vm.confirmReminderDeletion(current.id, setOf(target.id, added.id)))
        assertNotNull(vm.state.value.error); assertEquals(2, vm.state.value.memories.size)
        TestSupport.await(vm.edit(target.id, "Puja with family", null))
        TestSupport.await(vm.confirmReminderDeletion(current.id, setOf(target.id)))
        assertNull(vm.state.value.reminderDeletion); assertEquals(2, vm.state.value.memories.size)
        assertTrue(vm.state.value.messages.last().text.contains("changed"))
    }

    @Test fun voiceDeletionRequiresSeparateConfirmationAndNoCloudCall() = runBlocking {
        val fake = FakeGemini("reminder"); val vm = TestSupport.vm(fake.client)
        TestSupport.await(vm.send("Do puja"))
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        val result = vm.saveVoice(vm.suggestVoice("delete reminders"))
        assertEquals(0, fake.calls); assertEquals(1, vm.state.value.memories.size)
        assertNotNull(vm.state.value.reminderDeletion); assertTrue(result.contains("Review the reminder deletion"))
    }
}
