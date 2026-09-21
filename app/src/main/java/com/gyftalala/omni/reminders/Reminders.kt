package com.gyftalala.omni.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gyftalala.omni.R
import com.gyftalala.omni.data.OmniStore
import com.gyftalala.omni.data.Reminder
import java.util.concurrent.Executors

class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    fun exactAllowed() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    fun fullScreenAllowed() = Build.VERSION.SDK_INT < 34 ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    fun notificationsAllowed(channel: String = CALL_CHANNEL): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    private fun pending(id: String): android.app.PendingIntent = android.app.PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setData(Uri.parse("omni://reminder/$id")).putExtra("id", id),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
    fun schedule(reminder: Reminder): Boolean {
        val trigger = maxOf(System.currentTimeMillis() + 1000, reminder.triggerAt)
        if (exactAllowed()) {
            try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending(reminder.id)); return true }
            catch (_: SecurityException) { /* Permission can be revoked between check and scheduling. */ }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending(reminder.id))
        return false
    }
    fun cancel(id: String) {
        val intent = pending(id)
        alarms.cancel(intent)
        intent.cancel()
        context.getSystemService(NotificationManager::class.java).cancel(id, 0)
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(id))
    }
    fun createChannel() {
        context.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CHANNEL, "Personal reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tasks saved in Omni bot AI"; lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            })
            createNotificationChannel(NotificationChannel(CALL_CHANNEL, "Reminder calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Call-style alerts for reminders saved in Omni bot AI"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                setSound(null, null)
                enableVibration(false)
            })
        }
    }
    companion object {
        const val CHANNEL = "omni-reminders-v1"
        const val CALL_CHANNEL = "omni-reminder-calls-v1"
        fun notificationId(id: String) = id.hashCode() and 0x7fffffff
    }
}

private val receiverExecutor = Executors.newSingleThreadExecutor()

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val pending = goAsync()
        receiverExecutor.execute {
            try {
                OmniStore(context).use { store ->
                    val reminder = store.reminders().firstOrNull { it.id == id && !it.completed } ?: return@use
                    if (reminder.triggerAt > System.currentTimeMillis() + 2000 || reminder.delivered) return@use
                    val scheduler = ReminderScheduler(context).apply { createChannel() }
                    val delivered = ReminderActions.delivered(context, reminder)
                    if (delivered.callStyle && scheduler.notificationsAllowed()) {
                        runCatching {
                            ContextCompat.startForegroundService(context,
                                Intent(context, ReminderCallService::class.java).setAction(ReminderCallService.ACTION_RING)
                                    .putExtra(ReminderCallService.EXTRA_ID, id))
                        }.onFailure { ReminderActions.postpone(context, id, 5, "delivery_failed") }
                    } else if (delivered.callStyle) {
                        // Keep recurring schedules alive even when Android blocks the call UI.
                        ReminderRecurrence.next(delivered.anchorAt, delivered.repeat)?.let { next ->
                            val following = delivered.copy(triggerAt = next, anchorAt = next, delivered = false)
                            store.save(following); scheduler.schedule(following)
                        }
                    } else {
                        val open = android.app.PendingIntent.getActivity(context, 0,
                            Intent(context, com.gyftalala.omni.MainActivity::class.java).setData(Uri.parse("omni://memory/$id")).putExtra("memoryId", id),
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                        if (scheduler.notificationsAllowed(ReminderScheduler.CHANNEL)) {
                            val public = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL).setSmallIcon(R.drawable.ic_omni)
                                .setContentTitle("Omni bot AI").setContentText("A personal reminder is ready.").build()
                            val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL)
                                .setSmallIcon(R.drawable.ic_omni).setContentTitle("Time for your reminder")
                                .setContentText(delivered.title).setStyle(NotificationCompat.BigTextStyle().bigText(delivered.title))
                                .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
                                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
                            try { context.getSystemService(NotificationManager::class.java).notify(id, 0, notification) }
                            catch (_: SecurityException) { /* Inbox delivery still persists. */ }
                        }
                        val next = ReminderRecurrence.next(delivered.anchorAt, delivered.repeat)
                        if (next != null) {
                            val following = delivered.copy(triggerAt = next, anchorAt = next, delivered = false)
                            store.save(following); scheduler.schedule(following)
                        }
                    }
                }
            } finally { pending.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        receiverExecutor.execute {
            try {
                runCatching {
                    val preferences = com.gyftalala.omni.cloud.CloudLocal(context).read()
                    com.gyftalala.omni.cloud.CloudBackupSchedule.reconcile(context, preferences,
                        replace = intent.action in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED))
                }
                OmniStore(context).use { store ->
                    val scheduler = ReminderScheduler(context).apply { createChannel() }
                    store.reminders().filter { !it.completed && !it.delivered }.forEach(scheduler::schedule)
                }
            } finally { pending.finish() }
        }
    }
}
