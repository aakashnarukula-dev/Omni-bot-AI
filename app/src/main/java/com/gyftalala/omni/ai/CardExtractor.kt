package com.gyftalala.omni.ai

import com.gyftalala.omni.data.CardDetails

object CardExtractor {
    private val number = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
    fun extract(text: String): CardDetails {
        val issuer = listOf("IndusInd", "HDFC", "ICICI", "Axis", "SBI", "Kotak", "American Express", "Amex", "IDFC", "Federal", "Canara", "Bank of Baroda", "Union Bank", "Yes Bank")
            .firstOrNull { text.contains(it, true) }.orEmpty()
        val network = listOf("Visa", "Mastercard", "RuPay", "American Express").firstOrNull { text.contains(it, true) }.orEmpty()
        val digits = number.findAll(text).map { it.value.filter(Char::isDigit) }.firstOrNull { luhn(it) }.orEmpty()
        val expiry = Regex("\\b(0[1-9]|1[0-2])\\s*[/|-]\\s*(20\\d{2}|\\d{2})\\b").findAll(text).lastOrNull()?.value?.replace(" ", "").orEmpty()
        val cvv = Regex("(?i)\\b(?:cvv2?|cvc2?|security code)\\s*[: -]?\\s*(\\d{3,4})\\b").find(text)?.groupValues?.get(1).orEmpty()
        val holder = Regex("(?im)^(?:cardholder(?: name)?|name)\\s*[: -]\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        return CardDetails(issuer, holder, digits, expiry, cvv, if (text.contains("credit", true)) "Credit card" else "Debit card", network)
    }
    fun luhn(value: String): Boolean {
        if (value.length !in 13..19 || value.any { !it.isDigit() }) return false
        return value.reversed().mapIndexed { index, char ->
            val n = char.digitToInt() * if (index % 2 == 1) 2 else 1
            if (n > 9) n - 9 else n
        }.sum() % 10 == 0 && value.toSet().size > 1
    }
    fun sensitive(text: String): Boolean = Regex("(?i)\\b(card|cvv|cvc|visa|mastercard|rupay|account|bank|ifsc|statement|medical|angiogram|discharge|passport|aadhaar|password|otp)\\b")
        .containsMatchIn(text) || number.containsMatchIn(text)
}
