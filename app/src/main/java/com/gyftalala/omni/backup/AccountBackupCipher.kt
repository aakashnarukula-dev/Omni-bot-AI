package com.gyftalala.omni.backup

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.*
import java.security.MessageDigest

internal data class AccountBackupHeader(val owner: ByteArray, val created: Long, val encoded: ByteArray)

/** Account format v1. The owner and date are authenticated along with every encrypted segment. */
internal object AccountBackupCipher {
    private val magic = "OMNIACC!".toByteArray(Charsets.US_ASCII)
    const val HEADER_SIZE = 52
    fun ownerTag(project: String, uid: String): ByteArray {
        require(project.isNotBlank() && uid.isNotBlank())
        return MessageDigest.getInstance("SHA-256").digest("$project\n$uid".toByteArray())
    }
    fun readHeader(input: InputStream): AccountBackupHeader {
        val header = ByteArray(HEADER_SIZE).also { DataInputStream(input).readFully(it) }
        val data = DataInputStream(ByteArrayInputStream(header))
        require(ByteArray(8).also(data::readFully).contentEquals(magic)) { "This is not an account backup." }
        require(data.readInt() == 1) { "This backup needs a newer version of Omni." }
        val owner = ByteArray(32).also(data::readFully)
        val created = data.readLong()
        require(created > 0) { "Invalid backup date." }
        return AccountBackupHeader(owner, created, header)
    }
    private fun primitive(key: ByteArray): AesGcmHkdfStreaming {
        require(key.size == 32)
        return AesGcmHkdfStreaming(key, "HmacSha256", 32, 1024 * 1024, 0)
    }
    fun encrypt(output: OutputStream, key: ByteArray, owner: ByteArray, created: Long, write: (OutputStream) -> Unit) {
        require(owner.size == 32 && created > 0)
        val header = ByteArrayOutputStream(HEADER_SIZE).also { bytes -> DataOutputStream(bytes).apply {
            write(magic); writeInt(1); write(owner); writeLong(created)
        } }.toByteArray()
        output.write(header)
        primitive(key).newEncryptingStream(object : FilterOutputStream(output) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun close() = flush()
        }, header).use(write)
    }
    fun <T> decrypt(input: InputStream, key: ByteArray, owner: ByteArray, read: (InputStream, Long) -> T): T {
        val header = readHeader(input)
        require(MessageDigest.isEqual(header.owner, owner)) { "This backup belongs to another Google account." }
        return primitive(key).newDecryptingStream(input, header.encoded).use { plain ->
            val result = read(plain, header.created)
            check(plain.read() == -1) { "Unexpected data after the backup. Nothing was restored." }
            result
        }
    }
}
