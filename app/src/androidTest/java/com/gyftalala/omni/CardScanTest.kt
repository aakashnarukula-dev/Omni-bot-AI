package com.gyftalala.omni

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.ai.CardScanReader
import com.gyftalala.omni.ai.OcrReader
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
class CardScanTest {
    @get:Rule val compose = createAndroidComposeRule<VerificationActivity>()
    @Before fun reset() = TestSupport.reset()

    @Test fun cameraOcrReviewPersistsBothOriginalsAndCorrectionsWithoutCloud() = runBlocking {
        val front = TestSupport.image("scan-front.png", listOf("IndusInd debit card", "4111 1111 1111 1111", "VALID THRU 09/29", "TEST OWNER", "Visa"))
        val back = TestSupport.image("scan-back.png", listOf("Authorized signature", "123"))
        val ocr = OcrReader()
        val details = try {
            val first = CardScanReader.extract(ocr.read(TestSupport.context, front), false)
            assertEquals("4111111111111111", first.number)
            assertEquals("TEST OWNER", first.holder)
            CardScanReader.extract(ocr.read(TestSupport.context, back), true, first.copy(holder = "CORRECTED OWNER"))
        } finally { ocr.close() }
        assertEquals("123", details.cvv)
        assertEquals("IndusInd", details.issuer)
        val fake = FakeGemini()
        val vm = TestSupport.vm(fake.client)
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        TestSupport.await(vm.send("", listOf(front, back), Category.CARD, reviewedCard = details))
        assertEquals(0, fake.calls)
        val saved = vm.state.value.memories.single()
        assertEquals(details, saved.card)
        assertEquals(2, saved.files.size)
        saved.files.zip(listOf(front, back)).forEach { (file, uri) ->
            val original = TestSupport.context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val output = java.io.ByteArrayOutputStream()
            vm.vault.export(file, output)
            assertArrayEquals(original, output.toByteArray())
        }
        TestSupport.await(vm.send("send me IndusInd debit card"))
        assertEquals(saved.id, vm.state.value.messages.last().attachmentId)
    }

    @Test fun reviewKeepsFullFieldsEditableAndRequiresExplicitSave() {
        var saved: CardDetails? = null
        var backScans = 0
        var retakes = 0
        compose.setContent { OmniTheme {
            var card by remember { mutableStateOf(CardDetails("IndusInd", "TEST OWNER", "4111111111111111", "09/29", "123")) }
            CardScanReview(card, false, null, { card = it }, { retakes++ }, { backScans++ }, { saved = card })
        } }
        compose.onNodeWithText("4111 1111 1111 1111").assertIsDisplayed()
        assertNull(saved)
        compose.onNodeWithText("Scan back").performScrollTo().performClick()
        assertEquals(1, backScans)
        compose.onNodeWithText("Retake").performScrollTo().performClick()
        assertEquals(1, retakes)
        compose.onNodeWithText("Cardholder").performScrollTo().performTextReplacement("EDITED OWNER")
        compose.onNodeWithText("Save card").performClick()
        assertEquals("EDITED OWNER", saved!!.holder)
        assertEquals("4111111111111111", saved!!.number)
        assertEquals("123", saved!!.cvv)
    }

    @Test fun composerButtonsStayCenteredForSingleAndMultipleLines() {
        val vm = TestSupport.vm()
        compose.setContent { OmniTheme {
            val state by vm.state.collectAsState()
            OmniApp(state, vm, null, {}, { _, _, _ -> }, {}, {}, {}, {})
        } }
        fun centered() {
            val add = compose.onNodeWithContentDescription("Attach").fetchSemanticsNode().boundsInRoot
            val send = compose.onNode(hasContentDescription("Send message") or hasContentDescription("Dictate message")).fetchSemanticsNode().boundsInRoot
            val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
            assertEquals(field.center.y, add.center.y, 1f)
            assertEquals(send.center.y, add.center.y, 1f)
        }
        centered()
        compose.onNode(hasSetTextAction()).performTextInput("Line one\nLine two\nLine three")
        centered()
        compose.onNode(hasSetTextAction()).performTextClearance()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(TestSupport.context.getExternalFilesDir(null), "composer-circle.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
