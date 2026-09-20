package com.gyftalala.omni

import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gyftalala.omni.backup.BackupPreview
import com.gyftalala.omni.cloud.*
import com.gyftalala.omni.data.OmniStore
import com.gyftalala.omni.ui.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class GoogleBackupUiTest {
    @get:Rule val compose = createAndroidComposeRule<VerificationActivity>()
    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(TestSupport.context.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
    }
    @Before fun guard() = TestSupport.requireIsolated()
    @Test fun welcomeHasOneGoogleActionAndNoPhoneOrPassword() {
        var clicked = false
        compose.setContent { OmniTheme { Surface { GoogleWelcomeScreen({ clicked = true }) } } }
        compose.onNodeWithTag("welcome-google-sign-in").assertIsDisplayed().performClick()
        assertTrue(clicked)
        capture("google-welcome.png")
        compose.onNodeWithText("Phone number").assertDoesNotExist()
        compose.onNodeWithText("Recovery password").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }
    @Test fun restorePreviewShowsSourceCountsAndSkipWithoutPassword() {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val store = OmniStore(TestSupport.context)
        val controller = CloudBackupController(TestSupport.context,store,scope) { }
        try {
            compose.setContent { OmniTheme { CloudBackupScreen(
                CloudState(step=CloudStep.AVAILABLE,ready=true,onboarding=true,accountEmail="test@example.test",
                    preview=BackupPreview(1700000000000,2,3,1,0,123),source="Cloud"),controller,{},{},{},{},{}) } }
            compose.onNodeWithTag("confirm-cloud-restore").assertIsDisplayed()
            compose.onNodeWithText("Skip restore").assertIsDisplayed()
            compose.onNodeWithText("Cloud").assertIsDisplayed()
            compose.onNodeWithText("test@example.test").assertIsDisplayed()
            capture("google-restore.png")
            compose.onNodeWithText("Recovery password").assertDoesNotExist()
        } finally { scope.cancel(); store.close() }
    }
}
