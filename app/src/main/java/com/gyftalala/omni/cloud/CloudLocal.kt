package com.gyftalala.omni.cloud

import android.content.Context
import android.util.AtomicFile
import com.gyftalala.omni.security.Vault
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

internal data class CloudPreferences(
    val uid: String = "", val accountKeyReady: Boolean = false, val enabled: Boolean = false,
    val hour: Int = 3, val minute: Int = 0, val wifiOnly: Boolean = true,
    val scheduleId: String = UUID.randomUUID().toString(), val lastSuccess: Long = 0,
    val lastError: String = "", val onboardingSeen: Boolean = false,
    val localSuccess: Long = 0, val backupBytes: Long = 0, val backupStatus: String = "",
) {
    val configured get() = uid.isNotBlank() && accountKeyReady
}

/** Separate from portable records. The account backup settings is wrapped by this installation's vault key. */
internal class CloudLocal(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "cloud-backup-settings"))
    private val vault = Vault(context)
    fun read(): CloudPreferences = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized CloudPreferences()
        val bytes = vault.decrypt(file.readFully(), PURPOSE)
        try {
            val j = JSONObject(String(bytes, Charsets.UTF_8))
            CloudPreferences(j.optString("uid"), j.optBoolean("accountKeyReady"), j.optBoolean("enabled"),
                j.optInt("hour", 3).coerceIn(0, 23), j.optInt("minute", 0).coerceIn(0, 59), j.optBoolean("wifiOnly", true),
                j.getString("scheduleId"), j.optLong("lastSuccess"), j.optString("lastError"), j.optBoolean("onboardingSeen"),
                j.optLong("localSuccess"), j.optLong("backupBytes"), j.optString("backupStatus"))
        } finally { bytes.fill(0) }
    }
    fun update(change: (CloudPreferences) -> CloudPreferences): CloudPreferences = synchronized(lock) {
        val p = change(read())
        require(p.hour in 0..23 && p.minute in 0..59)
        val j = JSONObject().put("uid", p.uid).put("accountKeyReady", p.accountKeyReady).put("enabled", p.enabled)
            .put("hour", p.hour).put("minute", p.minute).put("wifiOnly", p.wifiOnly).put("scheduleId", p.scheduleId)
            .put("localSuccess", p.localSuccess).put("backupBytes", p.backupBytes).put("backupStatus", p.backupStatus)
            .put("lastSuccess", p.lastSuccess).put("lastError", p.lastError).put("onboardingSeen", p.onboardingSeen)
        val plain = j.toString().toByteArray()
        val cipher = try { vault.encrypt(plain, PURPOSE) } finally { plain.fill(0) }
        val out = file.startWrite()
        try { out.write(cipher); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
        changes.value += 1
        p
    }
    companion object {
        private val lock = Any()
        private const val PURPOSE = "cloud-backup-settings:v1"
        val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    }
}

internal object DailyBackupTime {
    /** Recomputed in the current zone after each run. DST gaps move forward; overlaps run once. */
    fun next(now: Instant, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Instant {
        require(hour in 0..23 && minute in 0..59)
        val today = now.atZone(zone).toLocalDate()
        val time = LocalTime.of(hour, minute)
        val candidate = today.atTime(time).atZone(zone).toInstant()
        return if (candidate.isAfter(now)) candidate else today.plusDays(1).atTime(time).atZone(zone).toInstant()
    }
}
