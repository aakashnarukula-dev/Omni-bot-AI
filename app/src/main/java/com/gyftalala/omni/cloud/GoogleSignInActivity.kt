package com.gyftalala.omni.cloud

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.credentials.*
import androidx.credentials.exceptions.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.GoogleAuthProvider
import com.gyftalala.omni.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.tasks.await

internal class GoogleSignInState : ViewModel() {
    var signingIn by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    // This operation survives rotation without retaining the activity or showing a second account picker.
    fun exchange(idToken: String, services: CloudServices) {
        if (signingIn) return
        signingIn = true; error = null
        viewModelScope.launch {
            try {
                services.auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
                check(services.owner != null) { "Google account verification did not complete." }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = if (failure is FirebaseNetworkException) "Could not connect. Check your connection and try again."
                    else "Could not sign in with Google. Please try again."
            } finally { signingIn = false }
        }
    }
}

/** Transparent credential host: the original welcome screen stays visible below Google's picker. */
open class GoogleSignInActivity : ComponentActivity() {
    companion object { const val ERROR = "google_sign_in_error" }
    private val model: GoogleSignInState by viewModels()
    private val services get() = CloudServices.get(this)
    private var selection: Job? = null
    private val authListener = com.google.firebase.auth.FirebaseAuth.AuthStateListener {
        if (services.owner != null) complete()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        suppressTransition()
        if (!BuildConfig.ALLOW_TEST_SCREENSHOTS) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!CloudServices.configured) { finish(); return }
        // No content or second login page. Failures return to the caller's existing UI.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                snapshotFlow { model.error }.filterNotNull().collect { fail(it) }
            }
        }
        // A credential request belongs to this activity; token exchange survives recreation.
        if (!model.signingIn && model.error == null) chooseAccount()
    }
    override fun onStart() {
        super.onStart()
        if (CloudServices.configured) services.auth.addAuthStateListener(authListener)
    }
    override fun onStop() {
        if (CloudServices.configured) services.auth.removeAuthStateListener(authListener)
        super.onStop()
    }
    override fun onDestroy() {
        selection?.cancel()
        super.onDestroy()
    }
    override fun finish() {
        super.finish()
        suppressTransition()
    }
    @Suppress("DEPRECATION")
    private fun suppressTransition() = overridePendingTransition(0, 0)

    private fun fail(message: String) {
        if (!isFinishing && !isDestroyed) {
            setResult(Activity.RESULT_CANCELED, Intent().putExtra(ERROR, message))
            finish()
        }
    }
    private fun complete() {
        if (!isFinishing && !isDestroyed) { setResult(Activity.RESULT_OK); finish() }
    }
    protected open suspend fun requestCredential(): GetCredentialResponse {
        val manager = CredentialManager.create(this)
        // Show accounts in the Credential Manager sheet itself. The button-only option
        // launches another Google dialog and leaves an empty selector edge on some phones.
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.CLOUD_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        return try {
            manager.getCredential(this, GetCredentialRequest.Builder().addCredentialOption(option).build())
        } catch (_: NoCredentialException) {
            // Still support adding an account or re-authenticating when no usable account
            // exists. User cancellation never opens another picker.
            manager.getCredential(this, GetCredentialRequest.Builder().addCredentialOption(
                GetSignInWithGoogleOption.Builder(BuildConfig.CLOUD_WEB_CLIENT_ID).build()).build())
        }
    }
    private fun chooseAccount() {
        if (selection?.isActive == true || model.signingIn || isFinishing || isDestroyed) return
        if (services.owner != null) { complete(); return }
        selection = lifecycleScope.launch {
            try {
                val result = requestCredential()
                val credential = result.credential
                require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                // Firebase verifies the Google token. No identity is trusted from client-parsed token claims.
                model.exchange(token, services)
            } catch (_: GetCredentialCancellationException) {
                finish()
            } catch (_: NoCredentialException) {
                model.error = "Add a Google account on this phone, then try again."
            } catch (_: GetCredentialProviderConfigurationException) {
                model.error = "Google sign-in is unavailable. Update Google Play services and try again."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { model.error = "Could not open Google sign-in. Please try again." }
        }
    }
}
