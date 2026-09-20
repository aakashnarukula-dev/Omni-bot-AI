package com.gyftalala.omni

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.ui.OmniApp
import com.gyftalala.omni.ui.OmniTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ClockAlarmUiTest {
    @get:Rule val compose = createAndroidComposeRule<VerificationActivity>()
    @Before fun reset() = TestSupport.reset()
    @Test fun sentAlarmHasClockActionInsteadOfReminderReceiptAndHistoryOpensClock() {
        val vm = TestSupport.vm()
        compose.setContent { OmniTheme {
            val state by vm.state.collectAsState()
            OmniApp(state, vm, null, {}, { _, _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("chat-draft").performTextInput("set a alarm for 12:55am")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.waitUntil(5000) { vm.state.value.clockLaunch != null && !vm.state.value.busy }
        compose.onNodeWithText("Set alarm in Clock").assertIsDisplayed()
        compose.onNodeWithText("Choose date & time").assertDoesNotExist()
        val launch = vm.takeClockLaunch(vm.state.value.clockLaunch!!.messageId)!!
        TestSupport.await(vm.clockResult(launch))
        compose.onNodeWithText("Open Clock").assertIsDisplayed().performClick()
        compose.waitUntil { vm.state.value.clockLaunch != null }
        assertTrue(vm.state.value.clockLaunch!!.alarm.opened)
        compose.onNodeWithTag("chat-draft").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        File(TestSupport.context.getExternalFilesDir(null), "clock-alarm-chat.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun questionUsesChatAndKeepsClockAvailableWithoutReminderPicker() {
        val vm = TestSupport.vm(); TestSupport.await(vm.send("set an alarm"))
        compose.setContent { OmniTheme {
            val state by vm.state.collectAsState()
            OmniApp(state, vm, null, {}, { _, _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("What time should the alarm ring?", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Open Clock").assertIsDisplayed()
        compose.onNodeWithText("Choose date & time").assertDoesNotExist()
        assertNull(vm.state.value.clockLaunch)
    }
}
