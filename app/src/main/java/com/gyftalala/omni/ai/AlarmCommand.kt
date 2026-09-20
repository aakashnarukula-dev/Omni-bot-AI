package com.gyftalala.omni.ai

import java.time.Instant
import java.time.ZonedDateTime

data class AlarmCommand(val time: ParsedTime? = null, val question: String? = null, val daily: Boolean = false,
    val showClock: Boolean = false) {
    companion object {
        private val create = Regex("(?i)^\\s*(?:please\\s+)?(?:(?:(?:set|add|create|schedule)\\s+(?:me\\s+)?(?:(?:an?|the)\\s+)?alarm\\b)|(?:wake\\s+me\\s+up\\b)|(?:wake\\s+me\\s+(?=at|in|tomorrow|today))|(?:alarm(?=\\s+(?:at|for|in|on|tomorrow|today|tonight|every|daily|next)|\\s*$)))")
        private val manage = Regex("(?i)^\\s*(?:please\\s+)?(?:show|open|list|delete|remove|cancel|manage)\\s+(?:me\\s+)?(?:(?:my|all|the)\\s+)?alarms?[.!?]?\\s*$")
        fun parse(input: String, now: ZonedDateTime = ZonedDateTime.now()): AlarmCommand? {
            if (manage.matches(input)) return AlarmCommand(showClock = true)
            if (!create.containsMatchIn(input)) return null
            val text = input.lowercase().replace(Regex("\\ba\\.m\\.?"), "am").replace(Regex("\\bp\\.m\\.?"), "pm")
            val daily = Regex("\\b(every day|daily)\\b").containsMatchIn(text)
            fun ask(reason: String) = AlarmCommand(question = reason, daily = daily)
            val clocks = Regex("(?<![\\d:/-])\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)\\b|(?<![\\d:/-])\\d{1,2}:\\d{2}(?![\\d:/-])|\\b(?:noon|midnight)\\b").findAll(text).count()
            if (clocks > 1) return ask("Send one alarm time at a time, including AM or PM.")
            if (Regex("\\b(?:utc|gmt|ist|est|edt|pst|pdt|cst|cet)\\b").containsMatchIn(text))
                return ask("Use the time shown on your phone for this alarm, including AM or PM.")
            val parsed = TimeParser.parse(text, now)
                ?: return ask("What time should the alarm ring? Include AM or PM, for example ‘12:55 am’. You can also say ‘every day at 7 am’.")
            // Clock's public API has minute precision. Never shorten a relative request by rounding down.
            var target = Instant.ofEpochMilli(parsed.at).atZone(now.zone)
            if (target.second != 0 || target.nano != 0) target = target.plusMinutes(1).withSecond(0).withNano(0)
            val nearest = nextOccurrence(target.hour, target.minute, now)
            if (nearest != target) return ask("That date cannot be set through Clock's quick alarm action. Choose the date in Clock, or give the next alarm time.")
            return AlarmCommand(parsed.copy(at = target.toInstant().toEpochMilli()), daily = daily)
        }
        fun nextOccurrence(hour: Int, minute: Int, now: ZonedDateTime): ZonedDateTime {
            val today = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
            return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(hour, minute).atZone(now.zone)
        }
    }
}
