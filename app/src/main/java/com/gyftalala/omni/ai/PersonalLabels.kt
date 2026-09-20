package com.gyftalala.omni.ai

import com.gyftalala.omni.data.*
import java.text.Normalizer
import java.util.Locale

/** Owner-confirmed examples only. Predictions never become training feedback. */
data class LabelExample(val text: String, val category: Category, val confirmedAt: Long)

object PersonalLabels {
    private val allowed = setOf(Category.PRODUCT, Category.REMINDER, Category.NOTE, Category.DOCUMENT, Category.UX_DESIGN)
    private val privateText = Regex("(?i)\\b(api[ _-]?key|secret|pin|token|iban|ssn|birth|dob|address|phone number)\\b|\\S+@\\S+|(?:\\d[ +()-]*){7,}")
    private val prefix = Regex("^(?:please )?(?:(?:i )?(?:need|want|have) to )?")
    private val actions = setOf("buy", "purchase", "order", "reorder", "shop", "save", "note", "collect", "research", "compare",
        "read", "watch", "learn", "study", "practice", "plan", "write", "draft", "design", "bookmark", "call", "do", "pay",
        "submit", "send", "book", "renew", "update", "check", "finish", "clean")
    private val noise = setOf("a", "an", "the", "my", "me", "i", "to", "for", "of", "and", "please", "some", "this", "that")
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
    fun eligible(text: String): Boolean = text.isNotBlank() && text.length <= 240 &&
        !Regex("(?:AIza[0-9A-Za-z_-]{20,}|sk[-_][0-9A-Za-z_-]{12,})").containsMatchIn(text) &&
        !CardExtractor.sensitive(text) && IdentityReader.detect(text) == null && !privateText.containsMatchIn(text)
    fun canLearn(memory: Memory): Boolean = !memory.staysOnDevice && memory.files.isEmpty() &&
        memory.category in allowed && eligible(memory.text) && !CardExtractor.sensitive(memory.ocr) && IdentityReader.detect(memory.ocr) == null

    fun fromHistory(memories: List<Memory>, messages: List<ChatMessage>): List<LabelExample> {
        // Older versions recorded explicit category changes as these fixed assistant replies.
        val confirmations = mutableMapOf<Pair<String, Category>, Long>()
        for (message in messages) {
            val id = message.attachmentId ?: continue
            if (message.role != Role.ASSISTANT) continue
            val category = allowed.firstOrNull { message.text.startsWith("Moved to ${it.label.lowercase(Locale.ROOT)}.") } ?: continue
            val key = id to category
            confirmations[key] = maxOf(confirmations[key] ?: 0, message.createdAt)
        }
        return memories.asSequence().filter(::canLearn).mapNotNull { memory ->
            val at = maxOf(memory.categoryConfirmedAt, confirmations[memory.id to memory.category] ?: 0)
            if (at <= 0) null else LabelExample(memory.text, memory.category, at)
        }.sortedByDescending { it.confirmedAt }.distinctBy { normalize(it.text) }.take(200).toList()
    }
    private fun family(text: String): String? {
        if (ShoppingLanguage.administrativeOrder(text)) return null
        return normalize(text).replace(prefix, "").substringBefore(' ').takeIf { it in actions }
    }
    private fun tokens(text: String) = normalize(text).split(' ').filter { it !in noise && it.length > 1 }.toSet()
    private fun safe(examples: List<LabelExample>) = examples.asSequence().filter { it.category in allowed && eligible(it.text) }
        .sortedByDescending { it.confirmedAt }.distinctBy { normalize(it.text) }.take(200).toList()
    private fun similarity(a: String, b: String): Float {
        val left = tokens(a); val right = tokens(b)
        val shared = (left intersect right).size
        if (shared < 2) return 0f
        return 2f * shared / (left.size + right.size)
    }
    fun category(text: String, examples: List<LabelExample>): Category? {
        if (!eligible(text) || ShoppingLanguage.explicitReminder(text)) return null
        val normalized = normalize(text)
        val confirmed = safe(examples)
        confirmed.firstOrNull { normalize(it.text) == normalized }?.let { return it.category }
        if (Regex("\\b(not|never|don t|cancel)\\b").containsMatchIn(normalized) || ShoppingLanguage.administrativeOrder(text)) return null
        val family = family(text)
        val related = confirmed.filter { family(it.text) == family }
            .map { it to similarity(text, it.text) }.filter { it.second >= .72f }.sortedByDescending { it.second }
        if (related.isNotEmpty()) {
            val best = related.first()
            if (related.none { it.first.category != best.first.category && it.second >= best.second - .12f }) return best.first.category
            return null
        }
        // One correction must not turn every unrelated verb/object into the same category.
        if (family != null) {
            val sameAction = confirmed.filter { family(it.text) == family && !ShoppingLanguage.explicitReminder(it.text) }
            if (sameAction.size >= 2 && sameAction.map { it.category }.distinct().size == 1) return sameAction.first().category
        }
        return null
    }
    fun apply(text: String, fallback: IntentResult, examples: List<LabelExample>): IntentResult {
        val category = category(text, examples) ?: return fallback
        return IntentResult(category, text.trim().take(58).replaceFirstChar(Char::uppercase), "Saved using your category preference", .97f,
            clarificationQuestion = if (category == Category.REMINDER) "When should I remind you?" else null)
    }
    /** Bounded relevant text examples for an already-consented Gemini request; never full chat history. */
    fun relevant(text: String, examples: List<LabelExample>): List<LabelExample> {
        if (!eligible(text)) return emptyList()
        val family = family(text)
        return safe(examples).map { it to (similarity(text, it.text) + if (family != null && family(it.text) == family) .5f else 0f) }
            .filter { it.second > 0f }.sortedByDescending { it.second }.take(4).map { it.first }
    }
}

object ShoppingLanguage {
    private val shopping = Regex("(?i)^(?:please\\s+)?(?:(?:i\\s+)?(?:need|want|have)\\s+to\\s+)?(?:buy|purchase|order|reorder|shop for)\\s+\\S")
    private val explicit = Regex("(?i)^(?:please\\s+)?(?:(?:i\\s+)?(?:need|want|have)\\s+to\\s+)?(?:remind\\b|remember to\\b|set (?:a )?reminder\\b)")
    private val timing = Regex("(?i)\\b(tomorrow|tonight|today|daily|every day|next week|next month|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b|\\b(?:at|by|after|before)\\s+(?:\\d|noon|midnight|breakfast|lunch|dinner)|\\bin\\s+\\d+\\s*(?:minutes?|hours?|days?)\\b")
    fun explicitReminder(text: String): Boolean = explicit.containsMatchIn(text.trim()) || timing.containsMatchIn(text) || TimeParser.parse(text) != null
    fun administrativeOrder(text: String): Boolean = Regex("(?i)^\\s*(?:please\\s+)?order\\b").containsMatchIn(text) &&
        Regex("(?i)\\b(alphabetically|chronologically|ascending|descending|sort|sort order|by (?:date|name|price|priority|urgency)|tasks|list|records|rows)\\b").containsMatchIn(text)
    fun isShopping(text: String): Boolean = shopping.containsMatchIn(text.trim()) && !administrativeOrder(text)
}
