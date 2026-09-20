package com.gyftalala.omni.cloud

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.storage.FirebaseStorage
import com.gyftalala.omni.BuildConfig

internal class CloudServices private constructor(val auth: FirebaseAuth, val storage: FirebaseStorage) {
    companion object {
        val configured get() = BuildConfig.CLOUD_PROJECT_ID.isNotBlank() && BuildConfig.CLOUD_APP_ID.isNotBlank() &&
            BuildConfig.CLOUD_API_KEY.isNotBlank() && BuildConfig.CLOUD_BUCKET.isNotBlank() &&
            BuildConfig.CLOUD_WEB_CLIENT_ID.isNotBlank()
        @Volatile private var instance: CloudServices? = null
        fun get(context: Context): CloudServices {
            check(configured) { "Cloud backup is not connected yet." }
            return instance ?: synchronized(this) {
                instance ?: run {
                    val app = FirebaseApp.initializeApp(context.applicationContext, FirebaseOptions.Builder()
                        .setProjectId(BuildConfig.CLOUD_PROJECT_ID).setApplicationId(BuildConfig.CLOUD_APP_ID)
                        .setApiKey(BuildConfig.CLOUD_API_KEY).setStorageBucket(BuildConfig.CLOUD_BUCKET).build(), "omni-backups")
                    val auth = FirebaseAuth.getInstance(app)
                    val storage = FirebaseStorage.getInstance(app)
                    if (BuildConfig.DEBUG && BuildConfig.CLOUD_EMULATOR) {
                        auth.useEmulator("127.0.0.1", 9099)
                        storage.useEmulator("127.0.0.1", 9199)
                    }
                    storage.maxUploadRetryTimeMillis = 120_000
                    storage.maxDownloadRetryTimeMillis = 120_000
                    storage.maxOperationRetryTimeMillis = 30_000
                    CloudServices(auth, storage).also { instance = it }
                }
            }
        }
    }
    // Email is a display label only. The authenticated Firebase UID owns every backup.
    val owner get() = auth.currentUser?.takeIf { user ->
        user.isEmailVerified && !user.email.isNullOrBlank() &&
            user.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }
    }
    fun requireOwner(uid: String) {
        check(uid.isNotBlank() && owner?.uid == uid) { "Sign in with Google to continue." }
    }
}
