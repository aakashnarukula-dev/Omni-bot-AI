package com.gyftalala.omni.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.gyftalala.omni.data.OmniStore
import com.gyftalala.omni.reminders.ReminderScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

internal enum class BackupStep { HOME, CREATE_PASSWORD, RESTORE_PASSWORD, WORKING, PREVIEW, DONE }
internal data class BackupPreview(val created: Long, val items: Int, val messages: Int, val files: Int, val reminders: Int, val bytes: Long)
internal data class BackupState(val step: BackupStep = BackupStep.HOME, val status: String = "", val error: String? = null,
    val preview: BackupPreview? = null)

/** Password/URI/preview remain in memory only, never SavedStateHandle or settings. */
internal class BackupController(
    private val context: Context,
    private val store: OmniStore,
    private val scope: CoroutineScope,
    private val exclusive: suspend (suspend () -> Unit) -> Unit,
) {
    private val backup = VaultBackup(context, store)
    private val mutable = MutableStateFlow(BackupState())
    val state = mutable.asStateFlow()
    private class Session(var destination: Uri? = null, var source: Uri? = null,
        var prepared: PreparedRestore? = null, var job: Job? = null) {
        val signal = android.os.CancellationSignal()
        @Volatile var stream: java.io.Closeable? = null
    }
    @Volatile private var session = Session()
    private var recovered = false
    fun recoverPending() { if (!recovered) { backup.recover(); recovered = true } }

    fun chooseCreate(uri: Uri) { reset(); session.destination = uri; mutable.value = BackupState(BackupStep.CREATE_PASSWORD) }
    fun chooseRestore(uri: Uri) { reset(); session.source = uri; mutable.value = BackupState(BackupStep.RESTORE_PASSWORD) }

    fun create(password: CharArray) {
        val current = session
        val uri = current.destination ?: run { password.fill('\u0000'); return }
        start(password, BackupStep.CREATE_PASSWORD) { checkActive, report ->
            val output = context.contentResolver.openAssetFileDescriptor(uri, "wt", current.signal)?.createOutputStream()
                ?: throw IOException("Cannot write to this location.")
            current.stream = output
            try { output.use { backup.write(it, password, checkActive, report) } } finally { current.stream = null }
            current.destination = null
            BackupState(BackupStep.DONE, "Backup saved. Keep its password somewhere safe, separate from the file.")
        }
    }
    fun inspect(password: CharArray) {
        val current = session
        val uri = current.source ?: run { password.fill('\u0000'); return }
        start(password, BackupStep.RESTORE_PASSWORD) { checkActive, report ->
            val input = context.contentResolver.openAssetFileDescriptor(uri, "r", current.signal)?.createInputStream()
                ?: throw IOException("Cannot read this backup.")
            current.stream = input
            val verified = try { input.use { backup.prepare(it, password, checkActive, report) } } finally { current.stream = null }
            current.prepared = verified
            checkActive()
            val m = verified.manifest
            BackupState(BackupStep.PREVIEW, preview = BackupPreview(m.created, m.memories.size, m.messages.size, m.files.size, m.reminders.size, m.bytes))
        }
    }
    fun restore() {
        val current = session
        val staged = current.prepared ?: return
        start(null, BackupStep.HOME) { checkActive, report ->
            report("Restoring saved items…")
            val result = backup.commit(staged, checkActive)
            current.prepared = null
            // The transaction is committed. Scheduling failures must not turn a successful restore into a failure.
            val scheduler = ReminderScheduler(context)
            val scheduling = runCatching {
                scheduler.createChannel()
                store.reminders().filter { !it.completed && !it.delivered }.forEach(scheduler::schedule)
            }
            val reminderNotice = if (scheduling.isFailure) " Open Omni again to retry scheduling reminders."
                else if (!scheduler.notificationsAllowed() || !scheduler.exactAllowed()) " Check Notifications and Precise reminders in Settings."
                else " Future reminders are scheduled."
            BackupState(BackupStep.DONE, "Restored ${result.memories} items and ${result.messages} messages. ${result.skipped} existing items kept.$reminderNotice")
        }
    }
    private fun start(password: CharArray?, retry: BackupStep,
        action: suspend (() -> Unit, (String) -> Unit) -> BackupState) {
        val current = session
        if (current.job?.isActive == true) { password?.fill('\u0000'); return }
        mutable.value = BackupState(BackupStep.WORKING, "Preparing…")
        current.job = scope.launch(Dispatchers.IO) {
            try {
                var finished: BackupState? = null
                exclusive {
                    val active = currentCoroutineContext()
                    val checkActive = { active.ensureActive(); check(current === session) { "Backup operation cancelled." } }
                    checkActive()
                    recoverPending()
                    finished = action(checkActive) { message ->
                        checkActive(); mutable.value = BackupState(BackupStep.WORKING, message)
                    }
                }
                if (current === session) mutable.value = finished!!
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current === session) mutable.value = BackupState(retry, error = when {
                    retry == BackupStep.RESTORE_PASSWORD -> when (error.message) {
                        "Not enough free storage to restore this backup." -> "Not enough free storage. Free some space and try again. Nothing was restored."
                        "This backup needs a newer version of Omni." -> "Update Omni before restoring this backup. Nothing was restored."
                        "Choose an Omni .omnibak backup file." -> "Choose an Omni .omnibak backup file. Nothing was restored."
                        else -> "Could not verify this backup. Check the password and choose the complete, unchanged file. Nothing was restored."
                    }
                    retry == BackupStep.CREATE_PASSWORD -> "Backup was not saved. Check free space and destination access, then try again."
                    else -> "Restore was not completed. Your existing items are unchanged. Choose the backup again."
                })
            } finally { password?.fill('\u0000') }
        }
        // Also covers a job cancelled while still queued, before its try/finally can begin.
        current.job?.invokeOnCompletion { password?.fill('\u0000') }
    }

    /** Called on cancel, ordinary background lock, and leaving the backup page. */
    fun reset() {
        val previous = session
        session = Session()
        previous.job?.cancel()
        mutable.value = BackupState()
        scope.launch(Dispatchers.IO) {
            runCatching { previous.signal.cancel(); previous.stream?.close() }
            previous.job?.join()
            exclusive {
                // Cleanup owns its old session, so a newer selection cannot be discarded.
                runCatching { backup.discard(previous.prepared) }; previous.prepared = null
                previous.destination?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            }
        }
    }
}
