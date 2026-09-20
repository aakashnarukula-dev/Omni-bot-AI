package com.gyftalala.omni

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.backup.*
import com.gyftalala.omni.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupUiTest {
    @get:Rule val compose = createAndroidComposeRule<VerificationActivity>()
    @Before fun reset() = TestSupport.reset()
    @Test fun passwordMustMatchAndLossAcknowledgementRequired() {
        var received: CharArray? = null
        compose.setContent { OmniTheme { BackupScreen(BackupState(BackupStep.CREATE_PASSWORD), {}, {}, { received = it }, {}, {}, {}, {}) } }
        compose.onNodeWithTag("submit-backup-password").assertIsNotEnabled()
        compose.onNodeWithTag("backup-password").performTextInput("long synthetic password")
        compose.onNodeWithTag("backup-confirm-password").performTextInput("wrong synthetic password")
        compose.onNodeWithTag("backup-password-acknowledge").performScrollTo().performClick()
        compose.onNodeWithTag("submit-backup-password").assertIsNotEnabled()
        compose.onNodeWithTag("backup-confirm-password").performTextReplacement("long synthetic password")
        compose.onNodeWithTag("submit-backup-password").performScrollTo().performClick()
        assertEquals("long synthetic password", received?.concatToString())
        compose.onNodeWithTag("backup-password").assertTextContains("")
        received?.fill('\u0000')
    }
    @Test fun settingsHasSingleBackupEntryAndHomeHasClearActions() {
        var open by mutableStateOf(false); val vm = TestSupport.vm()
        compose.setContent { OmniTheme {
            if (!open) SettingsScreen(OmniState(ready = true), {}, vm, {}, {}, { open = true })
            else BackupScreen(BackupState(), {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Backup & restore").performScrollTo().performClick()
        compose.onNodeWithTag("create-backup").assertIsDisplayed()
        compose.onNodeWithTag("choose-backup").assertIsDisplayed()
        compose.onAllNodesWithText("Backup & restore").assertCountEquals(1)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(TestSupport.context.getExternalFilesDir(null), "backup-home.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun previewNeedsExplicitRestoreAndCancelDoesNotRestore() {
        var restores = 0; var cancels = 0
        compose.setContent { OmniTheme { BackupScreen(BackupState(BackupStep.PREVIEW,
            preview = BackupPreview(System.currentTimeMillis(), 8, 14, 5, 2, 1000)), {}, {}, {}, {}, { restores++ }, { cancels++ }, {}) } }
        assertEquals(0, restores)
        compose.onNodeWithTag("confirm-backup-restore").performScrollTo().assertIsDisplayed()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(TestSupport.context.getExternalFilesDir(null), "backup-preview.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(1, cancels); assertEquals(0, restores)
    }
    @Test fun controllerRoundTripWrongPasswordRetryWipesSecretsAndMakesNoAiCalls() {
        val fake = FakeGemini(); val vm = TestSupport.vm(fake.client)
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        TestSupport.await(vm.send("USB Type-C cable"))
        val calls = fake.calls
        val destination = TestSupport.file("controller.omnibak", byteArrayOf())
        compose.runOnIdle { vm.backups.chooseCreate(destination.second) }
        val password = "long synthetic password".toCharArray()
        compose.runOnIdle { vm.backups.create(password) }
        TestSupport.waitUntil(30000) { vm.backups.state.value.step == BackupStep.DONE || vm.backups.state.value.error != null }
        assertNull(vm.backups.state.value.error)
        TestSupport.waitUntil { password.all { it == '\u0000' } }
        assertTrue(destination.first.length() > 100)
        compose.runOnIdle { vm.backups.chooseRestore(destination.second) }
        val wrong = "wrong synthetic password".toCharArray()
        compose.runOnIdle { vm.backups.inspect(wrong) }
        TestSupport.waitUntil(30000) { vm.backups.state.value.error != null }
        assertEquals(BackupStep.RESTORE_PASSWORD, vm.backups.state.value.step)
        TestSupport.waitUntil { wrong.all { it == '\u0000' } }
        compose.runOnIdle { vm.backups.inspect("long synthetic password".toCharArray()) }
        TestSupport.waitUntil(30000) { vm.backups.state.value.step == BackupStep.PREVIEW || vm.backups.state.value.error != null }
        assertNull(vm.backups.state.value.error)
        assertEquals(1, vm.state.value.memories.size)
        compose.runOnIdle { vm.backups.restore() }
        TestSupport.waitUntil(30000) { vm.backups.state.value.step == BackupStep.DONE || vm.backups.state.value.error != null }
        assertNull(vm.backups.state.value.error)
        assertEquals(1, vm.state.value.memories.size)
        assertEquals(calls, fake.calls)
        compose.runOnIdle { vm.backups.reset() }
        TestSupport.waitUntil { !vm.state.value.busy }
    }
}
