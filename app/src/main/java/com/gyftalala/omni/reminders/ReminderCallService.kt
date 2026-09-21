package com.gyftalala.omni.reminders

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.gyftalala.omni.R
import com.gyftalala.omni.data.OmniStore
import com.gyftalala.omni.data.Reminder
import java.util.Locale
import java.util.concurrent.Executors

class ReminderCallService : Service(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private var reminder: Reminder? = null
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var speech: TextToSpeech? = null
    private var speechReady = false
    private var answered = false
    private val missed = Runnable {
        reminder?.let { ReminderActions.postpone(this, it.id, 5, "missed", it.triggerAt) }
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        ReminderScheduler(this).createChannel()
        vibrator = getSystemService(Vibrator::class.java)
        speech = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> ring(intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY)
            ACTION_ANSWER -> answer(intent.getStringExtra(EXTRA_ID))
            ACTION_SILENCE -> silence()
        }
        return START_NOT_STICKY
    }

    private fun ring(id: String) {
        val incoming = OmniStore(this).use { store -> store.reminders().firstOrNull { it.id == id && !it.completed } } ?: run {
            stopSelf(); return
        }
        if (getSystemService(AudioManager::class.java).mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
            ReminderActions.postpone(this, id, 5, "busy", incoming.triggerAt)
            stopSelf(); return
        }
        if (reminder != null && reminder?.id != id) {
            ReminderActions.postpone(this, id, 5, "busy", incoming.triggerAt)
            return
        }
        reminder = incoming
        answered = false
        startForeground(ReminderScheduler.notificationId(id), notification(incoming, true))
        startSound()
        handler.removeCallbacks(missed)
        handler.postDelayed(missed, RING_TIMEOUT_MS)
    }

    private fun answer(id: String?) {
        val current = reminder?.takeIf { id == null || it.id == id } ?: return
        answered = true
        handler.removeCallbacks(missed)
        silence()
        startForeground(ReminderScheduler.notificationId(current.id), notification(current, false))
        if (speechReady) speech?.speak("Reminder. ${current.title}", TextToSpeech.QUEUE_FLUSH, null, "omni-reminder")
        handler.postDelayed(missed, RING_TIMEOUT_MS)
    }

    private fun startSound() {
        silence()
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@ReminderCallService, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                isLooping = true
                prepare()
                start()
            }
        }
        val pattern = longArrayOf(0, 700, 500, 700)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            else @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
        }
    }

    private fun silence() {
        runCatching { player?.stop() }
        player?.release(); player = null
        vibrator?.cancel()
    }

    private fun notification(value: Reminder, incoming: Boolean): Notification {
        val fullScreen = PendingIntent.getActivity(this, ReminderScheduler.notificationId(value.id),
            Intent(this, ReminderCallActivity::class.java).putExtra(EXTRA_ID, value.id)
                .putExtra(EXTRA_TITLE, value.title).putExtra(EXTRA_KIND, value.kind.label).putExtra(EXTRA_TRIGGER_AT, value.triggerAt)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val done = action(value.id, ReminderActionReceiver.ACTION_DONE, 0)
        val later = action(value.id, ReminderActionReceiver.ACTION_SNOOZE, 15)
        val public = NotificationCompat.Builder(this, ReminderScheduler.CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_omni).setContentTitle("Omni bot AI")
            .setContentText("A personal reminder is ready.").build()
        return NotificationCompat.Builder(this, ReminderScheduler.CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_omni)
            .setContentTitle(if (incoming) "Incoming ${value.kind.label.lowercase()} reminder" else value.kind.label)
            .setContentText(value.title).setStyle(NotificationCompat.BigTextStyle().bigText(value.title))
            .setContentIntent(fullScreen).setFullScreenIntent(fullScreen, incoming)
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setOngoing(true).setAutoCancel(false)
            .addAction(0, "Done", done).addAction(0, "15 min", later).build()
    }

    private fun action(id: String, action: String, minutes: Int): PendingIntent = PendingIntent.getBroadcast(this,
        "$id:$action:$minutes".hashCode(), Intent(this, ReminderActionReceiver::class.java).setAction(action)
            .setData(android.net.Uri.parse("omni://reminder-action/$id/$minutes"))
            .putExtra(EXTRA_ID, id).putExtra(EXTRA_MINUTES, minutes).putExtra(EXTRA_TRIGGER_AT, reminder?.triggerAt ?: 0L),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    override fun onInit(status: Int) {
        speechReady = status == TextToSpeech.SUCCESS
        if (speechReady) {
            speech?.language = Locale.getDefault()
            if (answered) reminder?.let { speech?.speak("Reminder. ${it.title}", TextToSpeech.QUEUE_FLUSH, null, "omni-reminder") }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(missed)
        silence()
        speech?.stop(); speech?.shutdown(); speech = null
        reminder?.let { getSystemService(NotificationManager::class.java).cancel(ReminderScheduler.notificationId(it.id)) }
        reminder = null
        answered = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_RING = "com.gyftalala.omni.reminders.RING"
        const val ACTION_ANSWER = "com.gyftalala.omni.reminders.ANSWER"
        const val ACTION_SILENCE = "com.gyftalala.omni.reminders.SILENCE"
        const val EXTRA_ID = "reminderId"
        const val EXTRA_TITLE = "reminderTitle"
        const val EXTRA_KIND = "reminderKind"
        const val EXTRA_MINUTES = "reminderMinutes"
        const val EXTRA_TRIGGER_AT = "reminderTriggerAt"
        private const val RING_TIMEOUT_MS = 60_000L
    }
}

private val actionExecutor = Executors.newSingleThreadExecutor()

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ReminderCallService.EXTRA_ID) ?: return
        val expectedAt = intent.getLongExtra(ReminderCallService.EXTRA_TRIGGER_AT, 0L).takeIf { it > 0 }
        val pending = goAsync()
        actionExecutor.execute {
            try {
                when (intent.action) {
                    ACTION_DONE -> ReminderActions.complete(context, id, expectedAt)
                    ACTION_SNOOZE -> ReminderActions.postpone(context, id,
                        intent.getIntExtra(ReminderCallService.EXTRA_MINUTES, 5), "snoozed", expectedAt)
                    ACTION_RETRY -> ReminderActions.postpone(context, id, 5, "dismissed", expectedAt)
                }
                context.stopService(Intent(context, ReminderCallService::class.java))
                context.getSystemService(NotificationManager::class.java).cancel(ReminderScheduler.notificationId(id))
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_DONE = "com.gyftalala.omni.reminders.DONE"
        const val ACTION_SNOOZE = "com.gyftalala.omni.reminders.SNOOZE"
        const val ACTION_RETRY = "com.gyftalala.omni.reminders.RETRY"
    }
}
