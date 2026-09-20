package com.gyftalala.omni.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.gyftalala.omni.MainActivity
import com.gyftalala.omni.R
import com.gyftalala.omni.backup.VaultBackup
import com.gyftalala.omni.backup.AccountBackupCipher
import com.gyftalala.omni.BuildConfig
import com.gyftalala.omni.data.OmniStore
import com.gyftalala.omni.data.VaultAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.guava.await
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object CloudTransfers { val mutex = Mutex() }

internal object CloudBackupSchedule {
    const val DAILY = "omni-daily-cloud-backup"
    const val NOW = "omni-cloud-backup-now"
    private fun request(p: CloudPreferences, immediate: Boolean): OneTimeWorkRequest {
        val delay = if (immediate) Duration.ZERO else Duration.between(Instant.now(), DailyBackupTime.next(Instant.now(), p.hour, p.minute))
        return OneTimeWorkRequestBuilder<CloudBackupWorker>()
            .setInputData(workDataOf("uid" to p.uid, "scheduleId" to p.scheduleId, "daily" to !immediate))
            .addTag("omni-backup-${p.uid}")
            .setInitialDelay(delay.toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(!immediate).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
    }
    fun reconcile(context: Context, p: CloudPreferences, replace: Boolean = false) {
        val work = WorkManager.getInstance(context)
        if (!p.configured || !p.enabled || !CloudServices.configured) { work.cancelUniqueWork(DAILY); return }
        work.enqueueUniqueWork(DAILY, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request(p, false))
    }
    suspend fun afterRun(context: Context, p: CloudPreferences) {
        WorkManager.getInstance(context).enqueueUniqueWork(DAILY, ExistingWorkPolicy.APPEND_OR_REPLACE, request(p, false)).result.await()
    }
    fun now(context: Context, p: CloudPreferences) {
        check(p.configured)
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request(p, true))
    }
    fun cancel(context: Context) { WorkManager.getInstance(context).apply { cancelUniqueWork(DAILY); cancelUniqueWork(NOW) } }
    suspend fun cancelRun(context: Context, id: UUID, uid: String, scheduleId: String, daily: Boolean) {
        WorkManager.getInstance(context).cancelWorkById(id).result.await()
        val p = CloudLocal(context).read()
        // Cancel this transfer, not tomorrow's backup. A stale notification must never
        // change a different account or a schedule the user has subsequently edited.
        if (daily && p.uid == uid && p.scheduleId == scheduleId && p.enabled) reconcile(context, p)
    }
}

class CancelCloudBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = runCatching { UUID.fromString(intent.getStringExtra("workId")) }.getOrNull() ?: return
        val uid = intent.getStringExtra("uid") ?: return
        val scheduleId = intent.getStringExtra("scheduleId") ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8_000) { CloudBackupSchedule.cancelRun(context.applicationContext, id, uid, scheduleId, intent.getBooleanExtra("daily", false)) } }
            catch (_: Exception) { /* WorkManager reconciles again on the next app launch. */ }
            finally { pending.finish() }
        }
    }
}

class CloudBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!CloudServices.configured) return@withContext Result.success()
        val local = CloudLocal(applicationContext)
        val uid = inputData.getString("uid") ?: return@withContext Result.success()
        val scheduleId = inputData.getString("scheduleId") ?: return@withContext Result.success()
        val daily = inputData.getBoolean("daily", true)
        fun valid(p: CloudPreferences) = p.uid == uid && p.scheduleId == scheduleId && p.configured && (!daily || p.enabled)
        if (!valid(local.read())) return@withContext Result.success()
        var retry = false
        var phoneSaved = false
        fun status(message: String) { local.update { if (valid(it)) it.copy(backupStatus = message, lastError = "") else it } }
        try {
            withTimeout(9 * 60_000L) {
                setForeground(foreground())
                CloudTransfers.mutex.withLock transfer@{
                    val p = local.read()
                    if (!valid(p)) return@transfer
                    val service = CloudServices.get(applicationContext)
                    service.requireOwner(uid)
                    val repository = CloudRepository(service)
                    val folder = File(applicationContext.noBackupFilesDir, "cloud-transfer").apply { mkdirs() }
                    // No transfer can be live concurrently. Remove ciphertext abandoned by process death.
                    folder.listFiles()?.forEach(File::delete)
                    val file = File(folder, "${UUID.randomUUID()}.omnibak")
                    val empty = OmniStore(applicationContext).use { store ->
                        VaultAccess.mutex.withLock { store.memories().isEmpty() && store.messages().isEmpty() }
                    }
                    if (empty) {
                        status("Nothing to back up yet. Add a note, reminder or file first.")
                        return@transfer
                    }
                    status("Encrypting backup…")
                    val key = CloudAccountKey(applicationContext, service).get(uid)
                    val owner = AccountBackupCipher.ownerTag(BuildConfig.CLOUD_PROJECT_ID, uid)
                    try {
                        OmniStore(applicationContext).use { store ->
                            VaultAccess.mutex.withLock {
                                val active = currentCoroutineContext()
                                file.outputStream().use { out ->
                                    // A skipped restore on an empty installation must not displace good backups.
                                    if (store.memories().isEmpty() && store.messages().isEmpty()) {
                                        status("Nothing to back up yet. Add a note, reminder or file first."); return@transfer
                                    }
                                    VaultBackup(applicationContext, store).writeAccount(out, key, owner, { active.ensureActive() })
                                }
                            }
                        }
                        ensureActive(); service.requireOwner(uid)
                        check(valid(local.read())) { "Backup settings changed. Try again." }
                        val header = file.inputStream().use(AccountBackupCipher::readHeader)
                        status("Saving on this phone…")
                        val active = currentCoroutineContext()
                        MobileBackupCatalog(applicationContext).save(file, owner, header.created) {
                            active.ensureActive(); service.requireOwner(uid); check(valid(local.read()))
                        }
                        phoneSaved = true
                        local.update { if (valid(it)) it.copy(localSuccess = header.created, backupBytes = file.length()) else it }
                        val connectivity = applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
                        val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                        val online = network?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                        if (!online || (p.wifiOnly && connectivity.isActiveNetworkMetered)) {
                            status(if (!online) "Saved on phone. Cloud backup will retry when connected."
                                else "Saved on phone. Cloud backup is waiting for Wi-Fi.")
                            retry = true
                            return@transfer
                        }
                        status("Uploading to cloud…")
                        val saved = repository.upload(uid, file)
                        ensureActive()
                        local.update { if (valid(it)) it.copy(lastSuccess = saved.created, lastError = "", backupStatus = "Backup saved on phone and in cloud.") else it }
                        // Cleanup is optional; failure must never invalidate the complete new backup.
                        try { repository.prune(uid, saved) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                    } finally { key.fill(0); file.delete() }
                }
            }
        } catch (_: TimeoutCancellationException) {
            retry = true
            local.update { if (valid(it)) it.copy(lastError = if (phoneSaved) "Saved on phone. Cloud upload timed out. Retrying automatically." else "Backup timed out. Try again.", backupStatus = "") else it }
        } catch (cancelled: CancellationException) {
            status(if (phoneSaved) "Saved on phone. Cloud backup cancelled." else "Backup cancelled. Previous backups are kept.")
            throw cancelled
        }
        catch (_: Exception) {
            retry = true
            local.update { if (valid(it)) it.copy(lastError = if (phoneSaved) "Saved on phone. Cloud upload failed. Check your connection and retry." else "Backup failed. Check storage and connection, then retry.", backupStatus = "") else it }
        }
        if (retry && runAttemptCount < 4) return@withContext Result.retry()
        val current = local.read()
        if (daily && valid(current)) CloudBackupSchedule.afterRun(applicationContext, current)
        Result.success()
    }
    private fun foreground(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Encrypted backups", NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(applicationContext, 802, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getBroadcast(applicationContext, id.hashCode(),
            Intent(applicationContext, CancelCloudBackupReceiver::class.java).setAction("omni.cancel.backup.$id")
                .putExtra("workId", id.toString()).putExtra("uid", inputData.getString("uid"))
                .putExtra("scheduleId", inputData.getString("scheduleId")).putExtra("daily", inputData.getBoolean("daily", true)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_omni)
            .setContentTitle("Protecting your Omni backup").setContentText("Encrypting and saving your latest changes")
            .setContentIntent(intent).setOngoing(true).setSilent(true)
            .addAction(0, "Cancel", cancel).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(802, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(802, notification)
    }
    companion object { private const val CHANNEL = "omni-encrypted-backups" }
}
