package com.gyftalala.omni.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gyftalala.omni.MainActivity
import com.gyftalala.omni.R
import com.gyftalala.omni.data.ChatMessage
import com.gyftalala.omni.data.OmniStore
import com.gyftalala.omni.data.Reminder
import com.gyftalala.omni.data.Role
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    fun exactAllowed() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    fun notificationsAllowed(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    private fun pending(id: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setData(Uri.parse("omni://reminder/$id")).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
    }
    fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Personal reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tasks saved in Omni bot AI"; lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            })
    }
    companion object { const val CHANNEL = "omni-reminders-v1" }
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
                    val open = PendingIntent.getActivity(context, 0,
                        Intent(context, MainActivity::class.java).setData(Uri.parse("omni://memory/$id")).putExtra("memoryId", id),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    if (scheduler.notificationsAllowed()) {
                        val public = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL).setSmallIcon(R.drawable.ic_omni)
                            .setContentTitle("Omni bot AI").setContentText("A personal reminder is ready.").build()
                        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL)
                            .setSmallIcon(R.drawable.ic_omni).setContentTitle("Time for your reminder")
                            .setContentText(reminder.title).setStyle(NotificationCompat.BigTextStyle().bigText(reminder.title))
                            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
                        try { context.getSystemService(NotificationManager::class.java).notify(id, 0, notification) }
                        catch (_: SecurityException) { /* Inbox delivery still persists. */ }
                    }
                    store.transaction {
                        store.save(ChatMessage("delivery:$id:${reminder.triggerAt}", Role.ASSISTANT, "Reminder: ${reminder.title}",
                            System.currentTimeMillis(), attachmentId = id))
                        if (reminder.repeat == "daily") {
                            var next = Instant.ofEpochMilli(reminder.triggerAt).atZone(ZoneId.systemDefault()).plusDays(1)
                            while (next.toInstant().toEpochMilli() <= System.currentTimeMillis()) next = next.plusDays(1)
                            val following = reminder.copy(triggerAt = next.toInstant().toEpochMilli())
                            store.save(following); scheduler.schedule(following)
                        } else store.save(reminder.copy(delivered = true))
                    }
                    context.sendBroadcast(Intent("com.gyftalala.omni.CHANGED").setPackage(context.packageName))
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
