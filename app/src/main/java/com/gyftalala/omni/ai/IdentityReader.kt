package com.gyftalala.omni.ai

import com.gyftalala.omni.data.*

/** Format candidates, not government verification. Every field remains editable before saving. */
object IdentityReader {
    private val pan = Regex("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b")
    private val aadhaar = Regex("(?<!\\d)[2-9]\\d{3}[ -]?\\d{4}[ -]?\\d{4}(?!\\d)")
    private val dl = Regex("\\b[A-Z]{2}[ -]?\\d{2}[ -]?\\d{4}[ -]?\\d{6,8}\\b")
    fun detect(text: String): IdKind? = when {
        pan.containsMatchIn(text.uppercase()) || Regex("(?i)\\b(pan card|permanent account number|income tax department)\\b").containsMatchIn(text) -> IdKind.PAN
        aadhaar.containsMatchIn(text) || Regex("(?i)\\b(aadhaar|aadhar|uidai|unique identification)\\b").containsMatchIn(text) -> IdKind.AADHAAR
        Regex("(?i)\\b(driving licen[cs]e|driver.s licen[cs]e)\\b").containsMatchIn(text) || dl.containsMatchIn(text.uppercase()) -> IdKind.DL
        else -> null
    }
    fun extract(text: String, kind: IdKind, existing: IdentityDetails? = null): IdentityDetails {
        val upper = text.uppercase()
        val number = when (kind) {
            IdKind.PAN -> pan.find(upper)?.value
            IdKind.AADHAAR -> aadhaar.find(upper)?.value?.filter(Char::isDigit)
            IdKind.DL -> dl.find(upper)?.value?.replace(Regex("[ -]"), "")
            IdKind.OTHER -> Regex("(?im)^(?:id|document|number|id number)\\s*[:#-]\\s*([A-Z0-9 /-]{4,30})$").find(text)?.groupValues?.get(1)
        }.orEmpty()
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        val labeled = Regex("(?im)^(?:name|full name|name of holder)\\s*[:/-]\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
        val nameIndex = lines.indexOfFirst { it.equals("name", true) || it.equals("full name", true) }
        val afterLabel = if (nameIndex >= 0) lines.getOrNull(nameIndex + 1) else null
        val excluded = Regex("(?i)government|income|india|authority|identification|department|licen[cs]e|transport|signature|father|mother|address|male|female|birth|account|card|aadhaar|union|valid|issue")
        fun plausible(line: String) = line.length in 4..65 && line.none(Char::isDigit) && !excluded.containsMatchIn(line) && line.split(Regex("\\s+")).size >= 2
        val name = labeled?.takeIf(::plausible) ?: afterLabel?.takeIf(::plausible)
            ?: lines.filter { it == it.uppercase() && plausible(it) }.singleOrNull().orEmpty()
        val dob = Regex("(?i)(?:dob|date of birth|birth)\\s*[:/-]?\\s*(\\d{2}[/-]\\d{2}[/-]\\d{4})").find(text)?.groupValues?.get(1).orEmpty()
        return IdentityDetails(kind, existing?.number?.ifBlank { number } ?: number,
            existing?.name?.ifBlank { name } ?: name, existing?.birthDate?.ifBlank { dob } ?: dob)
    }
    fun different(text: String, current: IdentityDetails): Boolean {
        val fresh = extract(text, current.kind).number
        return current.number.isNotBlank() && fresh.isNotBlank() && normalize(current.number) != normalize(fresh)
    }
    private fun normalize(value: String) = value.uppercase().filter(Char::isLetterOrDigit)
}

class StableIdentityRead {
    private var previous = ""
    private var count = 0
    fun accept(number: String): Boolean {
        if (number.isBlank()) { previous = ""; count = 0; return false }
        count = if (number == previous) count + 1 else 1
        previous = number
        return count >= 3
    }
}
