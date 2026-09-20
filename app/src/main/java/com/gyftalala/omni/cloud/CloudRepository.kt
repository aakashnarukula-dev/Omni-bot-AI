package com.gyftalala.omni.cloud

import android.net.Uri
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.StorageTask
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RemoteBackup(val name: String, val created: Long, val bytes: Long)

/** Immutable objects become visible only after the upload completes. No public URLs or plaintext metadata. */
internal class CloudRepository(private val services: CloudServices) {
    private fun folder(uid: String): StorageReference {
        services.requireOwner(uid)
        require(uid.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return services.storage.reference.child("backups/$uid")
    }
    suspend fun list(uid: String): List<RemoteBackup> {
        val root = folder(uid)
        val found = mutableListOf<RemoteBackup>()
        var token: String? = null
        do {
            services.requireOwner(uid)
            val page = if (token == null) root.list(100).await() else root.list(100, token).await()
            for (ref in page.items) {
                require(found.size < 1000) { "Too many backup versions. Contact support before continuing." }
                if (!NAME.matches(ref.name)) continue
                val metadata = ref.metadata.await()
                if (metadata.sizeBytes in 1..MAX_BYTES) found += RemoteBackup(ref.name, metadata.creationTimeMillis, metadata.sizeBytes)
            }
            token = page.pageToken
        } while (token != null)
        services.requireOwner(uid)
        return found.sortedByDescending { it.created }
    }
    suspend fun upload(uid: String, file: File): RemoteBackup {
        require(file.length() in 1..MAX_BYTES) { "Backup exceeds the 11 GB cloud limit." }
        val ref = folder(uid).child("${UUID.randomUUID()}.omnibak")
        val metadata = StorageMetadata.Builder().setContentType("application/octet-stream")
            .setCacheControl("no-store").build()
        val result = ref.putFile(Uri.fromFile(file), metadata).awaitTransfer()
        services.requireOwner(uid)
        val saved = result.metadata ?: ref.metadata.await()
        check(saved.sizeBytes == file.length()) { "Upload could not be verified." }
        return RemoteBackup(ref.name, saved.creationTimeMillis, saved.sizeBytes)
    }
    suspend fun header(uid: String, remote: RemoteBackup): com.gyftalala.omni.backup.AccountBackupHeader {
        require(NAME.matches(remote.name))
        val root = folder(uid)
        var header: com.gyftalala.omni.backup.AccountBackupHeader? = null
        root.child(remote.name).getStream { _, stream ->
            stream.use { header = com.gyftalala.omni.backup.AccountBackupCipher.readHeader(it) }
        }.awaitTransfer()
        services.requireOwner(uid)
        return checkNotNull(header)
    }
    suspend fun download(uid: String, remote: RemoteBackup, file: File) {
        require(NAME.matches(remote.name) && remote.bytes in 1..MAX_BYTES)
        check(file.parentFile!!.usableSpace > remote.bytes + 32L * 1024 * 1024) { "Not enough free storage to download this backup." }
        folder(uid).child(remote.name).getFile(file).awaitTransfer()
        services.requireOwner(uid)
        check(file.length() == remote.bytes) { "Backup download is incomplete." }
    }
    /** Keep the newest three complete versions. Only clean objects older than this successful upload. */
    suspend fun prune(uid: String, saved: RemoteBackup) {
        list(uid).drop(3).filter { it.created < saved.created }.forEach {
            folder(uid).child(it.name).delete().await()
        }
    }
    companion object {
        const val MAX_BYTES = 11L * 1024 * 1024 * 1024
        private val NAME = Regex("[a-f0-9-]{36}\\.omnibak")
    }
}

private suspend fun <T : StorageTask<T>.SnapshotBase> StorageTask<T>.awaitTransfer(): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
