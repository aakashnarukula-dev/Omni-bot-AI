package com.gyftalala.omni.cloud

import android.content.Context
import android.content.ContentValues
import android.content.ContentUris
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.gyftalala.omni.backup.AccountBackupCipher
import com.gyftalala.omni.security.Vault
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

internal data class MobileBackup(val uri: Uri, val name: String, val created: Long)
internal data class MobileBackups(val files: List<MobileBackup>, val ignored: Int)

/** Reads app-owned Downloads and explicitly granted files, never unrestricted shared storage. */
internal class MobileBackupCatalog(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "mobile-backup-sources"))
    fun remember(uris: List<Uri>) = synchronized(lock) {
        require(uris.size <= MAX_FILES) { "Choose at most 10 backup files." }
        val known = runCatching { readUris() }.getOrDefault(emptyList())
        val all = (uris.map(Uri::toString) + known).distinct().take(MAX_FILES)
        val plain = JSONArray(all).toString().toByteArray()
        val encrypted = try { Vault(context).encrypt(plain, PURPOSE) } finally { plain.fill(0) }
        val out = file.startWrite()
        try { out.write(encrypted); file.finishWrite(out) } catch (error: Exception) { file.failWrite(out); throw error }
    }
    private fun readUris(): List<String> {
        if (!file.baseFile.exists()) return emptyList()
        val plain = Vault(context).decrypt(file.readFully(), PURPOSE)
        try { val json = JSONArray(String(plain)); return (0 until json.length().coerceAtMost(MAX_FILES)).map(json::getString) }
        finally { plain.fill(0) }
    }
    fun scan(owner: ByteArray, checkActive: () -> Unit = {}): MobileBackups = synchronized(lock) {
        var ignored = 0
        val known = runCatching { readUris() }.getOrElse { ignored++; emptyList() }
        val automatic = runCatching { automaticUris() }.getOrElse { ignored++; emptyList() }
        val files = (known + automatic.map(Uri::toString)).distinct().mapNotNull { raw ->
            checkActive()
            try {
                val uri = Uri.parse(raw)
                val header = context.contentResolver.openInputStream(uri)?.use(AccountBackupCipher::readHeader) ?: error("Unavailable file")
                if (!MessageDigest.isEqual(header.owner, owner)) return@mapNotNull null
                val name = if (uri.scheme == "file") File(uri.path!!).name else context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "Omni backup"
                MobileBackup(uri, name.take(160), header.created)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { ignored++; null }
        }
        MobileBackups(files, ignored)
    }
    private fun automaticUris(): List<Uri> {
        if (Build.VERSION.SDK_INT < 29) return legacyFolder().listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("Omni-") && it.extension == "omnibak" }.map(Uri::fromFile)
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        return context.contentResolver.query(collection, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.IS_PENDING} = 0",
            arrayOf(DIRECTORY, "Omni-%.omnibak"), "${MediaStore.Downloads.DATE_ADDED} DESC")?.use { cursor ->
            buildList { while (size < 100 && cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
        }.orEmpty()
    }
    /** Publish only complete ciphertext; a failed copy never appears as a valid backup. */
    fun save(source: File, owner: ByteArray, created: Long, checkActive: () -> Unit = {}): Uri {
        val name = "Omni-$created-${java.util.UUID.randomUUID()}.omnibak"
        val uri = if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, DIRECTORY)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val target = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create phone backup")
            try {
                context.contentResolver.openOutputStream(target, "w")!!.use { output ->
                    source.inputStream().use { input -> copy(input, output, checkActive) }
                }
                checkActive()
                check(context.contentResolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
                target
            } catch (error: Exception) { runCatching { context.contentResolver.delete(target, null, null) }; throw error }
        } else {
            val target = File(legacyFolder().apply { mkdirs() }, name)
            val pending = File(target.parentFile, "$name.partial")
            try {
                pending.outputStream().use { output -> source.inputStream().use { copy(it, output, checkActive) } }
                checkActive(); check(pending.renameTo(target)); Uri.fromFile(target)
            } finally { pending.delete() }
        }
        // Keep three completed automatic copies for this account. Never prune chosen exports.
        runCatching {
            automaticUris().mapNotNull { old ->
                context.contentResolver.openInputStream(old)?.use(AccountBackupCipher::readHeader)?.let {
                    if (MessageDigest.isEqual(it.owner, owner)) old to it.created else null
                }
            }.sortedByDescending { it.second }.drop(3).forEach { (old, _) ->
                if (old != uri) {
                    if (old.scheme == "file") File(old.path!!).delete() else context.contentResolver.delete(old, null, null)
                }
            }
        }
        return uri
    }
    private fun legacyFolder() = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")
    private fun copy(input: java.io.InputStream, output: java.io.OutputStream, checkActive: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        while (true) { checkActive(); val size = input.read(buffer); if (size < 0) break; output.write(buffer, 0, size) }
    }
    companion object {
        const val MAX_FILES = 10
        const val DIRECTORY = "Download/Omni/"
        private val lock = Any()
        private const val PURPOSE = "mobile-backup-sources:v1"
    }
}
