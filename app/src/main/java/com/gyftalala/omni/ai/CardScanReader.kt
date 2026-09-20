package com.gyftalala.omni.ai

import com.gyftalala.omni.data.CardDetails

/** Additional hints are restricted to the owner's explicit card-scanning flow. */
object CardScanReader {
    fun differentCard(text: String, existing: CardDetails): Boolean {
        val number = CardExtractor.extract(text).number
        return existing.number.isNotBlank() && number.isNotBlank() && existing.number != number
    }
    fun extract(text: String, back: Boolean, existing: CardDetails? = null): CardDetails {
        // Printed capital I is frequently read as lowercase l in this issuer's name.
        val normalized = text.replace(Regex("(?i)\\bindus[il1]nd\\b"), "IndusInd")
        val parsed = CardExtractor.extract(normalized)
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        val excluded = Regex("(?i)\\b(bank|card|debit|credit|visa|mastercard|rupay|valid|thru|through|expiry|expires|member|signature|authorized|electronic|use|only|platinum|gold|silver|classic|international|customer|service|not|transferable|indusind|hdfc|icici|axis|sbi|kotak|american|express)\\b")
        val names = if (parsed.number.isNotBlank() && !back) lines.filter {
            it.length in 5..40 && Regex("[A-Z][A-Z .'-]+ [A-Z .'-]+").matches(it) && !excluded.containsMatchIn(it)
        } else emptyList()
        val holder = parsed.holder.ifBlank { names.singleOrNull().orEmpty() }
        // A bare code is considered only on an explicitly scanned back, never in general messages.
        val bareCodes = if (back) lines.mapNotNull { line ->
            when {
                Regex("\\d{3}").matches(line) -> line
                existing?.number?.isNotBlank() == true && Regex("${existing.number.takeLast(4)}\\s+\\d{3}").matches(line) -> line.takeLast(3)
                else -> null
            }
        }.distinct() else emptyList()
        val cvv = parsed.cvv.ifBlank { bareCodes.singleOrNull().orEmpty() }
        val type = when {
            text.contains("credit", true) -> "Credit card"
            text.contains("debit", true) -> "Debit card"
            else -> "Payment card"
        }
        val fresh = parsed.copy(holder = holder, cvv = cvv, type = type)
        return existing?.copy(
            issuer = existing.issuer.ifBlank { fresh.issuer }, holder = existing.holder.ifBlank { fresh.holder },
            number = existing.number.ifBlank { fresh.number }, expiry = existing.expiry.ifBlank { fresh.expiry },
            cvv = existing.cvv.ifBlank { fresh.cvv }, network = existing.network.ifBlank { fresh.network },
            type = if (existing.type == "Payment card") fresh.type else existing.type,
        ) ?: fresh
    }
}

/** Require repeated matching reads; unrelated/empty frames clear the candidate. */
class StableCardRead {
    private var candidate = ""
    private var matches = 0
    fun accept(card: CardDetails, back: Boolean): Boolean {
        val key = if (back) card.cvv else card.number.takeIf(CardExtractor::luhn).orEmpty()
        if (key.isBlank()) { candidate = ""; matches = 0; return false }
        matches = if (key == candidate) matches + 1 else 1
        candidate = key
        return matches >= 3
    }
}
