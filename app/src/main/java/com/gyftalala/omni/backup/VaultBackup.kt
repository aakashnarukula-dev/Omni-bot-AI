package com.gyftalala.omni.backup

import android.content.Context
import android.util.AtomicFile
import com.gyftalala.omni.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.UUID

internal data class PreparedRestore(val manifest: BackupManifest, val directory: File, val mapped: Map<String, Attachment>)
internal data class RestoreResult(val memories: Int, val messages: Int, val reminders: Int, val skipped: Int)

/** No network, no plaintext archive, no replacement of existing records. Caller serializes app mutations. */
internal class VaultBackup(private val context: Context, private val store: OmniStore) {
    private val root = File(context.noBackupFilesDir, "backup-staging")

    fun write(output: OutputStream, password: CharArray, checkActive: () -> Unit = {}, progress: (String) -> Unit = {}) =
        writeArchive(output, checkActive, progress) { _, write -> BackupCipher.encrypt(output, password, write) }

    fun writeAccount(output: OutputStream, key: ByteArray, owner: ByteArray, checkActive: () -> Unit = {}, progress: (String) -> Unit = {}) =
        writeArchive(output, checkActive, progress) { manifest, write -> AccountBackupCipher.encrypt(output, key, owner, manifest.created, write) }

    private fun writeArchive(output: OutputStream, checkActive: () -> Unit, progress: (String) -> Unit,
        encrypt: (BackupManifest, (OutputStream) -> Unit) -> Unit) {
        val manifest = store.run {
            var snapshot: BackupManifest? = null
            transaction { snapshot = BackupManifest(System.currentTimeMillis(), backupRecords()).validate() }
            snapshot!!
        }
        val metadata = manifest.encode()
        try {
            progress("Protecting your backup…")
            encrypt(manifest) { stream ->
                checkActive()
                val data = DataOutputStream(stream)
                data.writeInt(metadata.size); data.write(metadata)
                data.writeInt(manifest.files.size)
                manifest.files.forEachIndexed { index, attachment ->
                    checkActive(); progress("Saving file ${index + 1} of ${manifest.files.size}…")
                    var written = 0L
                    store.vault.export(attachment, object : OutputStream() {
                        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            checkActive(); written += length
                            check(written <= attachment.size) { "A saved file has changed. Backup was not completed." }
                            data.write(bytes, offset, length)
                        }
                    })
                    check(written == attachment.size) { "A saved file is incomplete. Backup was not completed." }
                }
                checkActive(); data.writeInt(FOOTER)
            }
            output.flush()
        } finally { metadata.fill(0) }
    }

    fun prepare(input: InputStream, password: CharArray, checkActive: () -> Unit = {}, progress: (String) -> Unit = {}): PreparedRestore =
        prepareArchive(checkActive, progress) { read -> BackupCipher.decrypt(input, password) { read(it, null) } }

    fun prepareAccount(input: InputStream, key: ByteArray, owner: ByteArray, checkActive: () -> Unit = {}, progress: (String) -> Unit = {}): PreparedRestore =
        prepareArchive(checkActive, progress) { read -> AccountBackupCipher.decrypt(input, key, owner) { stream, created -> read(stream, created) } }

    private fun prepareArchive(checkActive: () -> Unit, progress: (String) -> Unit,
        decrypt: ((InputStream, Long?) -> PreparedRestore) -> PreparedRestore): PreparedRestore {
        val stage = File(root, UUID.randomUUID().toString())
        check(stage.mkdirs()) { "Could not prepare restore. Check free storage." }
        try {
            progress("Checking encrypted backup…")
            return decrypt { stream, created ->
                checkActive()
                val data = DataInputStream(stream)
                val length = data.readInt()
                require(length in 1..BackupManifest.MAX_MANIFEST) { "Invalid backup manifest size." }
                val metadata = ByteArray(length)
                val manifest = try { data.readFully(metadata); BackupManifest.decode(metadata) } finally { metadata.fill(0) }
                require(created == null || created == manifest.created) { "Invalid backup date." }
                require(data.readInt() == manifest.files.size) { "Invalid backup file count." }
                require(stage.usableSpace > manifest.bytes + 32L * 1024 * 1024) { "Not enough free storage to restore this backup." }
                val mapped = linkedMapOf<String, Attachment>()
                manifest.files.forEachIndexed { index, original ->
                    checkActive(); progress("Checking file ${index + 1} of ${manifest.files.size}…")
                    val restored = if (File(context.noBackupFilesDir, "vault/${original.id}").exists())
                        original.copy(id = UUID.randomUUID().toString()) else original
                    store.vault.stageRestore(File(stage, restored.id), restored.id, data, restored.size, checkActive)
                    mapped[original.id] = restored
                }
                require(data.readInt() == FOOTER) { "This backup is incomplete." }
                // BackupCipher then reads to authenticated EOF before this object can reach the UI.
                PreparedRestore(manifest, stage, mapped)
            }
        } catch (error: Exception) { stage.deleteRecursively(); throw error }
    }

    fun commit(prepared: PreparedRestore, checkActive: () -> Unit = {}): RestoreResult {
        check(prepared.directory.parentFile == root && prepared.directory.exists()) { "Choose the backup again." }
        var result: RestoreResult? = null
        try {
            store.transaction {
                checkActive()
                val ids = store.recordIds()
                val existingMemories = store.memories().associateBy { it.id }
                val records = prepared.manifest.records.filter { it.kind != "settings" && it.id !in ids }
                val newMemories = records.filter { it.kind == "memory" }.map { OmniStore.memoryFromJson(it.json) }
                val newMemoryIds = newMemories.map { it.id }.toSet()
                val needed = newMemories.flatMap { it.files }.map { it.id }.toSet()
                val toMove = needed.map { prepared.mapped.getValue(it) }
                // Durable journal contains only fresh opaque IDs, not names or card details.
                val journal = AtomicFile(File(prepared.directory, "promoted"))
                val journalOut = journal.startWrite()
                try {
                    journalOut.write(toMove.joinToString("\n") { it.id }.toByteArray())
                    journal.finishWrite(journalOut)
                } catch (error: Exception) { journal.failWrite(journalOut); throw error }
                for (attachment in toMove) {
                    checkActive()
                    store.vault.promoteRestore(File(prepared.directory, attachment.id), attachment.id)
                }
                store.vault.syncDirectory()
                var messageCount = 0; var reminderCount = 0
                for (r in records) {
                    checkActive()
                    val json = JSONObject(r.json.toString())
                    when (r.kind) {
                        "memory" -> {
                            val files = OmniStore.memoryFromJson(json).files.map { prepared.mapped.getValue(it.id) }
                            json.put("files", JSONArray(files.map { f -> JSONObject().put("id", f.id).put("name", f.name).put("mime", f.mime).put("size", f.size) }))
                            // Preserve the local-only invariant even for archives from older installations.
                            if (OmniStore.memoryFromJson(json).staysOnDevice) json.put("privateToDevice", true)
                        }
                        "message" -> {
                            val message = messageFromJson(json)
                            message.fileIds?.let { fileIds ->
                                val mappedIds = if (message.attachmentId in newMemoryIds) fileIds.map { prepared.mapped.getValue(it).id }
                                    else existingMemories[message.attachmentId]?.files.orEmpty().filter { it.id in fileIds }.map { it.id }
                                json.put("fileIds", JSONArray(mappedIds))
                            }
                            messageCount++
                        }
                        "reminder" -> {
                            // An existing item's newer state wins, even if its reminder was removed.
                            if (reminderFromJson(json).id !in newMemoryIds) continue
                            reminderCount++
                        }
                    }
                    store.saveBackupRecord(r.copy(json = json))
                }
                if ("settings" !in ids) {
                    val model = prepared.manifest.records.firstOrNull { it.kind == "settings" }?.json?.optString("model", "gemini-3.8-flash")
                    store.saveSettings(JSONObject().put("model", model ?: "gemini-3.8-flash").put("cloud", false))
                }
                checkActive()
                result = RestoreResult(newMemories.size, messageCount, reminderCount, prepared.manifest.memories.size - newMemories.size)
            }
            return result!!
        } finally {
            // Query durable references after transaction success/rollback; never remove committed files.
            runCatching { cleanup(prepared.directory) }
        }
    }

    fun discard(prepared: PreparedRestore?) { prepared?.let { cleanup(it.directory) } }

    /** Run once before starting backup work after process start. Covers death before/after DB commit. */
    fun recover() { root.listFiles()?.filter { it.isDirectory }?.forEach(::cleanup) }

    private fun cleanup(directory: File) {
        val journal = File(directory, "promoted")
        if (journal.exists()) {
            val used = store.memories().flatMap { it.files }.map { it.id }.toSet()
            journal.useLines { lines -> lines.filter(BackupManifest::safeFileId).filterNot { it in used }.forEach {
                store.vault.delete(Attachment(it, "", "", 0))
            } }
        }
        directory.deleteRecursively()
    }
    companion object { private const val FOOTER = 0x4F4D4E49 }
}
