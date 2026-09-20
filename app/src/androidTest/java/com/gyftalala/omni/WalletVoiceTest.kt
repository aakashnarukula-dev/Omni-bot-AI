package com.gyftalala.omni

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.ai.*
import com.gyftalala.omni.data.*
import com.gyftalala.omni.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WalletVoiceTest {
    @get:Rule val compose = createAndroidComposeRule<VerificationActivity>()
    @Before fun reset() = TestSupport.reset()

    @Test fun identityOcrOriginalsStayEncryptedAndSearchableAfterReload() = runBlocking {
        val front = TestSupport.image("pan-front.png", listOf("INCOME TAX DEPARTMENT", "Name: TEST OWNER", "ABCDE1234F", "DOB: 09/08/1990"))
        val back = TestSupport.image("pan-back.png", listOf("ABCDE1234F", "Synthetic back image"))
        val reader = OcrReader()
        val identity = try { IdentityReader.extract(reader.read(TestSupport.context, front), IdKind.PAN) } finally { reader.close() }
        assertEquals("ABCDE1234F", identity.number)
        val fake = FakeGemini(); val vm = TestSupport.vm(fake.client)
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        TestSupport.await(vm.send("", listOf(front, back), Category.DOCUMENT, reviewedIdentity = identity.copy(name = "EDITED OWNER")))
        assertNull(vm.state.value.error); assertEquals(0, fake.calls)
        val saved = vm.state.value.memories.single()
        saved.files.zip(listOf(front, back)).forEach { (attachment, uri) ->
            val bytes = TestSupport.context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(bytes, vm.vault.read(attachment))
            val encrypted = File(TestSupport.context.noBackupFilesDir, "vault/${attachment.id}").readBytes()
            assertFalse(bytes.contentEquals(encrypted)); assertFalse(encrypted.take(8) == bytes.take(8))
        }
        OmniStore(TestSupport.context).use { assertEquals(saved, it.memories().single()) }
        TestSupport.await(vm.send("Show my PAN"))
        assertEquals(saved.id, vm.state.value.messages.last().attachmentId)
        TestSupport.await(vm.delete(saved.id))
        assertTrue(saved.files.all { !File(TestSupport.context.noBackupFilesDir, "vault/${it.id}").exists() })
    }

    @Test fun galleryIdDetectionNeverCallsGemini() {
        val photo = TestSupport.image("identity.png", listOf("Aadhaar", "2345 6789 0123", "Name: TEST OWNER"))
        val fake = FakeGemini(); val vm = TestSupport.vm(fake.client)
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        TestSupport.await(vm.send("", listOf(photo)))
        assertEquals(0, fake.calls)
        assertEquals(IdKind.AADHAAR, vm.state.value.memories.single().identity?.kind)
        assertEquals(Category.DOCUMENT, vm.state.value.memories.single().category)
    }

    @Test fun photoFinishKeepsHueAndReadableTextAndPersists() {
        val image = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        val palette = try { Canvas(image).drawColor(Color.rgb(30, 160, 190)); PhotoPalette.fromBitmap(image) } finally { image.recycle() }
        assertEquals(3, palette.size)
        assertTrue(Color.blue(palette[0]) > Color.red(palette[0]))
        assertTrue(ColorUtils.calculateContrast(0xFFE7E4DE.toInt(), palette[0]) >= 5.5)
        val memory = Memory("palette", "Colored card", "", Category.CARD, card = CardDetails(), palette = palette)
        OmniStore(TestSupport.context).use { store -> store.save(memory); assertEquals(palette, store.memories().single().palette) }
        assertEquals(palette[0], CardPalette.forMemory(memory).start)
    }

    @Test fun voiceBatchSavesSelectedItemsAndSchedulesOnlyExplicitTime() = runBlocking {
        val vm = TestSupport.vm()
        val drafts = vm.suggestVoice("Call doctor tomorrow at 8 pm and buy a cable and do puja and note an idea")
            .map { if (it.category == Category.NOTE) it.copy(selected = false) else if (it.text.startsWith("Call")) it.copy(priority = TaskPriority.URGENT) else it }
        assertTrue(vm.state.value.memories.isEmpty())
        val result = vm.saveVoice(drafts)
        assertTrue(result.contains("Saved 3 items"))
        assertEquals(3, vm.state.value.memories.size)
        assertEquals(1, vm.state.value.reminders.size)
        assertEquals(1, vm.state.value.memories.count { it.question != null })
        assertEquals(TaskPriority.URGENT, vm.state.value.memories.first { it.title.startsWith("Call") }.priority)
        vm.saveVoice(drafts)
        assertEquals(3, vm.state.value.memories.size)
        val pending = vm.state.value.memories.first { it.question != null }
        TestSupport.await(vm.schedule(pending.id, System.currentTimeMillis() + 3600000))
        TestSupport.await(vm.taskDetails(pending.id, TaskPriority.LOW, TopicTag.HOME))
        OmniStore(TestSupport.context).use { assertEquals(TaskPriority.LOW, it.memories().first { it.id == pending.id }.priority) }
        TestSupport.await(vm.complete(pending.id)); assertTrue(vm.state.value.reminders.first { it.id == pending.id }.completed)
    }

    @Test fun voiceSearchDoesNotCreateNewMemory() = runBlocking {
        val vm = TestSupport.vm(); TestSupport.await(vm.send("USB Type-C cable"))
        vm.saveVoice(vm.suggestVoice("Show my cable"))
        assertEquals(1, vm.state.value.memories.size)
        assertEquals(vm.state.value.memories.single().id, vm.state.value.messages.last().attachmentId)
    }

    @Test fun walletExpandsAndFindsIdsAndDistantCards() {
        val vm = TestSupport.vm()
        val cards = (1..9).map { i -> Memory("c$i", "Bank $i", "", Category.CARD, createdAt = i.toLong(), card = CardDetails("Bank $i", "TEST OWNER", "4111111111111111", "09/29", "123")) }
        val identity = Memory("id", "PAN", "", Category.DOCUMENT, identity = IdentityDetails(IdKind.PAN, "ABCDE1234F", "TEST OWNER"))
        var opened = ""
        compose.setContent { OmniTheme { WalletScreen(cards + identity, ImagePreviews(vm.vault), { opened = it }, {}) } }
        compose.onNodeWithTag("wallet-stack").assertExists()
        screenshot("wallet-stack.png")
        compose.onNodeWithTag("wallet-stack").performTouchInput { swipeDown() }
        compose.onNodeWithText("Collapse cards").assertIsDisplayed()
        compose.onNodeWithTag("wallet-list").performScrollToNode(hasText("BANK 1"))
        compose.onNodeWithText("BANK 1").performClick()
        compose.onNodeWithTag("wallet-details-c1").performClick(); assertEquals("c1", opened)
        compose.onNodeWithText("IDs", useUnmergedTree = true).performClick()
        compose.onNodeWithText("ABCDE1234F").assertIsDisplayed()
        screenshot("wallet-identities.png")
    }

    @Test fun voiceReviewAllowsCorrectionsAndDeselection() {
        val originals = VoiceParser.parse("Call doctor and buy a cable")
        var saved = emptyList<VoiceItem>()
        lateinit var focusManager: FocusManager
        var keyboard: SoftwareKeyboardController? = null
        compose.setContent { OmniTheme {
            focusManager = LocalFocusManager.current
            keyboard = LocalSoftwareKeyboardController.current
            var items by remember { mutableStateOf(originals) }
            VoiceReview(items, false, null, { items = it }, { saved = items.filter { it.selected } })
        } }
        compose.onNodeWithTag("voice-item-${originals[0].id}").performTextReplacement("Call dentist tomorrow at 8 pm")
        // Finish editing before scrolling so TextField's bring-into-view request cannot pull the list back.
        compose.runOnIdle { focusManager.clearFocus(); keyboard?.hide() }
        compose.waitForIdle()
        compose.onNodeWithTag("voice-review-items").performScrollToNode(hasTestTag("voice-select-${originals[1].id}"))
        compose.onNodeWithTag("voice-select-${originals[1].id}").performClick()
        compose.onNodeWithText("Save selected items").performClick()
        assertEquals(1, saved.size)
        assertEquals("Call dentist tomorrow at 8 pm", saved.single().text)
        screenshot("voice-review.png")
    }

    @Test fun legacyEncryptedRecordsLoadWithSafeDefaults() {
        OmniStore(TestSupport.context).use { store ->
            val legacy = org.json.JSONObject().put("id", "legacy").put("title", "Old note").put("text", "Keep this")
                .put("category", "NOTE").put("createdAt", 123L).put("files", org.json.JSONArray())
            store.writableDatabase.insertOrThrow("records", null, android.content.ContentValues().apply {
                put("id", "legacy"); put("kind", "memory"); put("created", 123L)
                put("payload", store.vault.encrypt(legacy.toString().toByteArray(), "memory:legacy"))
            })
            val restored = store.memories().single()
            assertEquals("Keep this", restored.text); assertNull(restored.identity); assertTrue(restored.palette.isEmpty())
            assertEquals(TaskPriority.NORMAL, restored.priority); assertEquals(TopicTag.PERSONAL, restored.tag)
            store.save(restored.copy(priority = TaskPriority.URGENT)); assertEquals("Keep this", store.memories().single().text)
        }
    }

    @Test fun invalidVoiceTimeRejectsWholeBatchBeforeSaving() = runBlocking {
        val vm = TestSupport.vm()
        val items = VoiceParser.parse("Do puja and call doctor").mapIndexed { i, item -> item.copy(at = if (i == 0) System.currentTimeMillis() + 3600000 else 1L) }
        try { vm.saveVoice(items); fail("Expected a future-time error") } catch (_: IllegalArgumentException) { }
        assertTrue(vm.state.value.memories.isEmpty()); assertTrue(vm.state.value.reminders.isEmpty())
    }

    @Test fun librarySearchFindsReminderByPriorityAndTopic() {
        val vm = TestSupport.vm()
        TestSupport.await(vm.send("Call doctor ASAP")); TestSupport.await(vm.send("Do puja"))
        compose.setContent { OmniTheme { val state by vm.state.collectAsState(); OmniApp(state, vm, null, {}, { _, _, _ -> }, {}, {}, {}, {}) } }
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Urgent")
        compose.onNodeWithText("Call doctor ASAP").assertIsDisplayed()
        compose.onNodeWithText("Do puja").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextReplacement("Home")
        compose.onNodeWithText("Do puja").assertIsDisplayed()
        compose.onNodeWithText("Call doctor ASAP").assertDoesNotExist()
        screenshot("reminder-topics.png")
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(TestSupport.context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
