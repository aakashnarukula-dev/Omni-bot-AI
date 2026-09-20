package com.gyftalala.omni

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.data.*
import com.gyftalala.omni.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScannerNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<VerificationActivity>()
    @Before fun reset() = TestSupport.reset()

    @Test fun emptyWalletOpensScannerAndFirstSavedCardReturnsToStack() {
        val vm = TestSupport.vm()
        var memories by mutableStateOf(emptyList<Memory>())
        compose.setContent { OmniTheme {
            OmniApp(OmniState(ready = true, memories = memories), vm, null, {}, { _, _, _ -> }, {}, {}, {}, {},
                scanner = { close ->
                    Column { Text("Live scanner"); TextButton(onClick = close) { Text("Close scanner") } }
                })
        } }
        compose.onNodeWithContentDescription("Cards").performClick()
        compose.onNodeWithText("Live scanner").assertIsDisplayed()
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
        compose.onNodeWithText("Close scanner").performClick()
        compose.onNodeWithContentDescription("Chat").assertIsSelected()
        compose.onNodeWithContentDescription("Cards").performClick()
        compose.runOnIdle { memories = listOf(Memory("card", "Test card", "", Category.CARD,
            card = CardDetails("HDFC", "TEST OWNER", "4111111111111111", "09/29"))) }
        compose.onNodeWithText("Live scanner").assertDoesNotExist()
        compose.onNodeWithText("Your wallet").assertIsDisplayed()
        compose.onNodeWithText("ADD CARD").assertIsDisplayed()
        compose.onNodeWithContentDescription("Voice capture").assertDoesNotExist()
    }

    @Test fun manualScannerHidesDockAndBackPreservesChatDraft() {
        val vm = TestSupport.vm()
        var open by mutableStateOf(false)
        compose.setContent { OmniTheme {
            OmniApp(OmniState(ready = true), vm, null, {}, { kind, _, _ -> open = kind == "card_scan" }, {}, {}, {}, {},
                scannerOpen = open, dismissScanner = { open = false }, scanner = { Text("Live scanner") })
        } }
        compose.onNode(hasSetTextAction()).performTextInput("Keep this draft")
        compose.runOnIdle { open = true }
        compose.onNodeWithText("Live scanner").assertIsDisplayed()
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasSetTextAction()).assertTextContains("Keep this draft")
        assertFalse(open)
    }

    @Test fun scannerControlsSitBelowFrameAndAboveFloatingDock() {
        var selected by mutableStateOf("Credit")
        var captured = 0
        compose.setContent { OmniTheme {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { chrome(selected, { selected = it }, { captured++ }) }
                FloatingNavigation("Cards", {})
            }
        } }
        val frame = compose.onNodeWithTag("scan-frame").fetchSemanticsNode().boundsInRoot
        val types = compose.onNodeWithTag("scan-types").fetchSemanticsNode().boundsInRoot
        val shutter = compose.onNodeWithTag("scan-shutter").fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("floating-navigation").fetchSemanticsNode().boundsInRoot
        assertEquals(1.586f, frame.width / frame.height, .02f)
        assertTrue(frame.bottom < types.top)
        assertTrue(types.bottom < shutter.top)
        assertTrue(shutter.bottom < dock.top)
        assertEquals(shutter.width, shutter.height, 1f)
        assertEquals(frame.center.x, shutter.center.x, 1f)
        compose.onNodeWithText("AADHAAR").performClick()
        assertEquals("Aadhaar", selected)
        compose.onNodeWithContentDescription("Capture front").performClick()
        assertEquals(1, captured)
        File(TestSupport.context.getExternalFilesDir(null), "scanner-dock.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun shortScannerScrollsToShutterWithoutCoveringNavigation() {
        compose.setContent { OmniTheme {
            Column(Modifier.width(320.dp).height(440.dp)) {
                Box(Modifier.weight(1f)) { chrome("Credit", {}, {}) }
                FloatingNavigation("Cards", {})
            }
        } }
        compose.onNodeWithTag("scan-shutter").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Cards").assertIsDisplayed()
        val shutter = compose.onNodeWithTag("scan-shutter").fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("floating-navigation").fetchSemanticsNode().boundsInRoot
        assertTrue(shutter.bottom <= dock.top)
    }

    @Test fun cameraPermissionFailureKeepsScannerExitAvailableWithoutDock() {
        val vm = TestSupport.vm()
        var requested = 0
        var settings = 0
        compose.setContent { OmniTheme {
            OmniApp(OmniState(ready = true), vm, null, {}, { _, _, _ -> }, {}, {}, {}, {}, scanner = { close ->
                CardScannerScreen(false, null, { requested++ }, { settings++ }, close, {}, embedded = true)
            })
        } }
        compose.onNodeWithContentDescription("Cards").performClick()
        compose.waitUntil(5000) { requested == 1 }
        assertEquals(1, requested)
        compose.onNodeWithText("Open camera settings").performClick()
        assertEquals(1, settings)
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close card scanner").performClick()
        compose.onNodeWithText("Open camera settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Chat").assertIsSelected()
    }

    @Test fun libraryAndWalletHaveOneHeaderWithWorkingSettings() {
        val vm = TestSupport.vm()
        val card = Memory("card", "IndusInd debit card", "", Category.CARD, card = CardDetails("IndusInd"))
        compose.setContent { OmniTheme {
            OmniApp(OmniState(ready = true, memories = listOf(card)), vm, null, {}, { _, _, _ -> }, {}, {}, {}, {})
        } }
        for ((tab, title) in listOf("Library" to "Everything you save", "Cards" to "Cards & docs")) {
            compose.onNodeWithContentDescription(tab).performClick()
            compose.onNodeWithText("Omni bot AI").assertIsDisplayed()
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithContentDescription("Omni").performClick()
            compose.onNodeWithText("AI & connection").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back from settings").performClick()
            compose.onNodeWithText(title).assertIsDisplayed()
            File(TestSupport.context.getExternalFilesDir(null), "single-header-${tab.lowercase()}.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Composable private fun chrome(selected: String, select: (String) -> Unit, capture: () -> Unit) {
        ScannerChrome("ALIGN CARD · ${selected.uppercase()}", listOf("Credit", "Debit", "PAN", "Aadhaar", "DL", "Other"),
            selected, select, false, true, false, "Hold steady for auto capture, or tap the shutter", false,
            false, false, {}, capture, {}, preview = { Box(Modifier.fillMaxSize().background(Color.Black)) })
    }
}
