package com.gyftalala.omni

import android.app.KeyguardManager
import android.content.Context
import android.view.ViewGroup
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import android.util.Base64
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.firebase.auth.GoogleAuthProvider
import com.gyftalala.omni.cloud.CloudServices
import com.gyftalala.omni.cloud.GoogleSignInActivity
import com.gyftalala.omni.data.Category
import com.gyftalala.omni.data.Memory
import com.gyftalala.omni.data.OmniStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Actual MainActivity lifecycle; synthetic Google accounts only in the isolated Auth emulator. */
class StartupAuthenticationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context get() = TestSupport.context
    private val services get() = CloudServices.get(context)

    @Before fun setup() {
        TestSupport.requireIsolated()
        assumeTrue("Run with the disposable Firebase Auth emulator.", BuildConfig.CLOUD_EMULATOR)
        assumeTrue("Use the controlled external picker for lifecycle tests; test real picker separately on a phone.",
            InstrumentationRegistry.getArguments().getString("controlledGoogleSignIn") == "true")
        check(context.getSystemService(KeyguardManager::class.java).isDeviceSecure) { "Test device needs a PIN." }
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked) { "Unlock the test device before running." }
        TestSupport.reset()
        services.auth.signOut()
        context.getSharedPreferences("omni-onboarding", Context.MODE_PRIVATE).edit().putBoolean("permissions_seen", true).commit()
    }

    private fun assertWelcome() {
        assertTrue(device.wait(Until.hasObject(By.text("Sign in with Google")), 5_000))
        device.waitForIdle()
        assertFalse("Fingerprint prompt must not cover sign-in", device.hasObject(By.text("Unlock Omni bot AI")))
        assertFalse(device.hasObject(By.desc("Unlock Omni bot AI")))
        assertFalse(device.hasObject(By.textContains("Authentication cancelled")))
    }

    private fun pickerActivity(): GoogleSignInActivity? {
        var found: GoogleSignInActivity? = null
        instrumentation.runOnMainSync {
            found = listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED).flatMap {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
            }.filterIsInstance<GoogleSignInActivity>().firstOrNull()
        }
        return found
    }

    @Test fun firstOpenRequestsVoiceAndAlertsButLeavesCameraUnrequested() {
        context.getSharedPreferences("omni-onboarding", Context.MODE_PRIVATE).edit().clear().commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("Allow permissions")), 5000))
            device.findObject(By.text("Allow permissions")).click()
            repeat(4) {
                if (!device.hasObject(By.text("Sign in with Google"))) {
                    val allow = device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile(".*:id/permission_allow(_foreground_only)?_button"))), 3000)
                    assertFalse(device.hasObject(By.textContains("take pictures")))
                    allow?.click()
                }
            }
            assertWelcome()
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
            if (android.os.Build.VERSION.SDK_INT >= 33) assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, context.checkSelfPermission(android.Manifest.permission.CAMERA))
        }
    }

    @Test fun firstOpenPermissionsCanBeSkippedWithoutLockOrCameraPrompt() {
        context.getSharedPreferences("omni-onboarding", Context.MODE_PRIVATE).edit().clear().commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.text("A few permissions")), 5_000))
            assertFalse(device.hasObject(By.text("Unlock Omni bot AI")))
            assertFalse(device.hasObject(By.textContains("take pictures")))
            device.findObject(By.text("Not now")).click()
            assertWelcome()
            scenario.recreate()
            assertWelcome()
            assertFalse(device.hasObject(By.text("A few permissions")))
        }
    }

    @Test fun signedOutColdLaunchResumeAndRecreationNeverPrompt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertWelcome()
            repeat(2) {
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertWelcome()
            }
            scenario.recreate()
            assertWelcome()
            scenario.onActivity { it.unlockOrSetup() }
            assertWelcome()
        }
    }

    @Test fun obsoleteLoginFlagAndLocalRecordsCannotTriggerSignedOutUnlock() {
        context.getSharedPreferences("omni-auth", Context.MODE_PRIVATE).edit()
            .putBoolean("googleSignInCompleted", true).commit()
        OmniStore(context).use { it.save(Memory("existing", "Private local test note", "", Category.NOTE)) }
        ActivityScenario.launch(MainActivity::class.java).use {
            assertWelcome()
            assertFalse(device.hasObject(By.text("Private local test note")))
        }
    }

    @Test fun googleButtonOpensSignInWithoutFingerprintAndCancelReturnsToWelcome() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertWelcome()
            device.findObject(By.text("Sign in with Google")).click()
            TestSupport.waitUntil { pickerActivity() != null }
            instrumentation.runOnMainSync {
                val host = requireNotNull(pickerActivityOnMain())
                assertEquals("Sign-in must not draw another welcome page", 0,
                    host.findViewById<ViewGroup>(android.R.id.content).childCount)
            }
            assertFalse(device.hasObject(By.text("Unlock Omni bot AI")))
            instrumentation.runOnMainSync {
                (pickerActivityOnMain() as ControlledGoogleSignInActivity).selection
                    .completeExceptionally(GetCredentialCancellationException())
            }
            TestSupport.waitUntil { pickerActivity() == null }
            assertWelcome()
            // Cancellation must leave the button working for another attempt.
            device.findObject(By.text("Sign in with Google")).click()
            TestSupport.waitUntil { pickerActivity() != null }
            instrumentation.runOnMainSync { pickerActivityOnMain()?.finish() }
        }
    }

    private fun pickerActivityOnMain() = listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED).flatMap {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
    }.filterIsInstance<GoogleSignInActivity>().firstOrNull()

    @Test fun signInFailureReturnsToOriginalWelcomeAndAllowsRetry() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertWelcome()
            device.findObject(By.text("Sign in with Google")).click()
            TestSupport.waitUntil { pickerActivity() != null }
            instrumentation.runOnMainSync {
                (pickerActivityOnMain() as ControlledGoogleSignInActivity).selection
                    .completeExceptionally(GetCredentialUnknownException())
            }
            TestSupport.waitUntil { pickerActivity() == null }
            assertWelcome()
            assertTrue(device.hasObject(By.text("Could not open Google sign-in. Please try again.")))
            device.findObject(By.text("Sign in with Google")).click()
            TestSupport.waitUntil { pickerActivity() != null }
            instrumentation.runOnMainSync { pickerActivityOnMain()?.finish() }
        }
    }

    private fun signIn() = runBlocking {
        fun encoded(value: String) = Base64.encodeToString(value.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val now = System.currentTimeMillis() / 1000
        val claims = JSONObject().put("iss", "https://accounts.google.com").put("aud", BuildConfig.CLOUD_WEB_CLIENT_ID)
            .put("sub", "startup-test").put("email", "startup-test@example.test").put("email_verified", true)
            .put("iat", now).put("exp", now + 3600)
        val token = "${encoded("{\"alg\":\"none\"}")}.${encoded(claims.toString())}."
        services.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
        assertNotNull(services.owner)
    }

    @Test fun successfulGoogleReturnAllowsFingerprintAndSignOutRemovesIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertWelcome()
            device.findObject(By.text("Sign in with Google")).click()
            TestSupport.waitUntil { pickerActivity() != null }
            signIn()
            TestSupport.waitUntil { pickerActivity() == null }
            assertTrue("Signed-in vault must still require device unlock", device.wait(Until.hasObject(By.text("Unlock Omni bot AI")), 5_000))
            services.auth.signOut()
            assertWelcome()
        }
    }

    @Test fun returningGoogleAccountWithEmptyVaultStaysSignedInAndRequiresUnlock() {
        signIn()
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("Unlock Omni bot AI")), 5_000))
            assertFalse(device.hasObject(By.text("Sign in with Google")))
            services.auth.signOut()
            assertWelcome()
        }
    }
}
