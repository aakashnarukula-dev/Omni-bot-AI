package com.gyftalala.omni.security

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.gyftalala.omni.data.Attachment
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Vault(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "vault").apply { mkdirs() }
    private val key: SecretKey by lazy {
        synchronized(Vault::class.java) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey("omni.vault.v1", null) as? SecretKey) ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder("omni.vault.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256).build())
                }.generateKey()
        }
    }
    private fun cipher(mode: Int, iv: ByteArray? = null, contextId: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (iv == null) init(mode, key) else init(mode, key, GCMParameterSpec(128, iv))
            updateAAD(contextId.toByteArray())
        }

    // One random data key per installation, wrapped by the non-exportable Keystore key.
    // Record operations use software AES-GCM, avoiding a hardware round trip for every row.
    private fun recordKey(): SecretKey = synchronized(recordKeys) {
        recordKeys.getOrPut(context.noBackupFilesDir.absolutePath) {
            val wrapped = AtomicFile(File(context.noBackupFilesDir, "record-key-v2"))
            val bytes = if (wrapped.baseFile.exists()) {
                val sealed = wrapped.openRead().use { it.readBytes() }
                require(sealed.size >= 28) { "The vault record key is damaged." }
                cipher(Cipher.DECRYPT_MODE, sealed.copyOfRange(0, 12), "omni:record-key:v2")
                    .doFinal(sealed.copyOfRange(12, sealed.size))
            } else {
                val generated = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().encoded
                val sealing = cipher(Cipher.ENCRYPT_MODE, contextId = "omni:record-key:v2")
                val output = wrapped.startWrite()
                try {
                    output.write(sealing.iv + sealing.doFinal(generated))
                    wrapped.finishWrite(output)
                } catch (error: Exception) { wrapped.failWrite(output); generated.fill(0); throw error }
                generated
            }
            try { require(bytes.size == 32); SecretKeySpec(bytes, "AES") }
            finally { bytes.fill(0) }
        }
    }
    private fun recordCipher(mode: Int, iv: ByteArray? = null, contextId: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (iv == null) init(mode, recordKey()) else init(mode, recordKey(), GCMParameterSpec(128, iv))
            updateAAD(RECORD_PREFIX + contextId.toByteArray())
        }

    fun encrypt(bytes: ByteArray, contextId: String): ByteArray {
        val cipher = recordCipher(Cipher.ENCRYPT_MODE, contextId = contextId)
        return RECORD_PREFIX + cipher.iv + cipher.doFinal(bytes)
    }
    fun decrypt(bytes: ByteArray, contextId: String): ByteArray {
        require(bytes.size >= 28) { "Encrypted record is damaged." }
        if (bytes.take(RECORD_PREFIX.size).toByteArray().contentEquals(RECORD_PREFIX)) {
            require(bytes.size >= RECORD_PREFIX.size + 28) { "Encrypted record is damaged." }
            return recordCipher(Cipher.DECRYPT_MODE, bytes.copyOfRange(RECORD_PREFIX.size, RECORD_PREFIX.size + 12), contextId)
                .doFinal(bytes.copyOfRange(RECORD_PREFIX.size + 12, bytes.size))
        }
        // v0.1.0 records and all existing attachment files retain their original format.
        return cipher(Cipher.DECRYPT_MODE, bytes.copyOfRange(0, 12), contextId)
            .doFinal(bytes.copyOfRange(12, bytes.size))
    }
    fun import(uri: Uri): Attachment {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/')?.take(160).orEmpty().ifBlank { "Attachment" }
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val nameColumn = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) name = it.getString(nameColumn).orEmpty().substringAfterLast('/').take(160).ifBlank { name }
                size = if (sizeColumn < 0 || it.isNull(sizeColumn)) -1 else it.getLong(sizeColumn)
            }
        }
        require(size <= MAX_BYTES) { "File exceeds 100 MB. Choose a smaller file." }
        val id = UUID.randomUUID().toString()
        val file = AtomicFile(File(directory, id))
        val output = file.startWrite()
        try {
            val cipher = cipher(Cipher.ENCRYPT_MODE, contextId = id)
            output.write(cipher.iv)
            var count = 0L
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot read this file. Choose it again." }
                // Do not close output before AtomicFile finishes its fsync/rename.
                val encrypted = CipherOutputStream(object : java.io.FilterOutputStream(output) {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { output.write(bytes, offset, length) }
                    override fun close() { flush() }
                }, cipher)
                encrypted.use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= MAX_BYTES) { "File exceeds 100 MB. Choose a smaller file." }
                        sink.write(buffer, 0, read)
                    }
                }
            }
            file.finishWrite(output)
            return Attachment(id, name, resolver.getType(uri) ?: "application/octet-stream", count)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
    fun read(attachment: Attachment): ByteArray {
        require(attachment.size <= 24 * 1024 * 1024) { "Image is too large to preview. Export the original instead." }
        return decrypt(File(directory, attachment.id).readBytes(), attachment.id)
    }
    fun export(attachment: Attachment, destination: java.io.OutputStream) {
        File(directory, attachment.id).inputStream().use { input ->
            val iv = ByteArray(12)
            check(input.read(iv) == 12) { "Encrypted file is damaged." }
            CipherInputStream(input, cipher(Cipher.DECRYPT_MODE, iv, attachment.id)).use { it.copyTo(destination, 64 * 1024) }
        }
    }
    fun delete(attachment: Attachment) { File(directory, attachment.id).delete() }
    /** Restore staging is always encrypted using this installation's key and a fresh attachment ID. */
    internal fun stageRestore(file: File, id: String, input: java.io.InputStream, size: Long, checkActive: () -> Unit) {
        require(size in 0..MAX_BYTES)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        val buffer = ByteArray(64 * 1024)
        try {
            val encryption = cipher(Cipher.ENCRYPT_MODE, contextId = id)
            output.write(encryption.iv)
            var remaining = size
            while (remaining > 0) {
                checkActive()
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(count > 0) { "Backup ended before a file was complete." }
                encryption.update(buffer, 0, count)?.let(output::write)
                remaining -= count
            }
            checkActive()
            output.write(encryption.doFinal())
            atomic.finishWrite(output)
        } catch (error: Exception) { atomic.failWrite(output); throw error }
        finally { buffer.fill(0) }
    }
    internal fun promoteRestore(file: File, id: String) {
        val target = File(directory, id)
        check(!target.exists() && file.renameTo(target)) { "Could not finish restoring files. Check free storage." }
    }
    internal fun syncDirectory() {
        val fd = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }
    companion object {
        const val MAX_BYTES = 100L * 1024 * 1024
        private val RECORD_PREFIX = byteArrayOf(0x4f, 0x4d, 0x4e, 0x49, 0x02, 0x00, 0x52, 0x43)
        private val recordKeys = mutableMapOf<String, SecretKey>()
    }
}
