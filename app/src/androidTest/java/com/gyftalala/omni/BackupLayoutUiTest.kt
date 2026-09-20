package com.gyftalala.omni

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gyftalala.omni.cloud.*
import com.gyftalala.omni.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class BackupLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<VerificationActivity>()
    @Before fun reset() = TestSupport.reset()

    @Test fun composerStaysAboveFloatingNavigationWithKeyboardAndMultilineDraft() {
        val vm = TestSupport.vm()
        compose.setContent { OmniTheme {
            OmniApp(OmniState(ready = true), vm, null, {}, { _, _, _ -> }, {}, {}, {}, {})
        } }
        fun checkBounds() {
            val composer = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot
            val dock = compose.onNodeWithTag("floating-navigation").fetchSemanticsNode().boundsInRoot
            assertTrue("Composer must finish above dock: $composer / $dock", composer.bottom <= dock.top)
            compose.onNodeWithTag("chat-draft").assertIsDisplayed()
        }
        checkBounds()
        screenshot("chat-composer-clear-of-dock.png")
        compose.onNodeWithTag("chat-draft").performClick().performTextInput("First line\nSecond line\nThird line")
        compose.waitForIdle()
        checkBounds()
    }

    @Test fun backupSummaryKeepsSingleActionAndShowsLocalAndCloudResults() {
        val vm = TestSupport.vm()
        var state by mutableStateOf(CloudState(ready = true, configured = true, accountEmail = "test@example.test",
            keyReady = true, enabled = true, hour = 3, localSuccess = 1_789_044_000_000, lastSuccess = 1_789_044_000_000,
            backupBytes = 145600))
        compose.setContent { OmniTheme { CloudBackupScreen(state, vm.cloudBackups, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Back up").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Google account").assertIsDisplayed()
        compose.onNodeWithText("Sign out").assertDoesNotExist()
        compose.onNodeWithText("Save backup file").assertDoesNotExist()
        compose.onNodeWithText("Find newest backup").assertDoesNotExist()
        screenshot("backup-summary.png")
        compose.runOnIdle { state = state.copy(backingUp = true, backupStatus = "Uploading to cloud…") }
        compose.onNodeWithTag("cloud-back-up-now").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Uploading to cloud…").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(backingUp = false, backupStatus = "Backup saved on phone and in cloud.") }
        compose.onNodeWithTag("cloud-back-up-now").assertIsEnabled()
        compose.onNodeWithText("Backup saved on phone and in cloud.").assertIsDisplayed()
        screenshot("backup-complete.png")
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(TestSupport.context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
