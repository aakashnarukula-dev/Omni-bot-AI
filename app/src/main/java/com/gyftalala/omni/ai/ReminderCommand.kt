package com.gyftalala.omni.ai

/** Commands address saved reminders. Embedded tasks such as “remind me to delete…” are not commands. */
data class ReminderCommand(val query: String) {
    companion object {
        private val prefix = Regex("""(?i)^\s*(?:(?:can|could|would)\s+you\s+)?(?:please\s+)?(?:delete|remove|cancel|clear)\s+(.+?)\s*[.!?]*$""")
        private val reminder = Regex("""(?i)\breminders?\b""")
        private val filler = Regex("""(?i)\b(?:my|the|all|saved|please|for|about|to|of)\b""")
        fun parse(text: String): ReminderCommand? {
            val target = prefix.matchEntire(text)?.groupValues?.get(1) ?: return null
            if (!reminder.containsMatchIn(target)) return null
            return ReminderCommand(target.replace(reminder, " ").replace(filler, " ")
                .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim())
        }
    }
}
