package com.gyftalala.omni.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class BackupCipherTest {
    private val password get() = "seven lanterns over quiet water".toCharArray()
    private fun encrypt(bytes: ByteArray) = ByteArrayOutputStream().also { output -> BackupCipher.encrypt(output, password) { it.write(bytes) } }.toByteArray()
    private fun decrypt(bytes: ByteArray, secret: CharArray = password) = BackupCipher.decrypt(ByteArrayInputStream(bytes), secret) { it.readBytes() }
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (expected: Exception) { } }

    @Test fun portableRoundTripIsRandomizedAndDoesNotExposeContent() {
        val content = "PRIVATE CARD NUMBER AND CHATS".repeat(100).toByteArray()
        val first = encrypt(content); val second = encrypt(content)
        assertArrayEquals(content, decrypt(first)); assertArrayEquals(content, decrypt(second))
        assertFalse(first.contentEquals(second))
        assertFalse(String(first, Charsets.ISO_8859_1).contains("PRIVATE CARD"))
    }
    @Test fun wrongPasswordHeaderChangesAndCiphertextChangesReject() {
        val bytes = encrypt(ByteArray(3000) { it.toByte() })
        rejects { decrypt(bytes, "wrong password still long".toCharArray()) }
        for (index in listOf(0, 12, 20, 55, bytes.lastIndex)) {
            val corrupt = bytes.copyOf(); corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
            rejects { decrypt(corrupt) }
        }
    }
    @Test fun multiSegmentTruncationReorderingAndTrailingBytesReject() {
        val content = ByteArray(3 * 1024 * 1024 + 97) { (it % 251).toByte() }
        val encrypted = encrypt(content)
        assertArrayEquals(content, decrypt(encrypted))
        for (length in listOf(0, 47, 48, 1024 * 1024 + 48, encrypted.size - 1)) rejects { decrypt(encrypted.copyOf(length)) }
        rejects { decrypt(encrypted + byteArrayOf(9)) }
        val swapped = encrypted.copyOf()
        val one = encrypted.copyOfRange(48 + 1024 * 1024, 48 + 2 * 1024 * 1024)
        encrypted.copyInto(swapped, 48 + 1024 * 1024, 48 + 2 * 1024 * 1024, 48 + 3 * 1024 * 1024)
        one.copyInto(swapped, 48 + 2 * 1024 * 1024)
        rejects { decrypt(swapped) }
    }
    @Test fun unicodeAndSpacesRemainExactAndWeakPasswordsReject() {
        val secret = "  మన ఓమ్ని బ్యాకప్  ".toCharArray()
        val output = ByteArrayOutputStream()
        BackupCipher.encrypt(output, secret) { it.write("test".toByteArray()) }
        assertArrayEquals("test".toByteArray(), decrypt(output.toByteArray(), secret))
        rejects { decrypt(output.toByteArray(), String(secret).trim().toCharArray()) }
        assertFalse(BackupCipher.validPassword("short".toCharArray()))
        assertFalse(BackupCipher.validPassword(" ".repeat(12).toCharArray()))
        assertFalse(BackupCipher.validPassword("x".repeat(257).toCharArray()))
    }
}
