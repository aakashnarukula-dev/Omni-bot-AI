package com.gyftalala.omni.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.gyftalala.omni.security.Vault
import org.json.JSONArray
import org.json.JSONObject

/** Only opaque IDs, record types and timestamps are plaintext. All content is authenticated ciphertext. */
class OmniStore(context: Context) : SQLiteOpenHelper(context, "omni.db", null, 1) {
    val vault = Vault(context)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, kind TEXT NOT NULL, payload BLOB NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX records_kind_created ON records(kind, created)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized private fun put(id: String, kind: String, json: JSONObject, created: Long = System.currentTimeMillis()) {
        writableDatabase.insertWithOnConflict("records", null, ContentValues().apply {
            put("id", id); put("kind", kind); put("created", created)
            put("payload", vault.encrypt(json.toString().toByteArray(), "$kind:$id"))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Could not save. Check free storage." } }
    }
    @Synchronized private fun rows(kind: String): List<JSONObject> = readableDatabase.query(
        "records", arrayOf("id", "payload"), "kind = ?", arrayOf(kind), null, null, "created ASC"
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(JSONObject(String(vault.decrypt(cursor.getBlob(1), "$kind:${cursor.getString(0)}"))))
    } }
    @Synchronized fun delete(id: String) { writableDatabase.delete("records", "id = ?", arrayOf(id)) }
    @Synchronized fun transaction(block: () -> Unit) {
        writableDatabase.beginTransaction()
        try { block(); writableDatabase.setTransactionSuccessful() } finally { writableDatabase.endTransaction() }
    }
    fun save(memory: Memory) = put(memory.id, "memory", JSONObject().apply {
        put("id", memory.id); put("title", memory.title); put("text", memory.text); put("category", memory.category.name)
        put("privateToDevice", memory.privateToDevice); put("categoryConfirmedAt", memory.categoryConfirmedAt)
        put("createdAt", memory.createdAt); put("sortOrder", memory.sortOrder); put("ocr", memory.ocr); put("question", memory.question); put("status", memory.status)
        put("palette", JSONArray(memory.palette)); put("priority", memory.priority.name); put("tag", memory.tag.name)
        memory.identity?.let { d -> put("identity", JSONObject().put("kind", d.kind.name).put("number", d.number)
            .put("name", d.name).put("birthDate", d.birthDate)) }
        put("alternatives", JSONArray(memory.alternatives.map { it.name }))
        put("files", JSONArray(memory.files.map { f -> JSONObject().put("id", f.id).put("name", f.name).put("mime", f.mime).put("size", f.size) }))
        memory.card?.let { c -> put("card", JSONObject().put("issuer", c.issuer).put("holder", c.holder).put("number", c.number)
            .put("expiry", c.expiry).put("cvv", c.cvv).put("type", c.type).put("network", c.network)) }
    }, memory.createdAt)
    fun memories(): List<Memory> = rows("memory").map(::memoryFromJson)
    companion object {
    internal fun memoryFromJson(j: JSONObject): Memory {
        val card = j.optJSONObject("card")?.let { CardDetails(it.optString("issuer"), it.optString("holder"), it.optString("number"),
            it.optString("expiry"), it.optString("cvv"), it.optString("type"), it.optString("network")) }
        val files = j.getJSONArray("files").let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let {
            Attachment(it.getString("id"), it.getString("name"), it.getString("mime"), it.getLong("size")) } } }
        val alternatives = j.optJSONArray("alternatives")?.let { a -> (0 until a.length()).map { Category.valueOf(a.getString(it)) } }.orEmpty()
        return Memory(j.getString("id"), j.getString("title"), j.getString("text"), Category.valueOf(j.getString("category")),
            j.getLong("createdAt"), files, j.optString("ocr"), card, j.optional("question"), alternatives, j.optString("status", "Saved"),
            identity = j.optJSONObject("identity")?.let { IdentityDetails(
                runCatching { IdKind.valueOf(it.optString("kind")) }.getOrDefault(IdKind.OTHER), it.optString("number"), it.optString("name"), it.optString("birthDate")) },
            palette = j.optJSONArray("palette")?.let { a -> (0 until a.length()).map(a::getInt) }.orEmpty(),
            priority = runCatching { TaskPriority.valueOf(j.optString("priority")) }.getOrDefault(TaskPriority.NORMAL),
            tag = runCatching { TopicTag.valueOf(j.optString("tag")) }.getOrDefault(TopicTag.PERSONAL),
            privateToDevice = j.optBoolean("privateToDevice"), categoryConfirmedAt = j.optLong("categoryConfirmedAt").coerceAtLeast(0),
            sortOrder = j.optLong("sortOrder", j.getLong("createdAt")))
    }
    }
    fun save(message: ChatMessage) = put(message.id, "message", JSONObject().apply {
        put("id", message.id); put("role", message.role.name); put("text", message.text); put("createdAt", message.createdAt)
        put("attachmentId", message.attachmentId)
        put("action", message.action)
        message.fileIds?.let { put("fileIds", JSONArray(it)) }
    }, message.createdAt)
    fun messages() = rows("message").map(::messageFromJson)
    fun save(reminder: Reminder) = put("reminder:${reminder.id}", "reminder", JSONObject().apply {
        put("id", reminder.id); put("title", reminder.title); put("triggerAt", reminder.triggerAt); put("completed", reminder.completed)
        put("repeat", reminder.repeat); put("delivered", reminder.delivered)
        put("kind", reminder.kind.name); put("callStyle", reminder.callStyle); put("anchorAt", reminder.anchorAt)
        put("retryCount", reminder.retryCount); put("lastAction", reminder.lastAction)
    })
    fun reminders() = rows("reminder").map(::reminderFromJson)
    fun settings(): JSONObject = rows("settings").firstOrNull() ?: JSONObject()
    fun saveSettings(json: JSONObject) = put("settings", "settings", json)
    /** Snapshot only portable records; credentials and device consent never enter the archive. */
    @Synchronized internal fun backupRecords(): List<BackupRecord> = buildList {
        readableDatabase.query("records", arrayOf("id", "kind", "payload", "created"), null, null, null, null, "created ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0); val kind = cursor.getString(1)
                if (kind == "settings") continue
                val bytes = vault.decrypt(cursor.getBlob(2), "$kind:$id")
                try { add(BackupRecord(id, kind, cursor.getLong(3), JSONObject(String(bytes, Charsets.UTF_8)))) }
                finally { bytes.fill(0) }
            }
        }
        add(BackupRecord("settings", "settings", System.currentTimeMillis(), JSONObject()
            .put("model", settings().optString("model", "gemini-3.8-flash"))))
    }
    @Synchronized internal fun recordIds(): Set<String> = readableDatabase.query("records", arrayOf("id"), null, null, null, null, null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    internal fun saveBackupRecord(record: BackupRecord) = put(record.id, record.kind, record.json, record.created)

}

internal data class BackupRecord(val id: String, val kind: String, val created: Long, val json: JSONObject)
internal fun messageFromJson(j: JSONObject) = ChatMessage(j.getString("id"), Role.valueOf(j.getString("role")), j.getString("text"),
    j.getLong("createdAt"), attachmentId = j.optional("attachmentId"), action = j.optional("action"),
    fileIds = j.optJSONArray("fileIds")?.let { a -> (0 until a.length()).map(a::getString) })
internal fun reminderFromJson(j: JSONObject) = Reminder(j.getString("id"), j.getString("title"), j.getLong("triggerAt"),
    j.optBoolean("completed"), j.optString("repeat", "none"), j.optBoolean("delivered"),
    runCatching { ReminderKind.valueOf(j.optString("kind")) }.getOrDefault(ReminderKind.TASK),
    j.optBoolean("callStyle", false), j.optLong("anchorAt", j.getLong("triggerAt")),
    j.optInt("retryCount"), j.optString("lastAction", "scheduled"))
private fun JSONObject.optional(key: String) = if (isNull(key) || !has(key)) null else getString(key)
