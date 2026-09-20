package com.gyftalala.omni.cloud

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.credentials.CredentialManager
import androidx.credentials.ClearCredentialStateRequest
import com.gyftalala.omni.BuildConfig
import com.gyftalala.omni.backup.*
import com.gyftalala.omni.data.OmniStore
import com.gyftalala.omni.data.VaultAccess
import com.gyftalala.omni.reminders.ReminderScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal enum class CloudStep { HOME, AVAILABLE, WORKING, DONE }
internal data class BackupCandidate(val created: Long, val label: String, val remote: RemoteBackup? = null, val mobile: MobileBackup? = null)
internal data class CloudState(
    val step: CloudStep = CloudStep.HOME, val configured: Boolean = CloudServices.configured,
    val ready: Boolean = false, val onboarding: Boolean = false, val enterApp: Boolean = false,
    val accountEmail: String? = null, val enabled: Boolean = false, val keyReady: Boolean = false,
    val hour: Int = 3, val minute: Int = 0, val wifiOnly: Boolean = true,
    val lastSuccess: Long = 0, val error: String? = null, val status: String = "",
    val remote: RemoteBackup? = null, val preview: BackupPreview? = null, val prompt: Boolean = false,
    val candidates: List<BackupCandidate> = emptyList(), val source: String = "", val ignored: Int = 0,
    val cloudChecked: Boolean = false, val mobileCount: Int = 0,
    val localSuccess: Long = 0, val backupBytes: Long = 0, val backupStatus: String = "", val backingUp: Boolean = false,
)

internal class CloudBackupController(private val context: Context, private val store: OmniStore,
    private val scope: CoroutineScope, private val changed: () -> Unit) {
    private val local = CloudLocal(context)
    private val mobile = MobileBackupCatalog(context)
    private val mutable = MutableStateFlow(CloudState())
    val state = mutable.asStateFlow()
    private class Operation(var job: Job? = null, var staged: PreparedRestore? = null, var uid: String? = null,
        var destination: Uri? = null, @Volatile var stream: Closeable? = null)
    @Volatile private var operation = Operation()
    private var discoveredUid: String? = null
    private var suppressPrompt = false
    private val service get() = CloudServices.get(context)
    private val repository get() = CloudRepository(service)
    private val keys get() = CloudAccountKey(context, service)
    private val backup = VaultBackup(context, store)
    init {
        scope.launch { CloudLocal.changes.drop(1).collect { refresh() } }
        scope.launch {
            val work = WorkManager.getInstance(context)
            combine(work.getWorkInfosForUniqueWorkFlow(CloudBackupSchedule.NOW),
                work.getWorkInfosForUniqueWorkFlow(CloudBackupSchedule.DAILY)) { immediate, daily ->
                val uid = if (CloudServices.configured) service.owner?.uid else null
                immediate.any { !it.state.isFinished && it.runAttemptCount == 0 && "omni-backup-$uid" in it.tags } ||
                    daily.any { it.state == WorkInfo.State.RUNNING && "omni-backup-$uid" in it.tags }
            }.collect { active ->
                mutable.value = mutable.value.copy(backingUp = active)
                refresh()
            }
        }
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            try {
                val p = local.read()
                val user = if (CloudServices.configured) service.owner else null
                mutable.value = mutable.value.copy(ready = true, onboarding = !p.onboardingSeen,
                    accountEmail = user?.email, enabled = p.enabled && p.uid == user?.uid,
                    keyReady = p.configured && p.uid == user?.uid, hour = p.hour, minute = p.minute, wifiOnly = p.wifiOnly,
                    lastSuccess = if (p.uid == user?.uid) p.lastSuccess else 0,
                    localSuccess = if (p.uid == user?.uid) p.localSuccess else 0,
                    backupBytes = if (p.uid == user?.uid) p.backupBytes else 0,
                    backupStatus = if (p.uid == user?.uid) p.backupStatus else "",
                    error = if (operation.job?.isActive == true) mutable.value.error else p.lastError.takeIf(String::isNotBlank))
                if (user == null && CloudServices.configured && !p.onboardingSeen) mutable.value = mutable.value.copy(prompt = true)
                if (user != null && user.uid == p.uid) CloudBackupSchedule.reconcile(context, p)
                if (user != null && operation.job?.isActive != true &&
                    (discoveredUid != user.uid || (!p.onboardingSeen && mutable.value.step == CloudStep.HOME && !mutable.value.cloudChecked))) {
                    discover(inspect = !p.onboardingSeen, prompt = !p.onboardingSeen && !suppressPrompt)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(ready = true, error = "Could not read backup settings. Unlock Omni and try again.") }
        }
    }
    fun signedIn() {
        discoveredUid = null; suppressPrompt = false
        mutable.value = mutable.value.copy(ready = true, accountEmail = service.owner?.email, onboarding = !local.read().onboardingSeen)
        discover(inspect = true, prompt = true, refreshKey = true)
    }
    fun discover(inspect: Boolean = false, prompt: Boolean = false, refreshKey: Boolean = false) {
        if (!CloudServices.configured || service.owner == null) { refresh(); return }
        val uid = service.owner!!.uid
        run("Checking your backups…") { current ->
            val owner = AccountBackupCipher.ownerTag(BuildConfig.CLOUD_PROJECT_ID, uid)
            val key = withTimeout(20_000) { keys.get(uid, refreshKey) }
            try {
                val active = currentCoroutineContext()
                val localFiles = mobile.scan(owner) { active.ensureActive() }
                var ignored = localFiles.ignored
                var cloudChecked = false
                val versions = try { withTimeout(20_000) { repository.list(uid) }.also { cloudChecked = true } }
                    catch (_: TimeoutCancellationException) { emptyList() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                val candidates = localFiles.files.map { BackupCandidate(it.created, "Phone · ${it.name}", mobile = it) }.toMutableList()
                for (remote in versions) {
                    active.ensureActive()
                    try {
                        val header = withTimeout(10_000) { repository.header(uid, remote) }
                        if (MessageDigest.isEqual(header.owner, owner)) candidates += BackupCandidate(header.created, "Cloud", remote = remote)
                        else ignored++
                    } catch (_: TimeoutCancellationException) { ignored++ }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { ignored++ }
                }
                val sorted = candidates.sortedWith(compareByDescending<BackupCandidate> { it.created }.thenBy { if (it.remote != null) 0 else 1 })
                service.requireOwner(uid)
                discoveredUid = uid
                var next = mutable.value.copy(ready = true, accountEmail = service.owner?.email,
                    onboarding = !local.read().onboardingSeen, candidates = sorted, remote = versions.firstOrNull(),
                    cloudChecked = cloudChecked, mobileCount = localFiles.files.size, ignored = ignored,
                    preview = null, source = "", prompt = prompt, step = CloudStep.HOME,
                    status = if (cloudChecked) "No backup found." else "Cloud could not be checked. Try again when connected.",
                    error = if (!cloudChecked) "Could not check cloud storage. Selected phone backups are still available." else null)
                if (inspect) {
                    VaultAccess.mutex.withLock { backup.discard(current.staged); current.staged = null }
                    for (candidate in sorted) {
                        active.ensureActive()
                        try {
                            val staged = prepareCandidate(uid, candidate, key, owner, current)
                            val m = staged.manifest
                            next = next.copy(step = CloudStep.AVAILABLE, source = candidate.label,
                                preview = BackupPreview(m.created, m.memories.size, m.messages.size, m.files.size, m.reminders.size, m.bytes),
                                status = "Newest verified backup", ignored = ignored)
                            break
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { ignored++ }
                    }
                    if (next.preview == null && sorted.isNotEmpty()) next = next.copy(ignored = ignored,
                        error = "No selected backup could be verified. Existing items and backup files are kept.")
                }
                if (inspect && (next.onboarding || prompt) && cloudChecked && sorted.isEmpty() && ignored == 0) {
                    val enabled = finishSetup(uid, immediate = false)
                    next = next.copy(onboarding = false, prompt = false, enterApp = true, keyReady = enabled, enabled = enabled, status = "")
                }
                next
            } finally { key.fill(0) }
        }
    }
    private suspend fun prepareCandidate(uid: String, candidate: BackupCandidate, key: ByteArray, owner: ByteArray, current: Operation): PreparedRestore =
        CloudTransfers.mutex.withLock {
            current.uid = uid
            val folder = File(context.noBackupFilesDir, "cloud-transfer").apply { mkdirs() }
            val file = File(folder, "${UUID.randomUUID()}.omnibak")
            try {
                val input = if (candidate.remote != null) {
                    repository.download(uid, candidate.remote, file); file.inputStream()
                } else context.contentResolver.openInputStream(checkNotNull(candidate.mobile).uri) ?: error("Backup unavailable")
                current.stream = input
                try {
                    input.use {
                        VaultAccess.mutex.withLock {
                            val active = currentCoroutineContext()
                            backup.prepareAccount(it, key, owner, { active.ensureActive(); service.requireOwner(uid) }) { message ->
                                mutable.value = mutable.value.copy(status = message)
                            }.also { prepared -> current.staged = prepared }
                        }
                    }
                } finally { current.stream = null }
            } finally { file.delete() }
        }
    fun chooseMobile(uris: List<Uri>) {
        if (uris.isEmpty()) return
        cancel()
        scope.launch(Dispatchers.IO) {
            try { mobile.remember(uris); discover(inspect = true, prompt = mutable.value.onboarding) }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = "Choose up to 10 Omni backup files.") }
        }
    }
    private suspend fun enableAccount(uid: String, immediate: Boolean): CloudPreferences {
        val key = withTimeout(20_000) { keys.get(uid) }; key.fill(0)
        service.requireOwner(uid)
        val p = local.update { it.copy(uid = uid, accountKeyReady = true, enabled = true,
            scheduleId = UUID.randomUUID().toString(), lastError = "", onboardingSeen = true) }
        CloudBackupSchedule.reconcile(context, p, replace = true)
        if (immediate) CloudBackupSchedule.now(context, p)
        return p
    }
    private suspend fun finishSetup(uid: String, immediate: Boolean): Boolean {
        return try { enableAccount(uid, immediate); true }
        catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            service.requireOwner(uid)
            local.update { it.copy(uid = uid, enabled = false, onboardingSeen = true,
                lastError = "Daily backup needs a connection. Enable it in Settings when connected.") }
            CloudBackupSchedule.cancel(context)
            false
        }
    }
    fun skipRestore() {
        val uid = if (CloudServices.configured) service.owner?.uid else null
        if (uid == null) return
        run("Finishing setup…") { current ->
            VaultAccess.mutex.withLock { backup.discard(current.staged); current.staged = null }
            val enabled = finishSetup(uid, immediate = true)
            mutable.value.copy(step = CloudStep.HOME, preview = null, onboarding = false, prompt = false, enterApp = true,
                keyReady = enabled, enabled = enabled, status = if (enabled) "Daily backup is on. Existing cloud backups are kept." else "Setup finished. Enable daily backup when connected.")
        }
    }
    fun enteredApp() { mutable.value = mutable.value.copy(enterApp = false) }
    fun restore() {
        val staged = operation.staged ?: return
        val uid = operation.uid ?: return
        run("Restoring your saved items…") { current ->
            val result = VaultAccess.mutex.withLock {
                service.requireOwner(uid)
                val active = currentCoroutineContext()
                backup.commit(staged) { active.ensureActive() }.also { current.staged = null }
            }
            val enabled = finishSetup(uid, immediate = false)
            val scheduler = ReminderScheduler(context)
            runCatching { scheduler.createChannel(); store.reminders().filter { !it.completed && !it.delivered }.forEach(scheduler::schedule) }
            changed()
            mutable.value.copy(step = CloudStep.DONE, keyReady = enabled, enabled = enabled, preview = null, prompt = false, onboarding = false,
                status = "Restored ${result.memories} items and ${result.messages} messages. ${result.skipped} existing items kept. " + if (enabled) "Daily backup is on." else "Enable daily backup when connected.")
        }
    }
    fun enableDaily() {
        val uid = service.owner?.uid ?: return
        run("Enabling daily backup…") {
            enableAccount(uid, immediate = true)
            mutable.value.copy(step = CloudStep.HOME, keyReady = true, enabled = true, onboarding = false,
                status = "Daily backup is on. First backup queued.")
        }
    }
    fun settings(enabled: Boolean, hour: Int, minute: Int, wifiOnly: Boolean) {
        run("Saving backup settings…", inline = true) {
            val old = local.read(); service.requireOwner(old.uid)
            val p = local.update { require(!enabled || it.configured); it.copy(enabled = enabled, hour = hour, minute = minute,
                wifiOnly = wifiOnly, scheduleId = UUID.randomUUID().toString()) }
            CloudBackupSchedule.cancel(context); CloudBackupSchedule.reconcile(context, p, replace = true)
            mutable.value.copy(step = CloudStep.HOME, enabled = enabled, hour = hour, minute = minute, wifiOnly = wifiOnly, status = "Settings saved.")
        }
    }
    fun backUpNow() {
        if (mutable.value.backingUp) return
        run("Starting backup…", inline = true) {
            val p = local.read(); service.requireOwner(p.uid)
            // An explicit retry replaces a waiting retry, so the button starts work now.
            WorkManager.getInstance(context).cancelUniqueWork(CloudBackupSchedule.NOW).result.get()
            local.update { it.copy(backupStatus = "Starting backup…", lastError = "") }
            CloudBackupSchedule.now(context, p)
            mutable.value.copy(step = CloudStep.HOME, backingUp = true, backupStatus = "Starting backup…", status = "")
        }
    }
    fun exportFile(uri: Uri) {
        val uid = service.owner?.uid ?: return
        run("Saving encrypted backup…") { current ->
            current.destination = uri
            val key = withTimeout(20_000) { keys.get(uid) }
            try {
                CloudTransfers.mutex.withLock {
                    VaultAccess.mutex.withLock {
                        val active = currentCoroutineContext()
                        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot write backup")
                        current.stream = output
                        try { output.use { backup.writeAccount(it, key, AccountBackupCipher.ownerTag(BuildConfig.CLOUD_PROJECT_ID, uid),
                            { active.ensureActive(); service.requireOwner(uid) }) } } finally { current.stream = null }
                    }
                }
                mobile.remember(listOf(uri)); current.destination = null
                mutable.value.copy(step = CloudStep.DONE, status = "Encrypted backup saved. Use this Google account to restore it.")
            } finally { key.fill(0) }
        }
    }
    fun signOut() {
        cancel(); suppressPrompt = true
        run("Signing out…") {
            local.update { CloudPreferences(hour = it.hour, minute = it.minute, wifiOnly = it.wifiOnly, onboardingSeen = true) }
            CloudBackupSchedule.cancel(context)
            if (CloudServices.configured) { service.auth.signOut(); keys.clear() }
            try { withTimeout(5_000) { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) } }
            catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled }
            catch (_: Exception) { }
            discoveredUid = null
            CloudState(ready = true, status = "Signed out. Local items and cloud backups are kept.")
        }
    }
    fun dismissPrompt() { suppressPrompt = true; mutable.value = mutable.value.copy(prompt = false) }
    fun cancel() {
        val previous = operation; operation = Operation(); previous.job?.cancel()
        mutable.value = mutable.value.copy(step = CloudStep.HOME, prompt = false, preview = null, status = "", error = null)
        scope.launch(Dispatchers.IO) {
            runCatching { previous.stream?.close() }; previous.job?.join()
            VaultAccess.mutex.withLock { backup.discard(previous.staged); previous.staged = null }
            previous.destination?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
        }
    }
    @Synchronized private fun run(status: String, inline: Boolean = false, block: suspend (Operation) -> CloudState) {
        val current = operation
        if (current.job?.isActive == true) return
        mutable.value = mutable.value.copy(step = if (inline) CloudStep.HOME else CloudStep.WORKING, status = if (inline) "" else status, error = null)
        current.job = scope.launch(Dispatchers.IO) {
            try {
                val result = withTimeout(9 * 60_000L) { block(current) }; ensureActive()
                if (operation === current) mutable.value = result
            } catch (_: TimeoutCancellationException) {
                if (operation === current) mutable.value = mutable.value.copy(step = CloudStep.HOME, ready = true,
                    error = "Backup check timed out. Try again when connected, or skip restore for now. Existing backups are kept.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (operation === current) mutable.value = mutable.value.copy(step = CloudStep.HOME, ready = true,
                    error = "Could not complete backup setup. Check your connection and try again. Existing backups are kept.")
            }
        }
    }
}
