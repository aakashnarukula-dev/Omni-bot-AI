package com.gyftalala.omni.reminders

import android.content.Context
import android.content.Intent
import com.gyftalala.omni.data.ChatMessage
import com.gyftalala.omni.data.OmniStore
import com.gyftalala.omni.data.Reminder
import com.gyftalala.omni.data.Role
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

object ReminderRecurrence {
    fun next(anchorAt: Long, repeat: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (repeat == "none") return null
        var candidate = Instant.ofEpochMilli(anchorAt).atZone(zone)
        do {
            candidate = when {
                repeat == "daily" -> candidate.plusDays(1)
                repeat == "weekdays" -> candidate.plusDays(1).let { value ->
                    generateSequence(value) { it.plusDays(1) }.first { it.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
                }
                repeat == "weekends" -> candidate.plusDays(1).let { value ->
                    generateSequence(value) { it.plusDays(1) }.first { it.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
                }
                repeat.startsWith("weekly:") -> candidate.plusWeeks(1)
                else -> return null
            }
        } while (candidate.toInstant().toEpochMilli() <= now)
        return candidate.toInstant().toEpochMilli()
    }
}

object ReminderActions {
    private val formatter = DateTimeFormatter.ofPattern("EEE, d MMM 'at' h:mm a z")

    fun delivered(context: Context, reminder: Reminder): Reminder {
        val updated = reminder.copy(delivered = true, lastAction = "ringing")
        OmniStore(context).use { store ->
            store.transaction {
                store.save(updated)
                store.save(ChatMessage("delivery:${reminder.id}:${reminder.triggerAt}", Role.ASSISTANT,
                    "Reminder: ${reminder.title}", System.currentTimeMillis(), attachmentId = reminder.id))
            }
        }
        changed(context)
        return updated
    }

    fun complete(context: Context, id: String, expectedAt: Long? = null) {
        val scheduler = ReminderScheduler(context)
        OmniStore(context).use { store ->
            val reminder = store.reminders().firstOrNull { it.id == id && !it.completed } ?: return@use
            if (expectedAt != null && reminder.triggerAt != expectedAt) return@use
            scheduler.cancel(id)
            val memory = store.memories().firstOrNull { it.id == id }
            val next = ReminderRecurrence.next(reminder.anchorAt, reminder.repeat)
            store.transaction {
                if (next == null) {
                    store.save(reminder.copy(completed = true, delivered = true, retryCount = 0, lastAction = "completed"))
                    memory?.let { store.save(it.copy(status = "Done", question = null)) }
                } else {
                    val following = reminder.copy(triggerAt = next, anchorAt = next, completed = false,
                        delivered = false, retryCount = 0, lastAction = "completed")
                    store.save(following)
                    memory?.let { store.save(it.copy(status = format(next), question = null)) }
                    scheduler.schedule(following)
                }
                store.save(ChatMessage(UUID.randomUUID().toString(), Role.ASSISTANT,
                    if (next == null) "Reminder completed: ${reminder.title}"
                    else "Reminder completed. Next: ${format(next)}", System.currentTimeMillis(), attachmentId = id))
            }
        }
        changed(context)
    }

    fun postpone(context: Context, id: String, minutes: Int, reason: String = "snoozed", expectedAt: Long? = null) {
        require(minutes in setOf(5, 15, 30, 60)) { "Unsupported reminder delay." }
        val scheduler = ReminderScheduler(context)
        OmniStore(context).use { store ->
            val reminder = store.reminders().firstOrNull { it.id == id && !it.completed } ?: return@use
            if (expectedAt != null && reminder.triggerAt != expectedAt) return@use
            scheduler.cancel(id)
            val at = System.currentTimeMillis() + minutes * 60_000L
            val postponed = reminder.copy(triggerAt = at, delivered = false,
                retryCount = reminder.retryCount + 1, lastAction = reason)
            store.transaction {
                store.save(postponed)
                store.memories().firstOrNull { it.id == id }?.let { store.save(it.copy(status = "Snoozed until ${format(at)}", question = null)) }
                store.save(ChatMessage(UUID.randomUUID().toString(), Role.ASSISTANT,
                    "Reminder moved by $minutes minutes: ${reminder.title}", System.currentTimeMillis(), attachmentId = id))
            }
            scheduler.schedule(postponed)
        }
        changed(context)
    }

    private fun format(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(formatter)
    private fun changed(context: Context) = context.sendBroadcast(Intent("com.gyftalala.omni.CHANGED").setPackage(context.packageName))
}
