package com.gyftalala.omni.cloud

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.gyftalala.omni.security.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/** Account recovery uses an immutable key in owner-protected Firebase Storage; local copies use Keystore encryption. */
internal class CloudAccountKey(private val context: Context, private val services: CloudServices) {
    private val file get() = AtomicFile(File(context.noBackupFilesDir, "cloud-account-key"))
    private fun cached(uid: String): ByteArray? {
        if (!file.baseFile.exists()) return null
        val plain = Vault(context).decrypt(file.readFully(), PURPOSE)
        try {
            val json = JSONObject(String(plain, Charsets.UTF_8))
            if (json.getString("uid") != uid) return null
            return Base64.decode(json.getString("key"), Base64.NO_WRAP).also { require(it.size == 32) }
        } finally { plain.fill(0) }
    }
    private fun cache(uid: String, key: ByteArray) {
        val plain = JSONObject().put("uid", uid).put("key", Base64.encodeToString(key, Base64.NO_WRAP)).toString().toByteArray()
        val encrypted = try { Vault(context).encrypt(plain, PURPOSE) } finally { plain.fill(0) }
        val out = file.startWrite()
        try { out.write(encrypted); file.finishWrite(out) } catch (error: Exception) { file.failWrite(out); throw error }
    }
    suspend fun get(uid: String, refreshRemote: Boolean = false): ByteArray = mutex.withLock {
        services.requireOwner(uid)
        if (!refreshRemote) runCatching { cached(uid) }.getOrNull()?.let { return@withLock it }
        val reference = services.storage.reference.child("keys/$uid/backup-key-v1")
        suspend fun download() = reference.getBytes(32).await().also { require(it.size == 32) { "Invalid account recovery key." } }
        val key = try { download() }
        catch (error: StorageException) {
            if (error.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw error
            // Losing a key must never silently create an unrelated key over existing backups.
            check(CloudRepository(services).list(uid).isEmpty()) { "Account recovery key is unavailable. Existing backups are kept." }
            val candidate = ByteArray(32).also(SecureRandom()::nextBytes)
            try {
                try {
                    reference.putBytes(candidate, StorageMetadata.Builder().setContentType("application/octet-stream").build()).await()
                    download().also { check(it.contentEquals(candidate)) { "Account recovery key changed." } }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { download() } // A simultaneous first sign-in may have created the immutable key.
            } finally { candidate.fill(0) }
        }
        try { services.requireOwner(uid); cache(uid, key); key }
        catch (error: Exception) { key.fill(0); throw error }
    }
    suspend fun clear() = mutex.withLock { file.delete() }
    companion object {
        private val mutex = Mutex()
        private const val PURPOSE = "cloud-account-key:v1"
    }
}
