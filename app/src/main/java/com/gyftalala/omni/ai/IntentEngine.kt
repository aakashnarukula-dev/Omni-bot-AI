package com.gyftalala.omni.ai

import com.gyftalala.omni.data.Category
import com.gyftalala.omni.data.IntentResult

class IntentEngine {
    private val reminderVerbs = Regex(
        "^(?:please\\s+)?(?:(?:i\\s+)?(?:need|want|have)\\s+to\\s+)?(call|remind|remember|do|pay|submit|send|book|schedule|renew|update|follow up|follow-up|check)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val productWords = Regex(
        "\\b(cable|charger|adapter|adaptor|shoe|shoes|headphones?|earbuds?|phone|laptop|keyboard|mouse|watch|bag|shirt|dress|product)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val uxWords = Regex(
        "\\b(ux|ui|wireframe|prototype|figma|checkout|navigation|navbar|landing page|product page|add to cart|sign in|log in)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val documentWords = Regex(
        "\\b(statement|invoice|receipt|certificate|discharge summary|report|document|bank|policy|agreement|passport|aadhaar|pan card)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val cardWords = Regex(
        "\\b(credit card|debit card|valid thru|valid through|cvv|mastercard|visa|rupay|amex)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val retrievalWords = Regex(
        "^\\s*(?:please\\s+)?(?:(?:show|find|get|open|list|search(?:\\s+for)?|look for|where is|where are)\\s+(?:me\\s+)?(?:my\\s+)?|(?:send|give)\\s+me\\s+(?:my\\s+)?)",
        RegexOption.IGNORE_CASE,
    )
    private val cardNumber = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
    private val expiry = Regex("\\b(0[1-9]|1[0-2])\\s*[/|-]\\s*(\\d{2}|20\\d{2})\\b")

    fun classify(
        text: String,
        fileName: String? = null,
        mimeType: String? = null,
        isImage: Boolean = false,
    ): IntentResult {
        val clean = text.trim()
        val searchable = listOfNotNull(clean, fileName).joinToString(" ")
        val lower = searchable.lowercase()

        if (fileName?.endsWith(".apk", ignoreCase = true) == true ||
            mimeType == "application/vnd.android.package-archive"
        ) {
            return result(Category.APK, fileName ?: "Android app", "Saved under APKs", .99f)
        }

        if (!isImage && fileName == null && ShoppingLanguage.isShopping(clean) && !looksLikeCard(clean)) {
            if (ShoppingLanguage.explicitReminder(clean)) return IntentResult(Category.REMINDER, titleFrom(clean, "Reminder"),
                "Waiting for date and time", .96f, "When should I remind you?")
            return result(Category.PRODUCT, titleFrom(clean, "Product"), "Added to things you want to buy", .97f)
        }

        if (!isImage && fileName == null && (reminderVerbs.containsMatchIn(clean) ||
            (ReminderProfile.kind(clean) != com.gyftalala.omni.data.ReminderKind.TASK && TimeParser.parse(clean) != null) ||
            lower == "puja" || lower.startsWith("puja ") || lower.endsWith(" update")
        )) {
            return IntentResult(
                category = Category.REMINDER,
                title = titleFrom(clean, "Reminder"),
                summary = "Waiting for date and time",
                confidence = .91f,
                clarificationQuestion = "When should I remind you?",
            )
        }

        if (looksLikeCard(searchable)) {
            val digits = cardNumber.find(searchable)?.value?.filter(Char::isDigit)
            return IntentResult(
                category = Category.CARD,
                title = issuer(searchable)?.let { "$it card" } ?: "Payment card",
                summary = "Stored in private card vault",
                confidence = .98f,
                issuer = issuer(searchable),
                lastFour = digits?.takeLast(4),
                expiry = expiry.find(searchable)?.value?.replace(" ", ""),
            )
        }

        if (!isImage && ("amazon." in lower || "amzn." in lower || "flipkart." in lower || "myntra." in lower)) {
            return result(Category.PRODUCT, titleFrom(clean, "Saved product"), "Added to things you want to buy", .98f)
        }

        if (isImage && uxWords.containsMatchIn(searchable) && productWords.containsMatchIn(searchable)) {
            return IntentResult(
                category = Category.UNKNOWN,
                title = "Product-page screenshot",
                summary = "Could be design reference or product to buy",
                confidence = .55f,
                clarificationQuestion = "Save this as UX design or a product you want to buy?",
                alternatives = listOf(Category.UX_DESIGN, Category.PRODUCT),
            )
        }

        if (uxWords.containsMatchIn(searchable) && (isImage || lower.contains("design"))) {
            return result(Category.UX_DESIGN, "UX reference", "Saved with design references", .90f)
        }

        if (mimeType == "application/pdf" || fileName != null && documentWords.containsMatchIn(searchable)) {
            return result(Category.DOCUMENT, fileName ?: "Document", "Saved with documents", .90f)
        }

        if (productWords.containsMatchIn(searchable)) {
            return result(Category.PRODUCT, titleFrom(clean, fileName ?: "Product"), "Added to things you want to buy", .86f)
        }

        if (documentWords.containsMatchIn(searchable) || mimeType == "application/pdf") {
            return result(Category.DOCUMENT, fileName ?: titleFrom(clean, "Document"), "Saved with documents", .84f)
        }

        if (isImage) {
            return IntentResult(Category.UNKNOWN, fileName ?: "Image", "Choose where this belongs", .3f,
                "Is this a design reference, something to buy, a card, or a document?",
                listOf(Category.UX_DESIGN, Category.PRODUCT, Category.CARD, Category.DOCUMENT))
        }

        if (fileName != null) {
            return result(Category.DOCUMENT, fileName, "File stored securely", .72f)
        }

        if (clean.isNotBlank()) {
            return result(Category.NOTE, titleFrom(clean, "Note"), "Saved as a note", .68f)
        }

        return result(Category.UNKNOWN, "Untitled item", "Choose a category", .2f)
    }

    fun isRetrievalQuery(text: String): Boolean = retrievalWords.containsMatchIn(text)

    fun searchTerms(text: String): String = text
        .replace(retrievalWords, "")
        .replace(Regex("\\b(please|image|photo|file)\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun looksLikeCard(text: String): Boolean =
        Regex("(?i)\\b(credit card|debit card|cvv|cvc|valid thru|valid through)\\b").containsMatchIn(text) ||
            cardWords.containsMatchIn(text) && (cardNumber.containsMatchIn(text) || expiry.containsMatchIn(text)) ||
            cardNumber.findAll(text).any { CardExtractor.luhn(it.value.filter(Char::isDigit)) }

    private fun issuer(text: String): String? {
        val candidates = listOf("IndusInd", "HDFC", "ICICI", "Axis", "SBI", "Kotak", "Amex", "Visa", "Mastercard", "RuPay")
        return candidates.firstOrNull { text.contains(it, ignoreCase = true) }
    }

    private fun titleFrom(text: String, fallback: String): String {
        if (text.isBlank()) return fallback
        return text.lineSequence().first().trim().take(58).replaceFirstChar(Char::uppercase)
    }

    private fun result(category: Category, title: String, summary: String, confidence: Float) =
        IntentResult(category, title, summary, confidence)
}
