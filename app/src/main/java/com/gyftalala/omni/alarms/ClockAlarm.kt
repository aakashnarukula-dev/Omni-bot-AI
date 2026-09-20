package com.gyftalala.omni.alarms

import android.content.Intent
import android.provider.AlarmClock
import com.gyftalala.omni.ai.AlarmCommand
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A chat handoff, never a claim that another app has saved an alarm. */
data class ClockAlarm(val at: Long = 0, val daily: Boolean = false, val opened: Boolean = false,
    val waiting: Boolean = false, val zone: String = ZoneId.systemDefault().id) {
    fun encode(): String = PREFIX + JSONObject().put("at", at).put("daily", daily).put("opened", opened)
        .put("waiting", waiting).put("zone", zone).toString()
    fun description(): String = if (at == 0L) "Clock" else Instant.ofEpochMilli(at).atZone(ZoneId.of(zone))
        .format(DateTimeFormatter.ofPattern(if (daily) "'Every day at' h:mm a z" else "EEE, d MMM 'at' h:mm a z"))
    fun intent(now: ZonedDateTime = ZonedDateTime.now()): Intent {
        if (opened || waiting || at == 0L) return Intent(AlarmClock.ACTION_SHOW_ALARMS)
        require(now.zone.id == zone) { "Phone time zone changed. Send the alarm time again." }
        val target = Instant.ofEpochMilli(at).atZone(now.zone)
        require(daily || AlarmCommand.nextOccurrence(target.hour, target.minute, now).toInstant().toEpochMilli() == at) {
            "This alarm time has passed. Send a new time; no alarm was created."
        }
        return Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, target.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, target.minute).putExtra(AlarmClock.EXTRA_MESSAGE, "Omni bot AI")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false).apply {
                if (daily) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList((1..7).toList()))
            }
    }
    companion object {
        private const val PREFIX = "clock-alarm-v1:"
        fun decode(action: String?): ClockAlarm? = runCatching {
            if (action == null || !action.startsWith(PREFIX) || action.length > 600) return null
            val json = JSONObject(action.removePrefix(PREFIX))
            ClockAlarm(json.getLong("at"), json.getBoolean("daily"), json.getBoolean("opened"),
                json.getBoolean("waiting"), json.getString("zone")).also {
                require(it.at >= 0); ZoneId.of(it.zone)
            }
        }.getOrNull()
    }
}

data class ClockLaunch(val messageId: String, val alarm: ClockAlarm)
