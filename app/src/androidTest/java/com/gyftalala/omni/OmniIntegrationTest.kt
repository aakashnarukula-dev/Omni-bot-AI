package com.gyftalala.omni

import android.app.Application
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.data.*
import com.gyftalala.omni.reminders.ReminderReceiver
import com.gyftalala.omni.reminders.ReminderScheduler
import com.gyftalala.omni.security.Vault
import com.gyftalala.omni.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Runs in the isolated verification application, never in the owner's vault. */
@RunWith(AndroidJUnit4::class)
class OmniIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<VerificationActivity>()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun reset() { TestSupport.reset() }

    @Test fun encryptionRoundTripAndTamperRejection() {
        val vault = Vault(context)
        val secret = "Owner card 4111111111111111 CVV 123".toByteArray()
        val encrypted = vault.encrypt(secret, "test:one")
        assertFalse(String(encrypted).contains("4111111111111111"))
        assertArrayEquals(secret, vault.decrypt(encrypted, "test:one"))
        val tampered = encrypted.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        assertTrue(runCatching { vault.decrypt(tampered, "test:one") }.isFailure)
        assertTrue(runCatching { vault.decrypt(encrypted, "test:another") }.isFailure)
    }

    @Test fun persistenceKeepsFullDetailsButNoPlaintext() {
        val original = Memory("test-card", "IndusInd debit card", "My private card", Category.CARD,
            card = CardDetails("IndusInd", "TEST OWNER", "4111111111111111", "09/29", "123"))
        OmniStore(context).use { it.save(original) }
        OmniStore(context).use { assertEquals(original, it.memories().single()) }
        SQLiteDatabase.openDatabase(context.getDatabasePath("omni.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT payload FROM records", null).use { cursor ->
                cursor.moveToFirst()
                val bytes = String(cursor.getBlob(0))
                assertFalse(bytes.contains("IndusInd")); assertFalse(bytes.contains("4111111111111111"))
            }
        }
    }

    @Test fun textReminderRetrievalAndCategoryFlow() {
        val vm = OmniViewModel(context.applicationContext as Application)
        runBlocking { vm.refresh().join() }
        compose.setContent { OmniTheme {
            val state by vm.state.collectAsState()
            OmniApp(state, vm, null, {}, { _, _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Send it. I'll remember.").performTextInput("USB Type-C to Type-C cable")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.waitUntil(10000) { vm.state.value.memories.any { it.category == Category.PRODUCT } && !vm.state.value.busy }
        runBlocking { vm.send("Do puja").join() }
        assertTrue(vm.state.value.memories.first { it.category == Category.REMINDER }.question != null)
        runBlocking { vm.send("tomorrow at 8 pm").join() }
        assertEquals(1, vm.state.value.reminders.size)
        runBlocking { vm.send("send me USB Type-C cable").join() }
        assertTrue(vm.state.value.messages.any { it.role == Role.ASSISTANT && it.text.startsWith("Found 1") })
        runBlocking { vm.send("send me IndusInd debit card").join() }
        assertTrue(vm.state.value.messages.last().text.contains("don't have"))
        compose.waitForIdle()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.getExternalFilesDir(null), "test-chat.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithContentDescription("Library").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Everything, in place.").assertIsDisplayed()
        compose.onNodeWithText("Search names, words or contents").performTextInput("cable")
        compose.onNodeWithText("USB Type-C to Type-C cable").assertIsDisplayed()
        vm.state.value.reminders.forEach { ReminderScheduler(context).cancel(it.id) }
    }

    @Test fun cardVisibleAndExportableWithoutMasking() {
        val memory = Memory("card", "IndusInd debit card", "", Category.CARD,
            card = CardDetails("IndusInd", "TEST OWNER", "4111111111111111", "09/29", "123", network = "Visa"))
        compose.setContent { OmniTheme { VirtualCard(memory) } }
        compose.onNodeWithText("4111 1111 1111 1111").assertIsDisplayed()
        compose.onNodeWithText("123").assertIsDisplayed()
        val image = ByteArrayOutputStream()
        CardRenderer.render(memory, image)
        assertTrue(image.size() > 1000)
        File(context.getExternalFilesDir(null), "test-card.png").writeBytes(image.toByteArray())
    }

    @Test fun attachmentRoundTripAndApkCategorization() {
        val file = File(context.cacheDir, "captures/sample.apk").apply { parentFile?.mkdirs(); writeBytes(ByteArray(4096) { (it % 255).toByte() }) }
        val vm = OmniViewModel(context.applicationContext as Application)
        runBlocking { vm.refresh().join(); vm.send("", listOf(FileProvider.getUriForFile(context, "${context.packageName}.files", file))).join() }
        assertNull(vm.state.value.error)
        val memory = vm.state.value.memories.single()
        assertEquals(Category.APK, memory.category)
        val bytes = ByteArrayOutputStream()
        vm.vault.export(memory.files.single(), bytes)
        assertArrayEquals(file.readBytes(), bytes.toByteArray())
    }

    @Test fun scheduledAlarmDeliversOnceIntoChat() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow").close()
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val id = "alarm-test"
        val reminder = Reminder(id, "Do puja", System.currentTimeMillis() + 2500)
        OmniStore(context).use { store ->
            store.save(Memory(id, "Do puja", "Do puja", Category.REMINDER))
            store.save(reminder)
        }
        assertTrue(ReminderScheduler(context).schedule(reminder))
        compose.waitUntil(12000) { OmniStore(context).use { store -> store.messages().any { it.id.startsWith("delivery:") } } }
        OmniStore(context).use { store ->
            assertEquals(1, store.messages().count { it.text == "Reminder: Do puja" })
            assertTrue(store.reminders().single().delivered)
        }
        assertTrue(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.tag == id })
        context.getSystemService(android.app.NotificationManager::class.java).cancel(id, 0)
        ReminderScheduler(context).cancel(id)
    }

    @Test fun shareIntentsAcceptStreamsAndClipDataWithoutDuplicatingFiles() {
        val uri = Uri.parse("content://example/images/1")
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, android.text.SpannableString("Card photo"))
        intent.clipData = android.content.ClipData.newRawUri("image", uri)
        assertEquals(listOf(uri), IncomingShare.parse(intent)!!.files)
        assertEquals("Card photo", IncomingShare.parse(intent)!!.text)
        intent.removeExtra(Intent.EXTRA_STREAM)
        assertEquals(listOf(uri), IncomingShare.parse(intent)!!.files)
        assertNull(IncomingShare.parse(Intent(Intent.ACTION_MAIN)))
    }
}
