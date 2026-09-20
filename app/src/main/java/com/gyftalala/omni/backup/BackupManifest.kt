package com.gyftalala.omni.backup

import com.gyftalala.omni.data.*
import com.gyftalala.omni.security.Vault
import org.json.JSONArray
import org.json.JSONObject

internal data class BackupManifest(val created: Long, val records: List<BackupRecord>) {
    val memories by lazy { records.filter { it.kind == "memory" }.map { OmniStore.memoryFromJson(it.json) } }
    val messages by lazy { records.filter { it.kind == "message" }.map { messageFromJson(it.json) } }
    val reminders by lazy { records.filter { it.kind == "reminder" }.map { reminderFromJson(it.json) } }
    val files by lazy { memories.flatMap { it.files }.distinctBy { it.id } }
    val bytes get() = files.sumOf { it.size }
    fun encode(): ByteArray = JSONObject().put("schema", 1).put("created", created)
        .put("records", JSONArray(records.map { record -> JSONObject().put("id", record.id).put("kind", record.kind)
            .put("created", record.created).put("json", record.json) })).toString().toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_MANIFEST) { "This vault is too large for this backup version." }
        }

    fun validate(): BackupManifest {
        require(created > 0 && records.size <= MAX_RECORDS) { "Invalid backup manifest." }
        require(records.map { it.id }.toSet().size == records.size) { "Duplicate records in backup." }
        for (r in records) {
            require(safeRecordId(r.id) && r.created >= 0) { "Invalid backup record." }
            when (r.kind) {
                "memory" -> { require(r.json.getString("id") == r.id); OmniStore.memoryFromJson(r.json) }
                "message" -> { require(r.json.getString("id") == r.id); messageFromJson(r.json) }
                "reminder" -> {
                    val reminder = reminderFromJson(r.json)
                    require(r.id == "reminder:${reminder.id}" && reminder.triggerAt >= 0 && reminder.repeat in setOf("none", "daily"))
                }
                "settings" -> {
                    require(r.id == "settings" && r.json.length() == 1)
                    require(r.json.getString("model").matches(Regex("[A-Za-z0-9._-]{1,100}")))
                }
                else -> error("This backup contains records from an unsupported version.")
            }
        }
        val attachments = memories.flatMap { it.files }
        require(attachments.size <= MAX_FILES && files.size <= MAX_FILES) { "Too many backup files." }
        require(attachments.groupBy { it.id }.values.all { group -> group.all { it == group.first() } }) { "Conflicting backup files." }
        for (f in files) {
            require(safeFileId(f.id) && f.size in 0..Vault.MAX_BYTES && f.name.length in 1..160 && f.mime.length in 1..256) { "Invalid backup file." }
        }
        require(bytes <= MAX_TOTAL) { "Backups larger than 10 GB are not supported yet." }
        val byId = memories.associateBy { it.id }
        for (m in messages) {
            // Detached historical messages are valid, but file references must belong to their memory.
            val memory = byId[m.attachmentId]
            require(m.fileIds == null || (memory != null && m.fileIds.all { id -> memory.files.any { it.id == id } })) { "Invalid chat file references." }
        }
        require(reminders.all { it.id in byId }) { "A reminder is missing its saved item." }
        return this
    }

    companion object {
        const val MAX_MANIFEST = 16 * 1024 * 1024
        const val MAX_RECORDS = 50_000
        const val MAX_FILES = 10_000
        const val MAX_TOTAL = 10L * 1024 * 1024 * 1024
        fun safeFileId(id: String) = id.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
        private fun safeRecordId(id: String) = id.matches(Regex("[A-Za-z0-9:_-]{1,180}"))
        fun decode(bytes: ByteArray): BackupManifest {
            require(bytes.size <= MAX_MANIFEST)
            checkNesting(bytes)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            require(json.getInt("schema") == 1) { "This backup needs a newer version of Omni." }
            val records = json.getJSONArray("records")
            require(records.length() <= MAX_RECORDS) { "Too many backup records." }
            return BackupManifest(json.getLong("created"), (0 until records.length()).map { i -> records.getJSONObject(i).let {
                BackupRecord(it.getString("id"), it.getString("kind"), it.getLong("created"), it.getJSONObject("json"))
            } }).validate()
        }
        private fun checkNesting(bytes: ByteArray) {
            var depth = 0; var quoted = false; var escaped = false
            for (byte in bytes) {
                val char = byte.toInt().toChar()
                if (quoted) {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; require(depth <= 32) { "Backup manifest is too deeply nested." } }
                    '}', ']' -> { depth--; require(depth >= 0) { "Invalid backup manifest." } }
                }
            }
            require(!quoted && depth == 0) { "Invalid backup manifest." }
        }
    }
}
