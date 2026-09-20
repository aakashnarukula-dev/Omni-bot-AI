package com.gyftalala.omni

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.ui.*
import com.gyftalala.omni.voice.SpeechInput
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatDictationTest {
    @get:Rule val compose = createAndroidComposeRule<VerificationActivity>()
    @Before fun reset() { TestSupport.reset(); TestSupport.shell("pm grant ${TestSupport.context.packageName} android.permission.RECORD_AUDIO") }
    private class FakeSpeech(override val onDevice: Boolean = true) : SpeechInput {
        var transcript: (String) -> Unit = {}
        var done: (String?) -> Unit = {}
        var starts = 0; var cancels = 0
        override fun start(language: String, transcript: (String) -> Unit, amplitude: (Float) -> Unit, done: (String?) -> Unit) {
            starts++; this.transcript = transcript; this.done = done
        }
        override fun cancel() { cancels++ }
        override fun stop() { done(null) }
    }
    @Test fun micChangesToSendAsSpeechArrivesAndNeverSendsAutomatically() {
        val speech = FakeSpeech(); var draft by mutableStateOf(""); val sent = mutableListOf<String>()
        compose.setContent { OmniTheme { ChatComposer(draft, { draft = it }, { sent.add(draft); draft = "" }, { _, _, _ -> }, { it(true) }, { speech }) } }
        compose.onNodeWithContentDescription("Dictate message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send message").assertDoesNotExist()
        compose.onNodeWithContentDescription("Dictate message").performClick()
        compose.runOnIdle { speech.transcript("Call Sujatha") }
        compose.onNodeWithTag("chat-draft").assertTextContains("Call Sujatha")
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
        assertTrue(sent.isEmpty())
        compose.runOnIdle { speech.transcript("Call Sujatha madam tomorrow"); speech.done(null) }
        assertTrue(sent.isEmpty())
        compose.onNodeWithContentDescription("Send message").performClick()
        assertEquals(listOf("Call Sujatha madam tomorrow"), sent)
        compose.runOnIdle { speech.transcript("late callback must not return") }
        compose.onNodeWithTag("chat-draft").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithContentDescription("Dictate message").assertIsDisplayed()
    }
    @Test fun typingCancelsSpeechAndLateResultsCannotOverwriteCorrections() {
        val speech = FakeSpeech(); var draft by mutableStateOf("")
        compose.setContent { OmniTheme { ChatComposer(draft, { draft = it }, {}, { _, _, _ -> }, { it(true) }, { speech }) } }
        compose.onNodeWithContentDescription("Dictate message").performClick()
        compose.runOnIdle { speech.transcript("Buy cable") }
        compose.onNodeWithTag("chat-draft").performTextReplacement("Buy USB Type-C cable")
        compose.runOnIdle { speech.transcript("incorrect late result") }
        compose.onNodeWithTag("chat-draft").assertTextContains("Buy USB Type-C cable")
        compose.onNodeWithText("Listening…").assertDoesNotExist()
        compose.onNodeWithTag("chat-draft").performTextClearance()
        compose.onNodeWithContentDescription("Dictate message").assertIsDisplayed()
    }
    @Test fun inactivePagerPageCancelsSpeechAndIgnoresLateCallbacks() {
        val speech = FakeSpeech(); var draft by mutableStateOf(""); var active by mutableStateOf(true)
        compose.setContent { OmniTheme { ChatComposer(draft, { draft = it }, {}, { _, _, _ -> }, { it(true) }, { speech }, active) } }
        compose.onNodeWithContentDescription("Dictate message").performClick()
        compose.runOnIdle { speech.transcript("Order oats"); active = false }
        compose.waitForIdle()
        compose.runOnIdle { speech.transcript("Late callback from previous page") }
        compose.onNodeWithTag("chat-draft").assertTextContains("Order oats")
        compose.onNodeWithText("Listening…").assertDoesNotExist()
    }
    @Test fun onlineFallbackRequiresConsentAndCancellationDoesNotRecord() {
        val speech = FakeSpeech(false); var draft by mutableStateOf("")
        compose.setContent { OmniTheme { ChatComposer(draft, { draft = it }, {}, { _, _, _ -> }, { it(true) }, { speech }) } }
        compose.onNodeWithContentDescription("Dictate message").performClick()
        compose.onNodeWithText("Use Android voice input?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick(); assertEquals(0, speech.starts)
        compose.onNodeWithContentDescription("Dictate message").performClick()
        compose.onNodeWithText("Use voice input").performClick(); assertEquals(1, speech.starts)
    }
    @Test fun leavingComposerCancelsRecordingAndKeepsExistingDraft() {
        val speech = FakeSpeech(); var shown by mutableStateOf(true); var draft by mutableStateOf("")
        compose.setContent { OmniTheme { if (shown) ChatComposer(draft, { draft = it }, {}, { _, _, _ -> }, { it(true) }, { speech }) } }
        compose.onNodeWithContentDescription("Dictate message").performClick()
        compose.runOnIdle { speech.transcript("Do puja"); shown = false }
        compose.waitForIdle()
        compose.runOnIdle { speech.transcript("late private words") }
        assertEquals("Do puja", draft); assertTrue(speech.cancels >= 2)
    }
    @Test fun dockHasThreeDestinationsAndNoSeparateVoicePage() {
        val vm = TestSupport.vm()
        compose.setContent { OmniTheme { OmniApp(OmniState(ready = true), vm, null, {}, { _, _, _ -> }, {}, {}, {}, {}) } }
        compose.onNodeWithContentDescription("Voice capture").assertDoesNotExist()
        compose.onNodeWithContentDescription("Dictate message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithContentDescription("Dictate message").assertDoesNotExist()
        compose.onNodeWithContentDescription("Chat").performClick()
        compose.onNodeWithContentDescription("Dictate message").assertIsDisplayed()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(TestSupport.context.getExternalFilesDir(null), "chat-inline-mic.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
