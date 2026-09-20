package com.gyftalala.omni.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class AccountBackupCipherTest {
    private val key = ByteArray(32) { it.toByte() }
    private val owner = AccountBackupCipher.ownerTag("project", "account")
    private val data = ByteArray(2_200_000) { (it % 251).toByte() }
    private fun encrypted(bytes: ByteArray = data) = ByteArrayOutputStream().also {
        AccountBackupCipher.encrypt(it, key, owner, 123456L) { out -> out.write(bytes) }
    }.toByteArray()
    private fun decrypt(bytes: ByteArray, secret: ByteArray = key, account: ByteArray = owner) =
        AccountBackupCipher.decrypt(bytes.inputStream(), secret, account) { input, created ->
            input.readBytes()
        }
    @Test fun segmentedRoundTripAndRandomizedCiphertext() {
        val first = encrypted(); val second = encrypted()
        assertEquals(123456L, AccountBackupCipher.readHeader(first.inputStream()).created)
        assertFalse(first.contentEquals(second)); assertArrayEquals(data, decrypt(first)); assertArrayEquals(data, decrypt(second))
    }
    @Test fun wrongKeyRejected() { assertTrue(runCatching { decrypt(encrypted(), ByteArray(32) { 42 }) }.isFailure) }
    @Test fun otherAccountAndProjectRejected() {
        for (tag in listOf(AccountBackupCipher.ownerTag("project", "other"), AccountBackupCipher.ownerTag("other", "account")))
            assertTrue(runCatching { decrypt(encrypted(), account = tag) }.isFailure)
    }
    @Test fun headerAndCiphertextTamperingRejected() {
        val original = encrypted()
        for (index in listOf(0, 11, 15, 51, 100, 1_100_000, original.lastIndex)) {
            val bytes = original.copyOf(); bytes[index] = (bytes[index].toInt() xor 1).toByte()
            assertTrue("Tamper at $index", runCatching { decrypt(bytes) }.isFailure)
        }
    }
    @Test fun truncatedOrAppendedArchiveRejected() {
        val bytes = encrypted()
        for (size in listOf(0, 51, 100, 1_100_000, bytes.size - 1))
            assertTrue(runCatching { decrypt(bytes.copyOf(size)) }.isFailure)
        assertTrue(runCatching { decrypt(bytes + byteArrayOf(42)) }.isFailure)
    }
    @Test fun editedOwnerCannotTransferArchive() {
        val bytes = encrypted(); val other = AccountBackupCipher.ownerTag("project", "other")
        other.copyInto(bytes, 12)
        assertTrue(runCatching { decrypt(bytes, account = other) }.isFailure)
    }
    @Test fun unreadTrailingPlaintextRejected() {
        assertTrue(runCatching { AccountBackupCipher.decrypt(encrypted().inputStream(), key, owner) { input, _ -> input.read() } }.isFailure)
    }
    @Test fun emptyPayloadRoundTrip() { assertArrayEquals(byteArrayOf(), decrypt(encrypted(byteArrayOf()))) }
}
