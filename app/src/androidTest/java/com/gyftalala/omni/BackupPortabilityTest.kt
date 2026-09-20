package com.gyftalala.omni

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gyftalala.omni.backup.VaultBackup
import com.gyftalala.omni.data.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream

/** Host transfers only synthetic artifacts between a removed app and a fresh installation. */
@RunWith(AndroidJUnit4::class)
class BackupPortabilityTest {
    private val password get() = "portable synthetic backup password".toCharArray()
    private val context get() = TestSupport.context
    private fun guard() { TestSupport.requireIsolated(); assumeTrue(InstrumentationRegistry.getArguments().getString("backupTransfer") == "true") }
    @Test fun prepare() {
        guard(); TestSupport.reset()
        OmniStore(context).use { store ->
            val attachment = store.vault.import(TestSupport.file("portable.bin", "PORTABLE ORIGINAL".toByteArray()).second)
            store.save(Memory("portable", "IndusInd debit card", "", Category.CARD, files = listOf(attachment),
                card = CardDetails("IndusInd", "TEST OWNER", "4111111111111111", "09/29", "123"), privateToDevice = true))
            val out = context.getExternalFilesDir(null)!!
            File(out, "portable.omnibak").outputStream().use { VaultBackup(context, store).write(it, password) }
            File(out, "old-key-probe.bin").writeBytes(store.vault.encrypt("OLD DEVICE".toByteArray(), "probe"))
        }
    }
    @Test fun verifyFreshInstall() {
        guard()
        OmniStore(context).use { store ->
            assertTrue(store.memories().isEmpty())
            val out = context.getExternalFilesDir(null)!!
            try { store.vault.decrypt(File(out, "old-key-probe.bin").readBytes(), "probe"); fail("Old installation key must not work") } catch (_: Exception) { }
            val service = VaultBackup(context, store)
            val prepared = File(out, "portable.omnibak").inputStream().use { service.prepare(it, password) }
            assertEquals(1, service.commit(prepared).memories)
            val card = store.memories().single()
            assertEquals("4111111111111111", card.card!!.number); assertTrue(card.staysOnDevice)
            val restored = ByteArrayOutputStream().also { store.vault.export(card.files.single(), it) }.toByteArray()
            assertEquals("PORTABLE ORIGINAL", String(restored))
            assertFalse(store.settings().optBoolean("cloud")); assertFalse(store.settings().has("key"))
        }
    }
}
