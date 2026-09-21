package com.gyftalala.omni.ai

import com.gyftalala.omni.data.ReminderKind

/** Local reminder semantics ported from the standalone Remind Me app. */
object ReminderProfile {
    fun kind(text: String): ReminderKind {
        val clean = text.lowercase()
        return when {
            Regex("\\b(medicine|medication|tablet|pill|dose|capsule|supplement)\\b").containsMatchIn(clean) -> ReminderKind.MEDICINE
            Regex("\\b(appointment|doctor|dentist|hospital|clinic|meeting)\\b").containsMatchIn(clean) -> ReminderKind.APPOINTMENT
            Regex("\\b(bill|payment|pay|emi|rent|recharge|subscription)\\b").containsMatchIn(clean) -> ReminderKind.PAYMENT
            Regex("\\b(exercise|workout|gym|walk|run|yoga)\\b").containsMatchIn(clean) -> ReminderKind.EXERCISE
            Regex("\\b(breakfast|lunch|dinner|meal|eat|food)\\b").containsMatchIn(clean) -> ReminderKind.MEAL
            Regex("\\b(wake(?:\\s+me)?(?:\\s+up)?|get up)\\b").containsMatchIn(clean) -> ReminderKind.WAKE_UP
            Regex("\\b(custom reminder|custom)\\b").containsMatchIn(clean) -> ReminderKind.CUSTOM
            else -> ReminderKind.TASK
        }
    }
}
