package com.gyftalala.omni

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class NavigationCommandUiTest {
    @get:Rule val compose = createAndroidComposeRule<VerificationActivity>()
    @Before fun reset() = TestSupport.reset()
    private val messages get() = (0..70).map { ChatMessage("m$it", Role.USER, "History message $it", it.toLong()) }

    @Test fun tabSwitchRestoresExactChatOffsetWithoutScrollingToNewest() {
        val vm = TestSupport.vm()
        compose.setContent { OmniTheme { OmniApp(OmniState(messages = messages, ready = true), vm, null, {}, { _, _, _ -> }, {}, {}, {}, {}) } }
        compose.onNodeWithText("History message 70").assertIsDisplayed()
        compose.onNodeWithTag("chat-messages").performScrollToIndex(10)
        val before = compose.onNodeWithTag("chat-message-m10").fetchSemanticsNode().boundsInRoot.top
        val nav = compose.onNodeWithContentDescription("Chat").fetchSemanticsNode().boundsInRoot
        repeat(3) {
            compose.onNodeWithContentDescription("Library").performClick()
            compose.onNodeWithContentDescription("Chat").performClick()
            compose.onNodeWithText("History message 10").assertIsDisplayed()
            assertEquals(before, compose.onNodeWithTag("chat-message-m10").fetchSemanticsNode().boundsInRoot.top, 1f)
            assertEquals(nav, compose.onNodeWithContentDescription("Chat").fetchSemanticsNode().boundsInRoot)
        }
        screenshot("tab-restored-chat.png")
    }

    @Test fun newMessagesStillAppearAfterTabReturn() {
        val vm = TestSupport.vm()
        var history by mutableStateOf(messages)
        compose.setContent { OmniTheme { OmniApp(OmniState(messages = history, ready = true), vm, null, {}, { _, _, _ -> }, {}, {}, {}, {}) } }
        compose.onNodeWithTag("chat-messages").performScrollToIndex(8)
        compose.onNodeWithContentDescription("Library").performClick()
        compose.runOnIdle { history = history + ChatMessage("new", Role.ASSISTANT, "New incoming reminder", 100L) }
        compose.onNodeWithContentDescription("Chat").performClick()
        compose.onNodeWithText("New incoming reminder").assertIsDisplayed()
        repeat(8) {
            compose.onNodeWithContentDescription("Library").performClick()
            compose.onNodeWithContentDescription("Chat").performClick()
            compose.onNodeWithText("New incoming reminder").assertIsDisplayed()
        }
        screenshot("new-message-tab-return.png")
        compose.onNodeWithText("New incoming reminder").assertIsDisplayed()
    }

    @Test fun librarySearchAndListPositionSurviveTabSwitch() {
        val vm = TestSupport.vm()
        val memories = (0..50).map { Memory("note$it", "Saved note $it", "", Category.NOTE, it.toLong()) }
        lateinit var focus: FocusManager
        compose.setContent { OmniTheme {
            focus = LocalFocusManager.current
            OmniApp(OmniState(memories = memories, messages = messages, ready = true), vm, null, {}, { _, _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Saved note")
        compose.runOnIdle { focus.clearFocus() }
        compose.onNodeWithTag("library-items").performScrollToIndex(20)
        val target = compose.onNodeWithText("Saved note 30")
        val before = target.fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithContentDescription("Chat").performClick()
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Saved note")
        target.assertIsDisplayed()
        assertEquals(before, target.fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun deletionDialogRequiresSelectionAndSupportsKeepThenDelete() {
        val vm = TestSupport.vm()
        TestSupport.await(vm.send("Call Suresh")); TestSupport.await(vm.send("Do puja"))
        val target = vm.state.value.memories.first { it.title == "Call Suresh" }
        compose.setContent { OmniTheme {
            val state by vm.state.collectAsState()
            OmniApp(state, vm, null, {}, { _, _, _ -> }, {}, {}, {}, {})
        } }
        TestSupport.await(vm.send("delete reminders"))
        compose.onNodeWithText("Delete reminders?").assertIsDisplayed()
        compose.onNodeWithText("Delete 0 reminders").assertIsNotEnabled()
        compose.onNodeWithText("Keep reminders").performClick()
        compose.waitUntil { vm.state.value.reminderDeletion == null && !vm.state.value.busy }
        assertEquals(2, vm.state.value.memories.size)
        TestSupport.await(vm.send("delete reminders"))
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("Delete 2 reminders").assertIsEnabled()
        compose.onNodeWithText("Clear selection").performClick()
        compose.onNodeWithTag("delete-reminder-${target.id}").performClick()
        screenshot("reminder-delete-selection.png", dialog = true)
        compose.onNodeWithText("Delete 1 reminder").performClick()
        compose.waitUntil { vm.state.value.reminderDeletion == null && !vm.state.value.busy }
        assertEquals("Do puja", vm.state.value.memories.single().title)
        compose.onNodeWithText("Deleted 1 reminder.").assertIsDisplayed()
    }

    private fun screenshot(name: String, dialog: Boolean = false) {
        val bitmap = (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap()
        File(TestSupport.context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
