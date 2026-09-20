package com.gyftalala.omni.backup

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.*
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Portable format v1. Tink owns nonce construction, segment authentication and final-segment checks. */
internal object BackupCipher {
    const val ITERATIONS = 600_000
    private val magic = "OMNIBAK!".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val SALT_SIZE = 32
    private const val HEADER_SIZE = 8 + 4 + 4 + SALT_SIZE

    fun validPassword(password: CharArray) = password.size in 12..256 && password.any { !it.isWhitespace() }

    private fun primitive(password: CharArray, salt: ByteArray): AesGcmHkdfStreaming {
        require(validPassword(password)) { "Use a backup password with 12–256 characters." }
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        return try { AesGcmHkdfStreaming(key, "HmacSha256", 32, 1024 * 1024, 0) }
            finally { key.fill(0) }
    }

    fun encrypt(output: OutputStream, password: CharArray, write: (OutputStream) -> Unit) {
        val salt = ByteArray(SALT_SIZE).also(SecureRandom()::nextBytes)
        val header = ByteArrayOutputStream(HEADER_SIZE).also { bytes -> DataOutputStream(bytes).apply {
            write(magic); writeInt(VERSION); writeInt(ITERATIONS); write(salt)
        } }.toByteArray()
        val cipher = primitive(password, salt)
        output.write(header)
        cipher.newEncryptingStream(NonClosingOutput(output), header).use(write)
    }

    fun <T> decrypt(input: InputStream, password: CharArray, read: (InputStream) -> T): T {
        val header = ByteArray(HEADER_SIZE)
        try { DataInputStream(input).readFully(header) }
        catch (_: EOFException) { throw IOException("This backup is incomplete or is not an Omni backup.") }
        val data = DataInputStream(ByteArrayInputStream(header))
        val signature = ByteArray(magic.size).also(data::readFully)
        require(signature.contentEquals(magic)) { "Choose an Omni .omnibak backup file." }
        require(data.readInt() == VERSION) { "This backup needs a newer version of Omni." }
        // Fixed work factor prevents attacker-controlled PBKDF work or a silent downgrade.
        require(data.readInt() == ITERATIONS) { "This backup has an unsupported encryption format." }
        val salt = ByteArray(SALT_SIZE).also(data::readFully)
        return primitive(password, salt).newDecryptingStream(input, header).use { plain ->
            val result = read(plain)
            check(plain.read() == -1) { "Unexpected data after the backup. Nothing was restored." }
            result
        }
    }

    private class NonClosingOutput(out: OutputStream) : FilterOutputStream(out) {
        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
        override fun close() = flush()
    }
}
