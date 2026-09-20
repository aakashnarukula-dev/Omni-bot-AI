package com.gyftalala.omni.ai

import com.gyftalala.omni.data.*
import java.util.UUID

object TaskHints {
    fun priority(text: String) = when {
        Regex("(?i)\\b(urgent|urgently|asap|immediately|high priority)\\b").containsMatchIn(text) -> TaskPriority.URGENT
        Regex("(?i)\\b(low priority|whenever|someday|no rush)\\b").containsMatchIn(text) -> TaskPriority.LOW
        else -> TaskPriority.NORMAL
    }
    fun tag(text: String): TopicTag = listOf(
        TopicTag.BILLS to "bill|rent|electricity|mortgage|insurance premium",
        TopicTag.HEALTH to "doctor|clinic|medicine|hospital|angiogram|discharge|health|dentist|gym",
        TopicTag.ENGINEERING to "deploy|code|pull request|debug|github|api",
        TopicTag.WORK to "work|office|meeting|client|boss|madam",
        TopicTag.HOME to "home|puja|groceries|laundry|clean|kitchen",
        TopicTag.FINANCE to "bank|finance|investment|credit|debit|account",
        TopicTag.IDEAS to "idea|brainstorm|inspiration"
    ).firstOrNull { Regex("(?i)\\b(?:${it.second})\\b").containsMatchIn(text) }?.first ?: TopicTag.PERSONAL
}

/** Split only before another action; preserve phrases like 'bread and butter' or names. */
object VoiceParser {
    private const val ACTION = "(?:call|remind|remember|do|pay|submit|send|book|schedule|renew|update|check|buy|order|note|save|write|finish|clean|set|wake|show|find|get|open|list|search|delete|remove|cancel|clear)"
    private val boundary = Regex("(?i)(?:[.!?;]\\s+|\\s+(?:and(?:\\s+then)?|also|then|plus)\\s+)(?=(?:please\\s+)?$ACTION\\b)")
    fun split(text: String): List<String> = text.trim().split(boundary).map(String::trim).filter(String::isNotBlank)
    fun time(text: String): ParsedTime? {
        val numbers = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
            "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
            "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "sixty" to 60)
        var normalized = text.lowercase().replace(Regex("\\ba\\.m\\.?"), "am").replace(Regex("\\bp\\.m\\.?"), "pm")
        val word = numbers.keys.joinToString("|")
        normalized = normalized.replace(Regex("\\bin\\s+($word)\\s+(minutes?|hours?|days?)\\b")) {
            "in ${numbers[it.groupValues[1]]} ${it.groupValues[2]}"
        }
        normalized = normalized.replace(Regex("\\b($word)(?:\\s+(fifteen|thirty|forty five))?\\s*(am|pm)\\b")) {
            val minute = when (it.groupValues[2]) { "fifteen" -> ":15"; "thirty" -> ":30"; "forty five" -> ":45"; else -> "" }
            "${numbers[it.groupValues[1]]}$minute ${it.groupValues[3]}"
        }
        return TimeParser.parse(normalized)
    }
    fun parse(text: String): List<VoiceItem> {
        require(text.length <= 16000) { "Use a shorter recording." }
        val segments = split(text)
        require(segments.size <= 12) { "Review up to 12 items at once. Use a shorter recording." }
        val engine = IntentEngine()
        return segments.map { phrase ->
            val category = when {
                Regex("(?i)^\\s*(?:please\\s+)?(?:set (?:an? )?(?:alarm|timer)|wake me|finish|clean)\\b").containsMatchIn(phrase) -> Category.REMINDER
                else -> engine.classify(phrase).category
            }
            VoiceItem(UUID.randomUUID().toString(), phrase, category, TaskHints.priority(phrase), TaskHints.tag(phrase), query = engine.isRetrievalQuery(phrase))
        }
    }
}
