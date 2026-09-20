package com.gyftalala.omni

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.ai.GeminiClient
import com.gyftalala.omni.data.*
import com.gyftalala.omni.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    @get:Rule val compose = createAndroidComposeRule<VerificationActivity>()
    @Before fun reset() = TestSupport.reset()
    private fun render(vm: OmniViewModel, selected: String? = null, pick: (String, Category?, String?) -> Unit = { _, _, _ -> }, exported: (Memory) -> Unit = {}, fontScale: Float = 1f,
        openPermission: (PermissionScreen) -> Unit = {}) {
        compose.setContent { OmniTheme {
            val state by vm.state.collectAsState()
            var selection by remember { mutableStateOf(selected) }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                OmniApp(state, vm, selection, { selection = it }, pick, {}, exported, {}, openPermission)
            }
        } }
    }

    @Test fun settingsDiscoverChooseAndProbeModel() {
        val fake = FakeGemini("product")
        val client = GeminiClient { url ->
            if (url.path.endsWith("/models")) FakeConnection(url, 200, """{"models":[{"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent"]}]}""")
            else fake.clientConnection(url)
        }
        val vm = TestSupport.vm(client)
        render(vm)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("API key").performClick()
        compose.onNodeWithText("Gemini API key").performTextInput("synthetic-key")
        compose.onNodeWithText("Save key").performClick()
        compose.waitUntil(10000) { vm.state.value.hasKey && !vm.state.value.busy }
        compose.onNodeWithText("Saved securely on this phone").assertIsDisplayed()
        compose.onNodeWithText("AI model").performClick()
        compose.onNodeWithText("Browse available models").performScrollTo().performClick()
        compose.waitUntil(10000) { vm.state.value.availableModels.size == 1 && !vm.state.value.busy }
        compose.onNodeWithText("Gemini 3.5 Flash-Lite").performScrollTo().performClick()
        compose.onNodeWithText("Use model").performClick()
        compose.waitUntil(10000) { vm.state.value.model == "gemini-3.5-flash-lite" && !vm.state.value.busy }
        compose.onNodeWithText("Check connection").performScrollTo().performClick()
        compose.waitUntil(10000) { vm.state.value.modelCheck?.passed == true && !vm.state.value.busy }
        compose.onNodeWithText("Connection verified").assertIsDisplayed()
        assertEquals("gemini-3.5-flash-lite", vm.state.value.modelCheck?.model)
        assertEquals("gemini-3.5-flash-lite", vm.state.value.model)
        assertFalse(vm.state.value.cloud)
    }

    @Test fun savedKeyCanBeKeptAndRemovedWithoutLosingMemories() {
        val vm = TestSupport.vm()
        TestSupport.await(vm.send("Do puja"))
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        render(vm)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("API key").performClick()
        compose.onNodeWithText("New API key").performTextInput("unsaved-replacement")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("synthetic-key", com.gyftalala.omni.data.OmniStore(TestSupport.context).use { it.settings().getString("key") })
        compose.onNodeWithText("API key").performClick()
        compose.onNodeWithText("Remove API key").performScrollTo().performClick()
        compose.onNodeWithText("Keep key").performClick()
        assertTrue(vm.state.value.hasKey)
        compose.onNodeWithText("Remove API key").performScrollTo().performClick()
        compose.onNodeWithText("Remove key").performClick()
        compose.waitUntil(10000) { !vm.state.value.hasKey && !vm.state.value.busy }
        assertFalse(vm.state.value.cloud)
        assertEquals(1, vm.state.value.memories.size)
        compose.onNodeWithText("Check connection").assertIsNotEnabled()
    }

    @Test fun enableWithoutKeyConnectsAndToggleSavesImmediately() {
        val vm = TestSupport.vm()
        render(vm)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("gemini-toggle").performClick()
        compose.onNodeWithText("Connect Gemini").assertIsDisplayed()
        compose.onNodeWithText("Gemini API key").performTextInput("synthetic-key")
        compose.onNodeWithText("Save & enable AI").performClick()
        compose.waitUntil(10000) { vm.state.value.cloud && !vm.state.value.busy }
        compose.onNodeWithTag("gemini-toggle").assertIsOn().performClick()
        compose.waitUntil(10000) { !vm.state.value.cloud && !vm.state.value.busy }
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("gemini-toggle").assertIsOff()
        assertTrue(TestSupport.vm().state.value.hasKey)
        assertFalse(TestSupport.vm().state.value.cloud)
    }

    @Test fun connectionFailureStaysVisibleAndMainScreenHasNoSetupFields() {
        val vm = TestSupport.vm(FakeGemini(status = 429).client)
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", false))
        render(vm)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Check connection").performClick()
        compose.waitUntil(10000) { vm.state.value.modelCheck?.checking == false && !vm.state.value.busy }
        compose.onNodeWithText("Connection needs attention").assertIsDisplayed()
        compose.onNodeWithText("Gemini quota or rate limit reached (429). Check limits in AI Studio.").assertIsDisplayed()
        compose.onNodeWithText("AI model").performClick()
        compose.onNodeWithText("Gemini 3.5 Flash-Lite").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("gemini-3.8-flash", vm.state.value.model)
        compose.onNodeWithText("Connection needs attention").assertIsDisplayed()
    }

    @Test fun settingsLargeTextAndModelChoicesStayReachable() {
        val vm = TestSupport.vm(FakeGemini("product").client)
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        render(vm, fontScale = 1.5f)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("AI model").performScrollTo().performClick()
        compose.onNodeWithText("Gemini 3.5 Flash-Lite").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Enter model ID").performScrollTo().performClick()
        compose.onNodeWithText("Model ID").performScrollTo().performTextReplacement("../bad-model")
        compose.onNodeWithText("Use model").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Privacy & security").performScrollTo().performClick()
        compose.onNodeWithText("Connection checks").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Lock Omni now").performScrollTo().assertIsDisplayed()
    }

    @Test fun settingsModelDialogDisplaysRecommendedSelection() {
        val vm = TestSupport.vm(FakeGemini("product").client)
        TestSupport.await(vm.send("Do puja"))
        TestSupport.await(vm.send("USB Type-C to Type-C cable"))
        TestSupport.await(vm.send("IndusInd debit card"))
        val card = vm.state.value.memories.first { it.category == Category.CARD }
        TestSupport.await(vm.edit(card.id, card.title, CardDetails("IndusInd", "SYNTHETIC OWNER", "4111111111111111", "09/29", "123")))
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        render(vm)
        for (tab in listOf("Chat", "Library", "Cards")) {
            compose.onNodeWithContentDescription(tab).performClick()
            compose.waitForIdle()
            java.io.File(TestSupport.context.getExternalFilesDir(null), "theme-${tab.lowercase()}.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("gemini-toggle").assertIsOn()
        compose.waitForIdle()
        // Capture this app's window only, excluding unrelated heads-up notifications.
        java.io.File(TestSupport.context.getExternalFilesDir(null), "settings-main.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("AI model").performClick()
        compose.onNodeWithText("Recommended for understanding").assertIsSelected()
        compose.waitForIdle()
        Thread.sleep(500) // Allow the Android dialog window animation to finish before visual review.
        java.io.File(TestSupport.context.getExternalFilesDir(null), "settings-model.png").outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun walletEditExportAndConfirmedDelete() {
        val vm = TestSupport.vm()
        TestSupport.await(vm.send("IndusInd debit card"))
        val id = vm.state.value.memories.single().id
        var exported: Memory? = null
        render(vm, id, exported = { exported = it })
        compose.onNodeWithText("Save card image").performScrollTo().performClick()
        assertEquals(id, exported?.id)
        compose.onNodeWithText("Edit card details").performScrollTo().performClick()
        compose.onNodeWithText("Cardholder").performScrollTo().performTextReplacement("CORRECTED OWNER")
        compose.onNodeWithText("Save details").performScrollTo().performClick()
        compose.waitUntil(10000) { vm.state.value.memories.single().card?.holder == "CORRECTED OWNER" }
        compose.onNodeWithTag("detail-delete").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, vm.state.value.memories.size)
        compose.onNodeWithTag("detail-delete").performClick()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { vm.state.value.memories.isEmpty() }
    }

    @Test fun attachmentChoicesExcludeCards() {
        val vm = TestSupport.vm()
        val choices = mutableListOf<Pair<String, Category?>>()
        render(vm, pick = { kind, category, _ -> choices += kind to category })
        for (label in listOf("Camera", "Photos", "Documents")) {
            compose.onNodeWithContentDescription("Attach").performClick()
            compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performClick()
        }
        compose.onNodeWithContentDescription("Attach").performClick()
        compose.onNode(hasText("Cards") and hasAnyAncestor(isPopup())).assertDoesNotExist()
        assertEquals(listOf("camera" to null, "image" to null, "file" to null), choices)
    }

    @Test fun permissionRowsUseActivityOwnedLaunchesAndKeepSettingsOpen() {
        val vm = TestSupport.vm()
        val destinations = mutableListOf<PermissionScreen>()
        render(vm, openPermission = { destinations += it })
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Notifications").performScrollTo().performClick()
        compose.onNodeWithText("Precise reminders").performScrollTo().performClick()
        assertEquals(listOf(PermissionScreen.NOTIFICATIONS, PermissionScreen.EXACT_ALARMS), destinations)
        compose.onNodeWithContentDescription("Back from settings").assertIsDisplayed()
    }

    @Test fun libraryReminderCompletionUpdatesSavedState() {
        val vm = TestSupport.vm()
        TestSupport.await(vm.send("Do puja in 20 minutes"))
        TestSupport.await(vm.send("Call Sujatha madam"))
        val scheduled = vm.state.value.memories.first { it.question == null }
        render(vm)
        compose.onNodeWithContentDescription("Reminders").assertDoesNotExist()
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithText("Reminder 2").performClick()
        compose.onNodeWithText("Call Sujatha madam").assertIsDisplayed()
        compose.onNodeWithText(scheduled.title).performClick()
        compose.onNodeWithTag("reminder-schedule").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reminder-complete").performClick()
        compose.waitUntil(10000) { vm.state.value.reminders.single().completed }
        compose.onNodeWithTag("reminder-complete").assertIsNotEnabled()
    }

    @Test fun fullCardDetailsRemainPresentAtLargeFontScale() {
        val memory = Memory("large-card", "IndusInd debit card", "", Category.CARD,
            card = CardDetails("IndusInd", "SYNTHETIC OWNER", "4111111111111111", "09/29", "123"))
        compose.setContent { OmniTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) { VirtualCard(memory) }
        } }
        compose.onNodeWithText("4111 1111 1111 1111").assertIsDisplayed()
        compose.onNodeWithText("123").assertIsDisplayed()
        compose.onNodeWithText("SYNTHETIC OWNER").assertIsDisplayed()
    }
}
