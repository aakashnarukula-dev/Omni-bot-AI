package com.gyftalala.omni

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.TestSupport.await
import com.gyftalala.omni.ai.GeminiClient
import com.gyftalala.omni.data.*
import com.gyftalala.omni.reminders.ReminderReceiver
import com.gyftalala.omni.reminders.ReminderScheduler
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class UseCaseTest {
    private val context get() = TestSupport.context
    @Before fun reset() = TestSupport.reset()
    private fun front() = TestSupport.image("card-front.png", listOf("IndusInd debit card", "Visa", "4111 1111 1111 1111", "Valid thru 09/29", "Cardholder: TEST OWNER"))
    private fun back() = TestSupport.image("card-back.png", listOf("IndusInd debit card", "CVV: 123", "Synthetic test card"))
    private fun screenshot() = TestSupport.image("checkout.png", listOf("Amazon shopping", "https://amazon.in/dp/TEST", "Running shoes", "Checkout", "Add to cart"))
    private fun assertOkay(vm: OmniViewModel) { assertNull(vm.state.value.error) }

    @Test fun suppliedTextExamplesBecomeCorrectCategories() {
        val vm = TestSupport.vm()
        val examples = listOf("USB Type-C to Type-C cable" to Category.PRODUCT, "Do puja" to Category.REMINDER,
            "angiogram discharge summary update" to Category.REMINDER, "call Sujatha madam" to Category.REMINDER,
            "https://amazon.in/dp/B012345" to Category.PRODUCT, "https://amzn.to/example" to Category.PRODUCT)
        for ((text, category) in examples) {
            await(vm.send(text)); assertOkay(vm)
            assertEquals(text, category, vm.state.value.memories.first { it.text == text }.category)
        }
        assertEquals(3, vm.state.value.memories.count { it.question == "When should I remind you?" })
    }

    @Test fun pendingReminderDoesNotSwallowNewTaskWithTime() {
        val vm = TestSupport.vm()
        await(vm.send("Do puja"))
        val puja = vm.state.value.memories.single()
        await(vm.send("Call doctor tomorrow at 8 pm"))
        assertOkay(vm)
        assertEquals(2, vm.state.value.memories.size)
        assertEquals("Call doctor tomorrow at 8 pm", vm.state.value.reminders.single().title)
        assertNotNull(vm.state.value.memories.first { it.id == puja.id }.question)
    }

    @Test fun vagueTimeKeepsTaskPendingWithoutInventingAlarm() {
        val vm = TestSupport.vm()
        await(vm.send("call Sujatha madam")); await(vm.send("tomorrow"))
        assertTrue(vm.state.value.reminders.isEmpty())
        assertEquals(1, vm.state.value.memories.size)
        assertTrue(vm.state.value.messages.last().text.contains("clear future date"))
    }

    @Test fun multiplePendingTasksStayIndependent() {
        val vm = TestSupport.vm()
        await(vm.send("Do puja")); await(vm.send("call Sujatha madam")); await(vm.send("in 20 minutes"))
        assertEquals("Call Sujatha madam", vm.state.value.reminders.single().title)
        assertNotNull(vm.state.value.memories.first { it.text == "Do puja" }.question)
    }

    @Test fun genericCardRetrievalDoesNotReturnDocuments() {
        val vm = TestSupport.vm()
        await(vm.send("IndusInd debit card")); await(vm.send("Bank statement September"))
        val cardId = vm.state.value.memories.first { it.category == Category.CARD }.id
        val before = vm.state.value.messages.size
        await(vm.send("show my card"))
        val replies = vm.state.value.messages.drop(before).filter { it.role == Role.ASSISTANT && it.attachmentId != null }
        assertEquals(listOf(cardId), replies.map { it.attachmentId })
    }

    @Test fun cardLookupRespectsBankAndMissingCase() {
        val vm = TestSupport.vm()
        await(vm.send("IndusInd debit card")); await(vm.send("HDFC credit card"))
        val target = vm.state.value.memories.first { it.title.contains("IndusInd") }
        await(vm.send("send me IndusInd debit card"))
        assertEquals(target.id, vm.state.value.messages.last().attachmentId)
        await(vm.send("send me Axis debit card"))
        assertTrue(vm.state.value.messages.last().text.contains("don't have"))
    }

    @Test fun screenshotWithShoppingDomainAsksProductOrDesign() {
        val vm = TestSupport.vm()
        await(vm.send("", listOf(screenshot())))
        assertOkay(vm)
        val memory = vm.state.value.memories.single()
        assertEquals(Category.UNKNOWN, memory.category)
        assertEquals(listOf(Category.UX_DESIGN, Category.PRODUCT), memory.alternatives)
        await(vm.send("UX design"))
        assertEquals(Category.UX_DESIGN, vm.state.value.memories.single().category)
        assertNull(vm.state.value.memories.single().question)
    }

    @Test fun screenshotCanBeSavedAsShoppingAndRetrieved() {
        val vm = TestSupport.vm()
        await(vm.send("", listOf(screenshot())))
        val id = vm.state.value.memories.single().id
        await(vm.categorize(id, Category.PRODUCT))
        await(vm.send("find running shoes"))
        assertEquals(id, vm.state.value.messages.last().attachmentId)
    }

    @Test fun frontAndBackBecomeOneFullCardRecord() {
        val vm = TestSupport.vm()
        await(vm.send("", listOf(front(), back()), Category.CARD))
        assertOkay(vm)
        val memory = vm.state.value.memories.single()
        assertEquals(2, memory.files.size)
        assertEquals("4111111111111111", memory.card!!.number)
        assertEquals("123", memory.card.cvv)
        assertEquals("09/29", memory.card.expiry)
        assertEquals("TEST OWNER", memory.card.holder)
        await(vm.send("send me IndusInd debit card"))
        assertEquals(memory.id, vm.state.value.messages.last().attachmentId)
    }

    @Test fun laterBackPhotoKeepsFrontAndCorrections() {
        val vm = TestSupport.vm()
        await(vm.send("", listOf(front()), Category.CARD))
        var memory = vm.state.value.memories.single()
        await(vm.edit(memory.id, "My IndusInd debit card", memory.card!!.copy(holder = "CORRECTED OWNER")))
        await(vm.send("", listOf(back()), Category.CARD, memory.id))
        memory = vm.state.value.memories.single()
        assertEquals(2, memory.files.size)
        assertEquals("CORRECTED OWNER", memory.card!!.holder)
        assertEquals("4111111111111111", memory.card.number)
        assertEquals("123", memory.card.cvv)
    }

    @Test fun bankPdfOcrAndOriginalRetrievalWork() {
        val (uri, original) = TestSupport.pdf("September-bank.pdf", 6)
        val vm = TestSupport.vm()
        await(vm.send("", listOf(uri)))
        assertOkay(vm)
        val memory = vm.state.value.memories.single()
        assertEquals(Category.DOCUMENT, memory.category)
        assertTrue(memory.ocr.contains("MARKERPAGE5"))
        assertFalse(memory.ocr.contains("MARKERPAGE6"))
        val export = ByteArrayOutputStream()
        vm.vault.export(memory.files.single(), export)
        assertArrayEquals(original, export.toByteArray())
        await(vm.send("send me September bank document"))
        assertEquals(memory.id, vm.state.value.messages.last().attachmentId)
    }

    @Test fun arbitraryBinaryFileRetainsBytes() {
        val bytes = ByteArray(1024 * 256) { (it % 251).toByte() }
        val (_, uri) = TestSupport.file("archive.custom", bytes)
        val vm = TestSupport.vm()
        await(vm.send("", listOf(uri)))
        assertOkay(vm)
        assertEquals(Category.DOCUMENT, vm.state.value.memories.single().category)
        val exported = ByteArrayOutputStream()
        vm.vault.export(vm.state.value.memories.single().files.single(), exported)
        assertArrayEquals(bytes, exported.toByteArray())
    }

    @Test fun corruptedImagePreservesOriginalAndRequestsReview() {
        val bytes = "not a valid image".toByteArray()
        val (_, uri) = TestSupport.file("broken.png", bytes)
        val vm = TestSupport.vm()
        await(vm.send("", listOf(uri)))
        assertOkay(vm)
        val memory = vm.state.value.memories.single()
        assertEquals(Category.UNKNOWN, memory.category)
        assertTrue(vm.state.value.messages.last().text.contains("Could not read"))
        val output = ByteArrayOutputStream(); vm.vault.export(memory.files.single(), output)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun oversizeBatchRollsBackAlreadyImportedFiles() {
        val (_, small) = TestSupport.file("small.txt")
        val (file, large) = TestSupport.file("too-large.bin")
        RandomAccessFile(file, "rw").use { it.setLength(com.gyftalala.omni.security.Vault.MAX_BYTES + 1) }
        val vm = TestSupport.vm()
        await(vm.send("", listOf(small, large)))
        assertTrue(vm.state.value.error.orEmpty().contains("100 MB"))
        assertTrue(vm.state.value.memories.isEmpty())
        assertTrue(File(context.noBackupFilesDir, "vault").listFiles().orEmpty().isEmpty())
    }

    @Test fun tooManyAttachmentsAndBlankInputDoNotCreatePartialRecords() {
        val vm = TestSupport.vm()
        await(vm.send(" \n "))
        assertTrue(vm.state.value.messages.isEmpty())
        val (_, uri) = TestSupport.file("file.txt")
        await(vm.send("", List(11) { uri }))
        assertTrue(vm.state.value.error.orEmpty().contains("10 files"))
        assertTrue(vm.state.value.memories.isEmpty())
    }

    @Test fun reminderRecategorizationCancelsScheduledTask() {
        val vm = TestSupport.vm()
        await(vm.send("Do puja"))
        val id = vm.state.value.memories.single().id
        await(vm.schedule(id, System.currentTimeMillis() + 120000))
        await(vm.categorize(id, Category.NOTE))
        assertTrue(vm.state.value.reminders.isEmpty())
        assertEquals(Category.NOTE, vm.state.value.memories.single().category)
    }

    @Test fun selectingCurrentReminderCategoryKeepsItsSchedule() {
        val vm = TestSupport.vm()
        await(vm.send("Do puja"))
        val id = vm.state.value.memories.single().id
        await(vm.schedule(id, System.currentTimeMillis() + 120000))
        val status = vm.state.value.memories.single().status
        await(vm.categorize(id, Category.REMINDER))
        assertEquals(status, vm.state.value.memories.single().status)
        assertNull(vm.state.value.memories.single().question)
    }

    @Test fun deleteRemovesOriginalsReminderAndRelatedMessages() {
        val (_, uri) = TestSupport.file("task.txt")
        val vm = TestSupport.vm()
        await(vm.send("Do task", listOf(uri), Category.REMINDER))
        val memory = vm.state.value.memories.single()
        await(vm.schedule(memory.id, System.currentTimeMillis() + 120000))
        await(vm.delete(memory.id))
        assertTrue(vm.state.value.memories.isEmpty())
        assertTrue(vm.state.value.reminders.isEmpty())
        assertTrue(vm.state.value.messages.none { it.attachmentId == memory.id })
        assertFalse(File(context.noBackupFilesDir, "vault/${memory.files.single().id}").exists())
    }

    @Test fun pastScheduleDoesNotModifyExistingReminder() {
        val vm = TestSupport.vm()
        await(vm.send("Do puja tomorrow at 8 pm"))
        val previous = vm.state.value.reminders.single()
        await(vm.schedule(previous.id, System.currentTimeMillis() - 1000))
        assertEquals(previous, vm.state.value.reminders.single())
        assertTrue(vm.state.value.error.orEmpty().contains("future"))
    }

    @Test fun interruptedSortingRetainsOriginalAndBecomesReviewable() {
        val memory = Memory("interrupted", "Saved file", "", Category.UNKNOWN, status = "Sorting")
        OmniStore(context).use { it.save(memory) }
        val vm = TestSupport.vm()
        assertEquals(memory.id, vm.state.value.memories.single().id)
        assertEquals("Needs review", vm.state.value.memories.single().status)
        assertNotNull(vm.state.value.memories.single().question)
    }

    @Test fun dailyAlarmAdvancesOneDayAndCompletionStopsIt() {
        val now = System.currentTimeMillis()
        val id = "daily-test"
        val reminder = Reminder(id, "Omni verification daily", now - 1000, repeat = "daily")
        OmniStore(context).use { it.save(Memory(id, reminder.title, "", Category.REMINDER)); it.save(reminder) }
        context.sendBroadcast(Intent(context, ReminderReceiver::class.java).putExtra("id", id))
        TestSupport.waitUntil { OmniStore(context).use { it.reminders().single().triggerAt > now } }
        OmniStore(context).use { store ->
            assertEquals(1, store.messages().size)
            assertFalse(store.reminders().single().delivered)
        }
        val vm = TestSupport.vm()
        await(vm.complete(id))
        assertTrue(vm.state.value.reminders.single().completed)
        assertEquals("Done", vm.state.value.memories.single().status)
    }

    @Test fun duplicateDeliveryIsIdempotent() {
        val id = "duplicate-test"
        OmniStore(context).use { it.save(Reminder(id, "Omni verification", System.currentTimeMillis() - 1000)) }
        val intent = Intent(context, ReminderReceiver::class.java).putExtra("id", id)
        context.sendBroadcast(intent); context.sendBroadcast(intent)
        TestSupport.waitUntil { OmniStore(context).use { it.reminders().single().delivered } }
        Thread.sleep(250)
        OmniStore(context).use { assertEquals(1, it.messages().size) }
    }

    @Test fun cloudFailurePreservesTextAndUsesLocalFallback() {
        val fake = FakeGemini(status = 429)
        val vm = TestSupport.vm(fake.client)
        await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        await(vm.send("My favorite blue color"))
        assertOkay(vm)
        assertEquals(1, fake.calls)
        assertEquals(Category.NOTE, vm.state.value.memories.single().category)
        assertTrue(vm.state.value.messages.last().text.contains("AI is unavailable"))
        assertTrue(fake.lastConnection!!.disconnected)
    }

    @Test fun malformedCloudResponsePreservesImage() {
        val fake = FakeGemini(malformed = true)
        val vm = TestSupport.vm(fake.client)
        await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        val uri = TestSupport.image("design.png", listOf("Design reference", "Figma wireframe", "Sign in"))
        await(vm.send("", listOf(uri)))
        assertEquals(1, fake.calls)
        assertEquals(1, vm.state.value.memories.single().files.size)
        assertTrue(vm.state.value.messages.last().text.contains("AI is unavailable"))
    }

    @Test fun sensitiveCardsNeverReachConfiguredCloud() {
        val fake = FakeGemini()
        val vm = TestSupport.vm(fake.client)
        await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        await(vm.send("", listOf(front(), back()), Category.CARD))
        assertOkay(vm)
        assertEquals(0, fake.calls)
        assertEquals("4111111111111111", vm.state.value.memories.single().card!!.number)
    }

    @Test fun sensitiveBankDocumentNeverReachesConfiguredCloud() {
        val fake = FakeGemini()
        val vm = TestSupport.vm(fake.client)
        await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        await(vm.send("", listOf(TestSupport.pdf("bank.pdf").first)))
        assertEquals(0, fake.calls)
        assertEquals(Category.DOCUMENT, vm.state.value.memories.single().category)
    }

    @Test fun cloudRequestCarriesImageAndKeepsKeyOutOfUrl() {
        val fake = FakeGemini("unknown")
        val vm = TestSupport.vm(fake.client)
        await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        await(vm.send("", listOf(TestSupport.image("page.png", listOf("Checkout", "Shoes", "Add to cart")))))
        assertEquals(1, fake.calls)
        val connection = fake.lastConnection!!
        val request = JSONObject(connection.request.toString("UTF-8"))
        assertFalse(connection.url.toString().contains("synthetic-key"))
        assertEquals("synthetic-key", connection.getRequestProperty("x-goog-api-key"))
        val image = request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getJSONObject("inline_data")
        assertEquals("image/jpeg", image.getString("mime_type"))
        assertTrue(image.getString("data").isNotEmpty())
        assertEquals(Category.UNKNOWN, vm.state.value.memories.single().category)
    }

    @Test fun settingsKeyRemovalDisablesCloudAndSurvivesReopen() {
        val fake = FakeGemini()
        val vm = TestSupport.vm(fake.client)
        await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        assertTrue(vm.state.value.hasKey)
        await(vm.settings(null, "gemini-2.5-flash", true))
        assertTrue(vm.state.value.hasKey)
        await(vm.settings("", "gemini-2.5-flash", false))
        await(vm.send("Another saved thought"))
        assertEquals(0, fake.calls)
        val reopened = TestSupport.vm(fake.client)
        assertFalse(reopened.state.value.hasKey)
        assertFalse(reopened.state.value.cloud)
        assertEquals("gemini-2.5-flash", reopened.state.value.model)
    }
}
