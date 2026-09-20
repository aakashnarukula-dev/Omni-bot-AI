package com.gyftalala.omni

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.credentials.GetCredentialResponse
import androidx.test.runner.AndroidJUnitRunner
import com.gyftalala.omni.cloud.GoogleSignInActivity
import kotlinx.coroutines.CompletableDeferred

/** Only the disposable auth tests control the external picker; production never includes this runner. */
class OmniTestRunner : AndroidJUnitRunner() {
    private var controlledSignIn = false

    override fun onCreate(arguments: Bundle) {
        controlledSignIn = arguments.getString("controlledGoogleSignIn") == "true"
        check(!controlledSignIn || (BuildConfig.CLOUD_EMULATOR && BuildConfig.APPLICATION_ID.endsWith(".verification")))
        super.onCreate(arguments)
    }

    override fun newActivity(loader: ClassLoader, className: String, intent: Intent): Activity {
        return if (controlledSignIn && className == GoogleSignInActivity::class.java.name) {
            ControlledGoogleSignInActivity()
        } else super.newActivity(loader, className, intent)
    }
}

class ControlledGoogleSignInActivity : GoogleSignInActivity() {
    val selection = CompletableDeferred<GetCredentialResponse>()
    override suspend fun requestCredential(): GetCredentialResponse = selection.await()
}
