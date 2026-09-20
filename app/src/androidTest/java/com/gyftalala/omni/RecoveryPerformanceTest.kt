package com.gyftalala.omni

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecoveryPerformanceTest {
    @Test fun legacyEncryptedRecordsRemainReadableAfterUpdate() {
        TestSupport.reset()
        val vault = com.gyftalala.omni.security.Vault(TestSupport.context)
        // Ensure the installation's existing Keystore key exists, then reproduce v0.1.0 bytes.
        vault.encrypt("new record".toByteArray(), "new")
        val key = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("omni.vault.v1", null)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            updateAAD("legacy-record".toByteArray())
        }
        val original = "Existing owner data survives update".toByteArray()
        val legacy = cipher.iv + cipher.doFinal(original)
        assertArrayEquals(original, vault.decrypt(legacy, "legacy-record"))
        assertTrue(runCatching { vault.decrypt(legacy, "wrong-record") }.isFailure)
    }

    @Test fun thousandItemVaultLoadsAndRetrievesExactOriginal() {
        TestSupport.reset()
        val context = TestSupport.context
        val started = android.os.SystemClock.elapsedRealtime()
        OmniStore(context).use { store -> store.transaction {
            repeat(1000) { index -> store.save(Memory("library-$index", "Saved document $index", "Reference material unique$index", Category.DOCUMENT,
                ocr = "Synthetic document content ".repeat(20))) }
        } }
        val written = android.os.SystemClock.elapsedRealtime()
        val vm = TestSupport.vm()
        val loaded = android.os.SystemClock.elapsedRealtime()
        assertEquals(1000, vm.state.value.memories.size)
        TestSupport.await(vm.send("find unique997"))
        val searched = android.os.SystemClock.elapsedRealtime()
        assertEquals("library-997", vm.state.value.messages.last().attachmentId)
        val timings = JSONObject().put("records", 1000).put("writeMs", written - started)
            .put("loadMs", loaded - written).put("retrieveWithPersistenceMs", searched - loaded)
        File(context.getExternalFilesDir(null), "performance.json").writeText(timings.toString(2))
        assertTrue("Vault load exceeded 10 seconds: $timings", loaded - written < 10000)
        assertTrue("Retrieval exceeded 10 seconds: $timings", searched - loaded < 10000)
    }

    @Test fun notificationHasPrivateVisibilityAndSafePublicText() {
        TestSupport.reset()
        val context = TestSupport.context
        val id = "notification-private"
        OmniStore(context).use { it.save(Reminder(id, "Synthetic private task", System.currentTimeMillis() - 1000)) }
        context.sendBroadcast(android.content.Intent(context, com.gyftalala.omni.reminders.ReminderReceiver::class.java).putExtra("id", id))
        TestSupport.waitUntil { context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == id } }
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.tag == id }.notification
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("A personal reminder is ready.", notification.publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertFalse(notification.publicVersion.extras.toString().contains("Synthetic private task"))
    }
}
