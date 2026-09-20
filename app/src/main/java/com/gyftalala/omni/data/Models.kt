package com.gyftalala.omni.data

enum class Category(val label: String) {
    REMINDER("Reminder"),
    PRODUCT("Want to buy"),
    UX_DESIGN("UX design"),
    APK("APK"),
    CARD("Card"),
    DOCUMENT("Document"),
    NOTE("Note"),
    UNKNOWN("Needs review"),
}

enum class Role { USER, ASSISTANT }

data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val createdAt: Long,
    val category: Category? = null,
    val attachmentId: String? = null,
    val action: String? = null,
    // Null identifies older messages whose attachments are inferred from the saved memory.
    val fileIds: List<String>? = null,
)

fun ChatMessage.files(memory: Memory?): List<Attachment> {
    if (role != Role.USER || memory == null) return emptyList()
    fileIds?.let { ids -> return ids.mapNotNull { id -> memory.files.firstOrNull { it.id == id } } }
    val named = memory.files.filter { it.name in text.lines() }
    return named.ifEmpty { memory.files }
}

fun ChatMessage.caption(files: List<Attachment>): String =
    if (fileIds == null && files.isNotEmpty() && text == files.joinToString("\n") { it.name }) "" else text

data class StoredAttachment(
    val id: String,
    val encryptedPath: String,
    val mimeType: String,
    val originalName: String,
    val category: Category,
    val title: String,
    val summary: String,
    val ocrText: String,
    val issuer: String?,
    val lastFour: String?,
    val expiry: String?,
    val createdAt: Long,
)

data class Reminder(
    val id: String,
    val title: String,
    val triggerAt: Long,
    val completed: Boolean = false,
    val repeat: String = "none",
    val delivered: Boolean = false,
)

data class Attachment(val id: String, val name: String, val mime: String, val size: Long)

data class CardDetails(
    val issuer: String = "",
    val holder: String = "",
    val number: String = "",
    val expiry: String = "",
    val cvv: String = "",
    val type: String = "Debit card",
    val network: String = "",
)

enum class IdKind(val label: String) { PAN("PAN"), AADHAAR("Aadhaar"), DL("Driving licence"), OTHER("Other ID") }
data class IdentityDetails(val kind: IdKind, val number: String = "", val name: String = "", val birthDate: String = "")
enum class TaskPriority(val label: String) { URGENT("Urgent"), NORMAL("Normal"), LOW("Low") }
enum class TopicTag(val label: String) {
    PERSONAL("Personal"), WORK("Work"), HOME("Home"), HEALTH("Health"), BILLS("Bills"), FINANCE("Finance"), IDEAS("Ideas"), ENGINEERING("Engineering")
}
data class VoiceItem(val id: String, val text: String, val category: Category,
    val priority: TaskPriority = TaskPriority.NORMAL, val tag: TopicTag = TopicTag.PERSONAL,
    val selected: Boolean = true, val at: Long? = null, val query: Boolean = false)

data class Memory(
    val id: String,
    val title: String,
    val text: String,
    val category: Category,
    val createdAt: Long = System.currentTimeMillis(),
    val files: List<Attachment> = emptyList(),
    val ocr: String = "",
    val card: CardDetails? = null,
    val question: String? = null,
    val alternatives: List<Category> = emptyList(),
    val status: String = "Saved",
    val identity: IdentityDetails? = null,
    val palette: List<Int> = emptyList(),
    val priority: TaskPriority = TaskPriority.NORMAL,
    val tag: TopicTag = TopicTag.PERSONAL,
    val privateToDevice: Boolean = false,
    val categoryConfirmedAt: Long = 0,
    val sortOrder: Long = createdAt,
) {
    val staysOnDevice: Boolean get() = privateToDevice || category == Category.CARD || card != null || identity != null
}

data class IntentResult(
    val category: Category,
    val title: String,
    val summary: String,
    val confidence: Float,
    val clarificationQuestion: String? = null,
    val alternatives: List<Category> = emptyList(),
    val issuer: String? = null,
    val lastFour: String? = null,
    val expiry: String? = null,
)
