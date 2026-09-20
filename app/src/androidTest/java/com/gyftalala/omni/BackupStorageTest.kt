package com.gyftalala.omni

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.backup.*
import com.gyftalala.omni.data.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupStorageTest {
    private val context get() = TestSupport.context
    private val password get() = "test backup long passphrase".toCharArray()
    @Before fun reset() { TestSupport.reset(); File(context.noBackupFilesDir, "backup-staging").deleteRecursively() }
    private fun seed(store: OmniStore): ByteArray {
        val bytes = ByteArray(2 * 1024 * 1024 + 123) { (it % 251).toByte() }
        val file = store.vault.import(TestSupport.file("backup-synthetic.bin", bytes).second)
        store.save(Memory("card", "IndusInd debit card", "private card", Category.CARD, files = listOf(file),
            card = CardDetails("IndusInd", "TEST OWNER", "4111111111111111", "09/29", "123"), privateToDevice = true))
        for (kind in IdKind.entries) store.save(Memory("id${kind.name}", kind.label, "private ID", Category.DOCUMENT,
            identity = IdentityDetails(kind, "TEST NUMBER", "TEST OWNER", "01/01/1990"), privateToDevice = true))
        store.save(Memory("task", "Call doctor", "Call doctor", Category.REMINDER, priority = TaskPriority.URGENT, tag = TopicTag.HEALTH))
        store.save(Reminder("task", "Call doctor", System.currentTimeMillis() + 86400000, repeat = "daily"))
        store.save(ChatMessage("chat", Role.USER, "Photo caption", 10L, attachmentId = "card", fileIds = listOf(file.id)))
        store.saveSettings(JSONObject().put("key", "SYNTHETIC_SECRET_EXCLUDED").put("cloud", true).put("model", "gemini-3.8-flash"))
        return bytes
    }
    private fun archive(store: OmniStore) = ByteArrayOutputStream().also { VaultBackup(context, store).write(it, password) }.toByteArray()
    private fun stage(store: OmniStore, bytes: ByteArray) = VaultBackup(context, store).prepare(ByteArrayInputStream(bytes), password)
    private fun export(store: OmniStore, file: Attachment) = ByteArrayOutputStream().also { store.vault.export(file, it) }.toByteArray()
    private fun assertNoStaging() = assertTrue(File(context.noBackupFilesDir, "backup-staging").listFiles().orEmpty().isEmpty())

    @Test fun completeRoundTripPreservesPrivateRecordsAndOriginalBytesWithoutKey() {
        lateinit var original: ByteArray
        lateinit var encrypted: ByteArray
        OmniStore(context).use { store -> original = seed(store); encrypted = archive(store) }
        val decoded = BackupCipher.decrypt(ByteArrayInputStream(encrypted), password) { data ->
            val input = DataInputStream(data); val size = input.readInt()
            val manifest = ByteArray(size).also(input::readFully)
            while (input.read() != -1) { }
            String(manifest)
        }
        assertFalse(decoded.contains("SYNTHETIC_SECRET_EXCLUDED")); assertFalse(decoded.contains("\"cloud\""))
        TestSupport.reset()
        OmniStore(context).use { store ->
            val service = VaultBackup(context, store)
            val prepared = stage(store, encrypted)
            assertTrue(store.memories().isEmpty())
            assertFalse(File(prepared.directory, prepared.mapped.values.single().id).readBytes().contentEquals(original))
            val result = service.commit(prepared)
            assertEquals(6, result.memories); assertEquals(1, result.messages); assertEquals(1, result.reminders)
            val card = store.memories().first { it.id == "card" }
            assertArrayEquals(original, export(store, card.files.single()))
            assertEquals(listOf(card.files.single().id), store.messages().single().fileIds)
            assertEquals("4111111111111111", card.card!!.number)
            assertTrue(store.memories().filter { it.id != "task" }.all { it.staysOnDevice })
            assertEquals(TaskPriority.URGENT, store.memories().first { it.id == "task" }.priority)
            assertEquals("daily", store.reminders().single().repeat)
            assertFalse(store.settings().optBoolean("cloud")); assertEquals("", store.settings().optString("key"))
            assertNoStaging()
        }
    }

    @Test fun additiveRestorePreservesEditsKeyAndDoesNotDuplicateOnRepeat() {
        OmniStore(context).use { store ->
            seed(store); val encrypted = archive(store)
            val edited = store.memories().first { it.id == "card" }.copy(title = "Newer label")
            store.save(edited); store.delete("idPAN")
            store.delete("reminder:task")
            store.saveSettings(JSONObject().put("key", "CURRENT_SECRET").put("cloud", false).put("model", "custom-model"))
            val service = VaultBackup(context, store)
            assertEquals(1, service.commit(stage(store, encrypted)).memories)
            assertEquals(0, service.commit(stage(store, encrypted)).memories)
            assertEquals(edited, store.memories().first { it.id == "card" })
            assertEquals(6, store.memories().size); assertEquals(1, store.messages().size)
            assertTrue(store.reminders().isEmpty())
            assertEquals("CURRENT_SECRET", store.settings().getString("key"))
            assertEquals("custom-model", store.settings().getString("model"))
            assertNoStaging()
        }
    }

    @Test fun corruptBackupAndCancelledCommitLeaveExistingVaultUntouched() {
        lateinit var encrypted: ByteArray
        OmniStore(context).use { seed(it); encrypted = archive(it) }
        TestSupport.reset()
        OmniStore(context).use { store ->
            store.save(Memory("keep", "Keep me", "Existing data", Category.NOTE))
            val service = VaultBackup(context, store)
            for (bad in listOf(encrypted.copyOf(encrypted.size - 1), encrypted + byteArrayOf(1), encrypted.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() })) {
                try { stage(store, bad); fail("Expected damaged backup rejection") } catch (_: Exception) { }
                assertEquals("keep", store.memories().single().id); assertNoStaging()
            }
            val prepared = stage(store, encrypted)
            var checks = 0
            try { service.commit(prepared) { if (++checks == 3) throw CancellationException("Synthetic cancellation") }; fail("Expected cancellation") }
            catch (_: CancellationException) { }
            assertEquals("keep", store.memories().single().id)
            assertTrue(File(context.noBackupFilesDir, "vault").listFiles().orEmpty().isEmpty())
            assertNoStaging()
        }
    }

    @Test fun outputFailureCannotBecomeSuccessfulBackup() {
        OmniStore(context).use { store ->
            seed(store)
            var count = 0
            val broken = object : OutputStream() { override fun write(b: Int) { if (++count > 100) throw IOException("Synthetic full storage") } }
            try { VaultBackup(context, store).write(broken, password); fail("Expected write failure") } catch (_: IOException) { }
            assertEquals(6, store.memories().size)
        }
    }

    @Test fun recoveryRemovesOrphansAndKeepsCommittedAttachments() {
        OmniStore(context).use { store ->
            seed(store)
            val retained = store.memories().first { it.id == "card" }.files.single()
            val root = File(context.noBackupFilesDir, "backup-staging")
            val stage = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
            val orphan = UUID.randomUUID().toString()
            store.vault.stageRestore(File(stage, orphan), orphan, ByteArrayInputStream(byteArrayOf(7)), 1) { }
            File(stage, "promoted").writeText("$orphan\n${retained.id}")
            store.vault.promoteRestore(File(stage, orphan), orphan)
            VaultBackup(context, store).recover()
            assertFalse(File(context.noBackupFilesDir, "vault/$orphan").exists())
            assertTrue(File(context.noBackupFilesDir, "vault/${retained.id}").exists())
            assertNoStaging()
        }
    }

    @Test fun manifestRejectsPathsSecretsDuplicateIdsAndBrokenReferences() {
        OmniStore(context).use { store ->
            seed(store)
            val manifest = BackupManifest(System.currentTimeMillis(), store.backupRecords()).validate().encode()
            fun rejects(change: (JSONObject) -> Unit) {
                val json = JSONObject(String(manifest)); change(json)
                try { BackupManifest.decode(json.toString().toByteArray()); fail("Expected manifest rejection") } catch (_: Exception) { }
            }
            rejects { it.put("schema", 999) }
            rejects { j -> j.getJSONArray("records").put(j.getJSONArray("records").getJSONObject(0)) }
            rejects { j -> val rows = j.getJSONArray("records"); (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getString("kind") == "settings" }.getJSONObject("json").put("key", "secret") }
            rejects { j -> val rows = j.getJSONArray("records"); (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getString("id") == "card" }.getJSONObject("json").getJSONArray("files").getJSONObject(0).put("id", "../../outside") }
            rejects { j -> val rows = j.getJSONArray("records"); (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getString("id") == "chat" }.getJSONObject("json").put("attachmentId", "missing") }
        }
    }
}
