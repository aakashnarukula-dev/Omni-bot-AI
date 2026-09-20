package com.gyftalala.omni.ai

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

data class ParsedTime(val at: Long, val repeat: String = "none")

/** Deliberately rejects bare hours, vague periods, unsupported recurrence and dates in the past. */
object TimeParser {
    fun isTimeReply(input: String): Boolean = Regex("(?i)^\\s*(at\\b|on\\b|in\\b|tomorrow\\b|today\\b|every\\b|daily\\b|next\\b|noon\\b|midnight\\b|monday\\b|tuesday\\b|wednesday\\b|thursday\\b|friday\\b|saturday\\b|sunday\\b|\\d)").containsMatchIn(input)
    fun parse(input: String, now: ZonedDateTime = ZonedDateTime.now()): ParsedTime? = runCatching {
        val text = input.lowercase().trim()
        val relative = Regex("\\bin\\s+(\\d{1,4})\\s*(minutes?|mins?|hours?|hrs?|days?)\\b").find(text)
        if (relative != null) {
            if (Regex("\\b(every|daily|weekly|monthly|tomorrow|today)\\b|\\d\\s*(am|pm)\\b|\\d:\\d").containsMatchIn(text)) return null
            val n = relative.groupValues[1].toLong()
            if (n <= 0) return null
            val future = when {
                relative.groupValues[2].startsWith("m") -> now.plusMinutes(n)
                relative.groupValues[2].startsWith("h") -> now.plusHours(n)
                else -> now.plusDays(n)
            }
            return ParsedTime(future.toInstant().toEpochMilli())
        }
        val daily = Regex("\\b(every day|daily)\\b").containsMatchIn(text)
        if (Regex("\\b(every|weekly|monthly|yearly)\\b").containsMatchIn(text) && !daily) return null
        if (Regex("\\b(next week|next month|morning|evening|afternoon|tonight)\\b").containsMatchIn(text)) return null
        val match = Regex("(?<![\\d:/-])(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b").find(text)
            ?: Regex("(?<![\\d:/-])(\\d{1,2}):(\\d{2})(?![\\d:/-])").find(text)
        val time = when {
            match != null -> {
                var hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].ifBlank { "0" }.toInt()
                val meridiem = match.groupValues.getOrNull(3).orEmpty()
                if (meridiem.isNotBlank()) {
                    if (hour !in 1..12) return null
                    hour = hour % 12 + if (meridiem == "pm") 12 else 0
                }
                LocalTime.of(hour, minute)
            }
            "noon" in text -> LocalTime.NOON
            "midnight" in text -> LocalTime.MIDNIGHT
            else -> return null
        }
        val iso = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b").find(text)
        val weekday = DayOfWeek.entries.firstOrNull { Regex("\\b${it.name.lowercase()}\\b").containsMatchIn(text) }
        // Numeric local dates are ambiguous across locales; the date picker handles them.
        if (Regex("\\d{1,2}[/.-]\\d{1,2}").containsMatchIn(text) && iso == null) return null
        if (Regex("\\b(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec)\\b").containsMatchIn(text)) return null
        val explicitDate = iso != null || "today" in text || "tomorrow" in text || weekday != null
        val date = when {
            iso != null -> LocalDate.parse(iso.value)
            "day after tomorrow" in text -> now.toLocalDate().plusDays(2)
            "tomorrow" in text -> now.toLocalDate().plusDays(1)
            weekday != null -> now.toLocalDate().with(TemporalAdjusters.next(weekday))
            else -> now.toLocalDate()
        }
        var target = date.atTime(time).atZone(now.zone)
        // A nonexistent local clock time must be chosen explicitly, never silently shifted by DST.
        if (target.toLocalTime() != time) return null
        if (!target.isAfter(now)) {
            if (explicitDate && !daily) return null
            target = target.plusDays(1)
        }
        ParsedTime(target.toInstant().toEpochMilli(), if (daily) "daily" else "none")
    }.getOrNull()
}
