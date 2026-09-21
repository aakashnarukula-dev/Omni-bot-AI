package com.gyftalala.omni

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gyftalala.omni.ai.*
import com.gyftalala.omni.alarms.ClockAlarm
import com.gyftalala.omni.alarms.ClockLaunch
import com.gyftalala.omni.data.*
import com.gyftalala.omni.reminders.ReminderScheduler
import com.gyftalala.omni.reminders.ReminderActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class ModelCheck(val model: String, val checking: Boolean = false, val passed: Boolean = false, val message: String)
data class ReminderDeletion(val id: String, val candidates: List<Memory>)

data class OmniState(
    val memories: List<Memory> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val busy: Boolean = false,
    val ready: Boolean = false,
    val hasKey: Boolean = false,
    val cloud: Boolean = false,
    val model: String = "gemini-3.8-flash",
    val availableModels: List<GeminiModel> = emptyList(),
    val modelCheck: ModelCheck? = null,
    val modelListStatus: String? = null,
    val loadingModels: Boolean = false,
    val error: String? = null,
    val reminderDeletion: ReminderDeletion? = null,
    val clockLaunch: ClockLaunch? = null,
)

class OmniViewModel @JvmOverloads constructor(application: Application, private val gemini: GeminiClient = GeminiClient()) : AndroidViewModel(application) {
    private val store = OmniStore(application)
    val vault get() = store.vault
    private val engine = IntentEngine()
    private val ocr = OcrReader()
    private val scheduler = ReminderScheduler(application)
    private val mutex = VaultAccess.mutex
    private val mutable = MutableStateFlow(OmniState())
    val state = mutable.asStateFlow()
    internal val cloudBackups = com.gyftalala.omni.cloud.CloudBackupController(application, store, viewModelScope) { refresh() }
    internal val backups = com.gyftalala.omni.backup.BackupController(application, store, viewModelScope) { block ->
        mutex.withLock {
            mutable.value = mutable.value.copy(busy = true)
            try { block() } finally {
                runCatching { publish() }
                mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    fun refresh() = work {
        backups.recoverPending()
        scheduler.createChannel()
        store.memories().filter { it.status in listOf("Sorting", "Reading attachment") }.forEach {
            store.save(it.copy(status = "Needs review", question = "Your original is saved. Where should this go?"))
        }
        store.reminders().filter { !it.completed && !it.delivered }.forEach(scheduler::schedule)
    }
    private fun work(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        mutex.withLock {
            mutable.value = mutable.value.copy(busy = true, error = null)
            try { block(); publish() }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                mutable.value = mutable.value.copy(error = error.message?.take(180) ?: "Something failed. Please try again.")
                runCatching { publish() }
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    private fun publish() {
        val settings = store.settings()
        mutable.value = mutable.value.copy(memories = store.memories().sortedByDescending { it.sortOrder }, messages = store.messages(),
            reminders = store.reminders(), ready = true, hasKey = settings.optString("key").isNotBlank(),
            cloud = settings.optBoolean("cloud"), model = settings.optString("model", "gemini-3.8-flash"))
    }
    private fun reply(text: String, id: String? = null) = store.save(ChatMessage(UUID.randomUUID().toString(), Role.ASSISTANT,
        text, System.currentTimeMillis(), attachmentId = id))

    fun send(text: String, uris: List<Uri> = emptyList(), category: Category? = null, appendTo: String? = null,
        reviewedCard: CardDetails? = null, reviewedIdentity: IdentityDetails? = null) = work {
        sendInternal(text, uris, category, appendTo, reviewedCard, reviewedIdentity)
    }
    private suspend fun sendInternal(text: String, uris: List<Uri> = emptyList(), category: Category? = null, appendTo: String? = null,
        reviewedCard: CardDetails? = null, reviewedIdentity: IdentityDetails? = null, voice: VoiceItem? = null) {
        require(reviewedCard == null || (category == Category.CARD && uris.isNotEmpty())) { "A reviewed card needs its scanned photo." }
        require(reviewedIdentity == null || (category == Category.DOCUMENT && uris.isNotEmpty())) { "An ID needs its scanned original." }
        val clean = text.trim().take(16000)
        if (clean.isBlank() && uris.isEmpty()) return
        require(uris.size <= 10) { "Choose up to 10 files per message." }
        val existing = store.memories().sortedByDescending { it.createdAt }
        val history by lazy { store.messages() }
        // Explicit clock requests stay local and cannot consume an unrelated pending reminder.
        if (uris.isEmpty() && category == null && appendTo == null) {
            val last = history.lastOrNull()
            val awaiting = last?.takeIf { it.role == Role.ASSISTANT }?.action?.let(ClockAlarm::decode)?.takeIf { it.waiting }
            if (awaiting != null && clean.lowercase() in setOf("cancel", "cancel alarm", "never mind", "nevermind")) {
                store.save(ChatMessage(UUID.randomUUID().toString(), Role.USER, clean, System.currentTimeMillis()))
                reply("Alarm request cancelled. No alarm was created.")
                mutable.value = mutable.value.copy(clockLaunch = null)
                return
            }
            val alarm = AlarmCommand.parse(clean) ?: if (awaiting != null && TimeParser.isTimeReply(clean))
                AlarmCommand.parse("set an alarm ${if (awaiting.daily) "every day " else ""}$clean") else null
            if (alarm != null) {
                val user = ChatMessage(UUID.randomUUID().toString(), Role.USER, clean, System.currentTimeMillis())
                val action = ClockAlarm(at = alarm.time?.at ?: 0, daily = alarm.daily, opened = alarm.showClock, waiting = alarm.question != null)
                val message = ChatMessage(UUID.randomUUID().toString(), Role.ASSISTANT,
                    alarm.question ?: if (alarm.showClock) "Manage ringing alarms in your phone's Clock app."
                    else "Alarm · ${action.description()}\nSet this in your phone's Clock app.", System.currentTimeMillis(), action = action.encode())
                store.transaction {
                    store.save(user)
                    store.save(message)
                }
                mutable.value = mutable.value.copy(clockLaunch = if (alarm.question == null) ClockLaunch(message.id, action) else null)
                return
            }
        }
        val deletion = if (uris.isEmpty()) ReminderCommand.parse(clean) else null
        if (deletion != null) {
            store.save(ChatMessage(UUID.randomUUID().toString(), Role.USER, clean, System.currentTimeMillis()))
            val candidates = MemorySearch.find(existing.filter { it.category == Category.REMINDER }, deletion.query)
            mutable.value = mutable.value.copy(reminderDeletion = candidates.takeIf { it.isNotEmpty() }?.let {
                ReminderDeletion(UUID.randomUUID().toString(), it)
            })
            reply(if (candidates.isEmpty()) "No matching reminders found. Nothing deleted."
                else "Choose which reminders to delete. Nothing is removed until you confirm.")
            return
        }
        if (uris.isEmpty() && engine.isRetrievalQuery(clean)) {
            store.save(ChatMessage(UUID.randomUUID().toString(), Role.USER, clean, System.currentTimeMillis()))
            val found = MemorySearch.find(existing, engine.searchTerms(clean))
            if (found.isEmpty()) reply("I don't have anything matching that yet. Send the item or try another name.")
            else {
                reply("Found ${found.size} saved ${if (found.size == 1) "item" else "items"}.")
                found.take(20).forEach { reply(it.title, it.id) }
            }
            return
        }
        val pending = existing.firstOrNull { it.category == Category.REMINDER && it.question != null }
        val timeReply = TimeParser.isTimeReply(clean)
        if (voice == null && uris.isEmpty() && pending != null && timeReply && TimeParser.parse(clean) != null) {
            store.save(ChatMessage(UUID.randomUUID().toString(), Role.USER, clean, System.currentTimeMillis()))
            scheduleInternal(pending, TimeParser.parse(clean)!!)
            return
        }
        if (voice == null && uris.isEmpty() && pending != null && timeReply) {
            store.save(ChatMessage(UUID.randomUUID().toString(), Role.USER, clean, System.currentTimeMillis()))
            reply("I need a clear future date and time for “${pending.title}”. Try ‘tomorrow at 8 pm’, ‘in 20 minutes’, or choose a time below.", pending.id)
            return
        }
        val unresolved = existing.firstOrNull { it.category == Category.UNKNOWN && it.question != null }
        val answerCategory = when (clean.lowercase()) {
            "ux", "ui", "ux design", "design", "design reference" -> Category.UX_DESIGN
            "product", "buy", "want to buy", "shopping" -> Category.PRODUCT
            "card", "debit card", "credit card" -> Category.CARD
            "document", "documents" -> Category.DOCUMENT
            "note" -> Category.NOTE
            else -> null
        }
        if (voice == null && uris.isEmpty() && unresolved != null && answerCategory != null) {
            store.save(ChatMessage(UUID.randomUUID().toString(), Role.USER, clean, System.currentTimeMillis()))
            categorizeInternal(unresolved, answerCategory); return
        }
        val imported = mutableListOf<Attachment>()
        try { uris.forEach { imported += vault.import(it) } }
        catch (error: Exception) { imported.forEach(vault::delete); throw error }

        val id = appendTo ?: voice?.id ?: UUID.randomUUID().toString()
        val original = appendTo?.let { itemId -> existing.firstOrNull { it.id == itemId } }
        var memory = original?.copy(files = original.files + imported, status = "Reading attachment")
            ?: Memory(id, clean.take(80).ifBlank { imported.firstOrNull()?.name ?: "Saved item" }, clean,
                category ?: Category.UNKNOWN, files = imported, status = "Sorting")
        store.transaction {
            store.save(memory)
            store.save(ChatMessage(UUID.randomUUID().toString(), Role.USER,
                clean, System.currentTimeMillis(), attachmentId = id, fileIds = imported.map { it.id }))
        }
        publish() // Persist and display before OCR or network work starts.
        val warnings = mutableListOf<String>()
        val recognized = uris.zip(imported).map { (uri, file) ->
            try {
                when {
                    file.mime.startsWith("image/") -> ocr.read(getApplication(), uri)
                    file.mime == "application/pdf" -> ocr.readPdf(getApplication(), uri)
                    file.mime.startsWith("text/") -> getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val buffer = CharArray(20000)
                        var count = 0
                        while (count < buffer.size) {
                            val read = reader.read(buffer, count, buffer.size - count)
                            if (read < 0) break
                            count += read
                        }
                        String(buffer, 0, count)
                    }.orEmpty()
                    else -> ""
                }
            } catch (_: Exception) { warnings += "Could not read ${file.name}; the original is saved."; "" }
        }.joinToString("\n").take(40000)
        val combined = listOf(clean, original?.ocr.orEmpty(), recognized).filter { it.isNotBlank() }.joinToString("\n")
        val isImage = imported.any { it.mime.startsWith("image/") }
        var intent = engine.classify(combined, imported.firstOrNull()?.name, imported.firstOrNull()?.mime, isImage)
        val identityKind = reviewedIdentity?.kind ?: original?.identity?.kind ?: IdentityReader.detect(combined)
        val forced = category ?: original?.category ?: identityKind?.let { Category.DOCUMENT }
        if (forced != null) intent = intent.copy(category = forced, clarificationQuestion = null, alternatives = emptyList())
        val settings = store.settings()
        val apiKey = settings.optString("key")
        val sensitive = original?.staysOnDevice == true || identityKind != null || forced == Category.CARD || intent.category == Category.CARD || CardExtractor.sensitive(combined)
        val feedback = if (!sensitive && forced == null && imported.isEmpty())
            PersonalLabels.fromHistory(existing, history) else emptyList()
        if (feedback.isNotEmpty()) intent = PersonalLabels.apply(clean, intent, feedback)
        // Unreadable images require user classification before any cloud upload.
        val cloudAllowed = settings.optBoolean("cloud") && apiKey.isNotBlank() && !sensitive &&
            (!isImage || recognized.isNotBlank() || forced in listOf(Category.UX_DESIGN, Category.PRODUCT))
        if (cloudAllowed && forced == null && intent.category != Category.APK &&
            (isImage || intent.confidence < .8f)) {
            try {
                val imageUri = uris.zip(imported).firstOrNull { it.second.mime.startsWith("image/") }?.first
                intent = gemini.classify(apiKey, settings.optString("model", "gemini-3.8-flash"), clean,
                    recognized, imageUri?.let { imageForAi(it) }, if (imageUri == null) null else "image/jpeg",
                    examples = feedback)
            } catch (_: Exception) { warnings += "AI is unavailable. Saved using local sorting; you can change the category." }
        } else if (isImage && forced == null && intent.category == Category.UNKNOWN && !settings.optBoolean("cloud")) {
            warnings += "Visual AI is off. Choose a category, or enable Gemini in Settings."
        }
        val effectiveCategory = intent.category
        val extracted = if (effectiveCategory == Category.CARD) CardExtractor.extract(combined) else null
        val card = reviewedCard ?: extracted?.let { fresh -> original?.card?.let { old ->
            old.copy(issuer = old.issuer.ifBlank { fresh.issuer }, holder = old.holder.ifBlank { fresh.holder },
                number = old.number.ifBlank { fresh.number }, expiry = old.expiry.ifBlank { fresh.expiry },
                cvv = old.cvv.ifBlank { fresh.cvv }, network = old.network.ifBlank { fresh.network })
        } ?: fresh }
        val identity = if (effectiveCategory == Category.DOCUMENT && identityKind != null)
            reviewedIdentity ?: IdentityReader.extract(combined, identityKind, original?.identity) else null
        val palette = original?.palette?.takeIf { it.size == 3 } ?: if (card != null || identity != null)
            uris.zip(imported).firstOrNull { it.second.mime.startsWith("image/") }?.first?.let {
                runCatching { com.gyftalala.omni.ui.PhotoPalette.fromUri(getApplication(), it) }.getOrDefault(emptyList())
            }.orEmpty() else emptyList()
        memory = memory.copy(category = effectiveCategory, identity = identity, palette = palette,
            privateToDevice = memory.staysOnDevice || original?.staysOnDevice == true || card != null || identity != null,
            priority = voice?.priority ?: original?.priority ?: TaskHints.priority(clean),
            tag = voice?.tag ?: original?.tag ?: TaskHints.tag(clean),
            title = if (original != null) original.title else if (identity != null) "${identity.kind.label} · ${identity.name.ifBlank { "My ID" }}" else if (card != null) "${card.issuer.ifBlank { "Payment" }} ${card.type.lowercase()}" else intent.title,
            ocr = listOf(original?.ocr.orEmpty(), recognized).joinToString("\n").trim(), card = card,
            question = when (effectiveCategory) { Category.REMINDER -> "When should I remind you?"; Category.UNKNOWN -> intent.clarificationQuestion ?: "Where should I save this?"; else -> null },
            alternatives = intent.alternatives, status = if (effectiveCategory == Category.REMINDER) "Needs a time" else "Saved")
        store.save(memory)
        val parsed = if (effectiveCategory == Category.REMINDER) (voice?.at?.let { ParsedTime(it) } ?: if (voice != null) VoiceParser.time(clean) else TimeParser.parse(clean)) else null
        if (parsed != null) scheduleInternal(memory, parsed)
        else reply(when (effectiveCategory) {
            Category.REMINDER -> "“${memory.title}” sounds like a task. When should I remind you?"
            Category.UNKNOWN -> memory.question!!
            Category.CARD -> "Saved to your card wallet. Check the scanned details; you can correct them or add the other side."
            else -> "Saved to ${effectiveCategory.label.lowercase()}."
        } + warnings.joinToString(prefix = if (warnings.isEmpty()) "" else "\n\n", separator = "\n"), id)
    }

    fun categorize(id: String, category: Category) = work {
        store.memories().firstOrNull { it.id == id }?.let { categorizeInternal(it, category) }
    }
    fun requestClock(messageId: String) = work {
        val message = store.messages().firstOrNull { it.id == messageId && it.role == Role.ASSISTANT } ?: return@work
        val alarm = ClockAlarm.decode(message.action) ?: return@work
        mutable.value = mutable.value.copy(clockLaunch = ClockLaunch(messageId, alarm))
    }
    fun takeClockLaunch(messageId: String): ClockLaunch? {
        val launch = mutable.value.clockLaunch?.takeIf { it.messageId == messageId } ?: return null
        mutable.value = mutable.value.copy(clockLaunch = null)
        return launch
    }
    fun clearClockLaunch() { mutable.value = mutable.value.copy(clockLaunch = null) }
    fun clockResult(launch: ClockLaunch, error: String? = null) = work {
        val message = store.messages().firstOrNull { it.id == launch.messageId } ?: return@work
        val alarm = ClockAlarm.decode(message.action) ?: return@work
        if (error != null) {
            store.save(message.copy(text = "Clock could not be opened. $error", action = alarm.encode()))
        } else if (!alarm.waiting) {
            store.save(message.copy(text = if (alarm.at == 0L) "Opened Clock. Manage your alarms there."
                else "Alarm · ${alarm.description()}\nOpened Clock. Check that the alarm is saved and enabled there.",
                action = alarm.copy(opened = true).encode()))
        }
    }
    private suspend fun categorizeInternal(memory: Memory, category: Category) {
        if (memory.category == category && memory.question == null) {
            if (PersonalLabels.canLearn(memory)) store.save(memory.copy(categoryConfirmedAt = System.currentTimeMillis()))
            return
        }
        if (memory.category == Category.REMINDER && category != Category.REMINDER) {
            scheduler.cancel(memory.id); store.delete("reminder:${memory.id}")
        }
        var updated = memory.copy(category = category, alternatives = emptyList(),
            privateToDevice = memory.staysOnDevice,
            identity = if (category == Category.DOCUMENT) memory.identity else null,
            question = if (category == Category.REMINDER) "When should I remind you?" else null,
            status = if (category == Category.REMINDER) "Needs a time" else "Saved",
            card = if (category == Category.CARD) memory.card ?: CardExtractor.extract(memory.text + "\n" + memory.ocr) else null)
        val learned = PersonalLabels.canLearn(updated)
        if (learned) updated = updated.copy(categoryConfirmedAt = System.currentTimeMillis())
        store.save(updated)
        // An unreadable image can be described after the owner identifies it as shopping or design.
        val settings = store.settings()
        val picture = updated.files.firstOrNull { it.mime.startsWith("image/") && it.size <= 24 * 1024 * 1024 }
        if (!updated.staysOnDevice && category in listOf(Category.PRODUCT, Category.UX_DESIGN) && picture != null &&
            settings.optBoolean("cloud") && settings.optString("key").isNotBlank() &&
            updated.identity == null && IdentityReader.detect(updated.text + "\n" + updated.ocr) == null && !CardExtractor.sensitive(updated.text + "\n" + updated.ocr)) {
            publish()
            try {
                val bytes = vault.read(picture)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: kotlin.error("Unsupported image")
                val image = try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it); it.toByteArray() } }
                    finally { bitmap.recycle() }
                val result = gemini.classify(settings.getString("key"), settings.optString("model", "gemini-3.8-flash"),
                    updated.text, updated.ocr, image, "image/jpeg")
                updated = updated.copy(title = result.title.ifBlank { updated.title })
                store.save(updated)
            } catch (_: Exception) { reply("Category saved. AI description is unavailable right now.", memory.id) }
        }
        reply(if (category == Category.REMINDER) "When should I remind you about “${updated.title}”?" else "Moved to ${category.label.lowercase()}." + if (learned) " I'll use this choice for similar messages." else "", memory.id)
    }
    fun schedule(id: String, at: Long, repeat: String = "none") = work {
        require(at > System.currentTimeMillis()) { "Choose a future date and time." }
        store.memories().firstOrNull { it.id == id }?.let { scheduleInternal(it, ParsedTime(at, repeat)) }
    }
    private fun scheduleInternal(memory: Memory, time: ParsedTime) {
        val reminder = Reminder(memory.id, memory.title, time.at, repeat = time.repeat,
            kind = ReminderProfile.kind("${memory.title}\n${memory.text}"), callStyle = true, anchorAt = time.at)
        val exact = scheduler.schedule(reminder)
        val formatted = Instant.ofEpochMilli(time.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, d MMM 'at' h:mm a z"))
        val recurrence = when {
            time.repeat == "daily" -> ", repeating daily"
            time.repeat == "weekdays" -> ", repeating on weekdays"
            time.repeat == "weekends" -> ", repeating on weekends"
            time.repeat.startsWith("weekly:") -> ", repeating every ${time.repeat.substringAfter(':').lowercase().replaceFirstChar(Char::uppercase)}"
            else -> ""
        }
        store.transaction {
            store.save(reminder)
            store.save(memory.copy(category = Category.REMINDER, question = null, status = formatted))
            reply("I'll call you with a ${reminder.kind.label.lowercase()} reminder: $formatted$recurrence." +
                (if (!exact) "\nEnable Alarms & reminders in Settings for precise delivery. Android may delay this reminder until then." else "") +
                (if (!scheduler.notificationsAllowed()) "\nNotifications are off. Enable them in Settings to receive alerts outside the app." else ""), memory.id)
        }
    }
    fun complete(id: String) = work {
        val memory = store.memories().firstOrNull { it.id == id } ?: return@work
        if (memory.status == "Done") return@work
        if (store.reminders().none { it.id == id }) {
            store.save(memory.copy(status = "Done", question = null))
            reply("Marked as done.", id)
        } else ReminderActions.complete(getApplication(), id)
    }
    fun restoreCompleted(id: String) = work {
        store.reminders().firstOrNull { it.id == id }?.let { store.save(it.copy(completed = false)) }
        store.memories().firstOrNull { it.id == id }?.let { store.save(it.copy(status = "Saved")) }
        reply("Moved back to your active items.", id)
    }
    fun delete(id: String) = work {
        val memory = store.memories().firstOrNull { it.id == id } ?: return@work
        deleteMemories(listOf(memory))
    }
    fun reorderMemories(ids: List<String>) = work {
        val byId = store.memories().associateBy { it.id }
        val maxOrder = (byId.values.maxOfOrNull { it.sortOrder } ?: System.currentTimeMillis()) + ids.size + 1
        store.transaction {
            ids.forEachIndexed { index, id -> byId[id]?.let { store.save(it.copy(sortOrder = maxOrder - index)) } }
        }
    }
    private fun deleteMemories(memories: List<Memory>) {
        val ids = memories.map { it.id }.toSet()
        ids.forEach(scheduler::cancel)
        store.transaction {
            ids.forEach { store.delete(it); store.delete("reminder:$it") }
            store.messages().filter { it.attachmentId in ids }.forEach { store.delete(it.id) }
        }
        memories.flatMap { it.files }.forEach(vault::delete)
    }
    fun cancelReminderDeletion(requestId: String) = work {
        if (mutable.value.reminderDeletion?.id != requestId) return@work
        mutable.value = mutable.value.copy(reminderDeletion = null)
        reply("Nothing deleted.")
    }
    fun confirmReminderDeletion(requestId: String, selectedIds: Set<String>) = work {
        val request = mutable.value.reminderDeletion?.takeIf { it.id == requestId } ?: return@work
        require(selectedIds.isNotEmpty() && request.candidates.map { it.id }.containsAll(selectedIds)) { "Choose reminders from this list." }
        val current = store.memories().associateBy { it.id }
        val selected = request.candidates.filter { it.id in selectedIds }
        if (selected.any { current[it.id] != it }) {
            mutable.value = mutable.value.copy(reminderDeletion = null)
            reply("These reminders changed. Ask again to review the latest list; nothing deleted.")
            return@work
        }
        deleteMemories(selected)
        mutable.value = mutable.value.copy(reminderDeletion = null)
        reply("Deleted ${selected.size} ${if (selected.size == 1) "reminder" else "reminders"}.")
    }
    fun edit(id: String, title: String, card: CardDetails?, identity: IdentityDetails? = null) = work {
        store.memories().firstOrNull { it.id == id }?.let { memory ->
            store.save(memory.copy(title = title.trim().ifBlank { memory.title }, card = card, identity = identity ?: memory.identity,
                privateToDevice = memory.staysOnDevice || card != null || identity != null))
            store.reminders().firstOrNull { it.id == id }?.let { store.save(it.copy(title = title.trim().ifBlank { memory.title })) }
        }
    }
    fun refreshWallet() = work {
        for (memory in store.memories()) {
            val identity = memory.identity ?: if (memory.category == Category.DOCUMENT)
                IdentityReader.detect(memory.ocr)?.let { IdentityReader.extract(memory.ocr, it) } else null
            if (memory.category != Category.CARD && identity == null) continue
            var palette = memory.palette
            if (palette.isEmpty()) memory.files.firstOrNull { it.mime.startsWith("image/") && it.size <= 24 * 1024 * 1024 }?.let { file ->
                palette = runCatching {
                    val bitmap = com.gyftalala.omni.ui.ImagePreviews.decode(vault.read(file))
                    try { com.gyftalala.omni.ui.PhotoPalette.fromBitmap(bitmap) } finally { bitmap.recycle() }
                }.getOrDefault(emptyList())
            }
            if (identity != memory.identity || palette != memory.palette) store.save(memory.copy(identity = identity, palette = palette))
        }
    }

    fun taskDetails(id: String, priority: TaskPriority, tag: TopicTag) = work {
        store.memories().firstOrNull { it.id == id }?.let { store.save(it.copy(priority = priority, tag = tag)) }
    }

    suspend fun suggestVoice(text: String): List<VoiceItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val settings = store.settings()
            val feedback = PersonalLabels.fromHistory(store.memories(), store.messages())
            VoiceParser.parse(text).map { original ->
                val predicted = engine.classify(original.text)
                val local = PersonalLabels.apply(original.text, predicted, feedback)
                val item = if (!original.query && ReminderCommand.parse(original.text) == null && AlarmCommand.parse(original.text) == null)
                    original.copy(category = if (local != predicted) local.category else original.category) else original
                if (ReminderCommand.parse(item.text) == null && !item.query && local.confidence < .8f && item.category == local.category && settings.optBoolean("cloud") &&
                    settings.optString("key").isNotBlank() && IdentityReader.detect(item.text) == null && !CardExtractor.sensitive(item.text)) {
                    try { item.copy(category = gemini.classify(settings.getString("key"), settings.optString("model", "gemini-3.8-flash"), item.text, "", examples = feedback).category) }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; item }
                } else item
            }
        }
    }

    suspend fun saveVoice(items: List<VoiceItem>): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val selected = items.filter { it.selected }
            require(selected.isNotEmpty()) { "Select at least one item." }
            require(selected.size <= 12 && selected.all { it.text.isNotBlank() && it.text.length <= 16000 }) { "Check the items before saving." }
            require(selected.all { ReminderCommand.parse(it.text) != null || it.at == null || it.at > System.currentTimeMillis() }) { "Choose a future reminder time." }
            val deletions = selected.count { ReminderCommand.parse(it.text) != null }
            require(deletions <= 1) { "Review one reminder deletion request at a time." }
            mutable.value = mutable.value.copy(busy = true, error = null)
            try {
                for (item in selected) {
                    if (store.memories().none { it.id == item.id }) sendInternal(item.text, category = item.category, voice = item)
                }
                publish()
                val searches = selected.count { it.query }
                val count = selected.size - searches - deletions
                if (deletions > 0) "${if (count > 0) "Saved $count items. " else ""}" +
                    if (mutable.value.reminderDeletion != null) "Review the reminder deletion in chat." else "Reminder request handled. Details are in chat."
                else if (count == 0) "Search complete. Results are in chat."
                else "Saved $count ${if (count == 1) "item" else "items"}." +
                    if (selected.any { it.category == Category.REMINDER && it.at == null && VoiceParser.time(it.text) == null })
                        " Choose a time in chat for reminders that need one." else ""
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun settings(key: String?, model: String, cloud: Boolean) = work {
        require(model.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Enter a valid Gemini model ID." }
        val settings = store.settings()
        val changedModel = settings.optString("model", "gemini-3.8-flash") != model
        key?.let { settings.put("key", it.trim()) }
        settings.put("model", model); settings.put("cloud", cloud && settings.optString("key").isNotBlank())
        store.saveSettings(settings)
        if (key != null || changedModel) mutable.value = mutable.value.copy(modelCheck = null)
        if (key != null) mutable.value = mutable.value.copy(availableModels = emptyList(), modelListStatus = null)
    }
    fun loadModels(key: String?) = work {
        mutable.value = mutable.value.copy(modelListStatus = "Finding models for your key…", loadingModels = true)
        try {
            val models = gemini.listModels(key?.trim()?.takeIf { it.isNotBlank() } ?: store.settings().optString("key"))
            mutable.value = mutable.value.copy(availableModels = models,
                modelListStatus = if (models.isEmpty()) "No compatible models returned. Check project access in AI Studio."
                else "Models available to your key. Check connection after choosing one.")
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            mutable.value = mutable.value.copy(modelListStatus = error.message?.take(180) ?: "Could not load models. Try again.")
        } finally { mutable.value = mutable.value.copy(loadingModels = false) }
    }
    fun checkModel(key: String?, name: String) = work {
        mutable.value = mutable.value.copy(modelCheck = ModelCheck(name, checking = true, message = "Checking connection…"))
        try {
            val start = android.os.SystemClock.elapsedRealtime()
            val result = gemini.classify(key?.trim()?.takeIf { it.isNotBlank() } ?: store.settings().optString("key"),
                name.trim(), "USB Type-C to Type-C cable", "")
            check(result.category == Category.PRODUCT) { "Gemini responded, but the sample classification needs review." }
            val seconds = (android.os.SystemClock.elapsedRealtime() - start) / 1000.0
            mutable.value = mutable.value.copy(modelCheck = ModelCheck(name, passed = true,
                message = "Sample sorted correctly in ${String.format(java.util.Locale.US, "%.1f", seconds)} s. No saved items sent."))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            mutable.value = mutable.value.copy(modelCheck = ModelCheck(name,
                message = error.message?.take(180) ?: "Could not reach Gemini. Check your connection and try again."))
        }
    }
    fun error(message: String?) { mutable.value = mutable.value.copy(error = message) }
    private suspend fun imageForAi(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bitmap = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: kotlin.error("Unsupported image")
        try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }
    override fun onCleared() { ocr.close(); store.close() }
}

object MemorySearch {
    fun find(memories: List<Memory>, query: String): List<Memory> {
        val normalized = query.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        val stop = setOf("my", "me", "the", "a", "an", "all", "saved", "earlier", "please", "of", "for", "photos", "photo", "images", "image", "files", "file")
        val terms = normalized.split(' ').filter { it.isNotBlank() && it !in stop }.map {
            when (it) { "cards" -> "card"; "documents" -> "document"; "apks" -> "apk"; "reminders" -> "reminder"; "products", "shopping" -> "product"; "designs" -> "design"; else -> it }
        }
        return memories.mapNotNull { memory ->
            val haystack = if (memory.staysOnDevice) {
                // Wallet lookup uses labels only; OCR and private numbers are not a search corpus.
                "${memory.title} ${memory.category.label} ${memory.card?.issuer.orEmpty()} ${memory.card?.type.orEmpty()} ${memory.identity?.kind?.label.orEmpty()}".lowercase()
            } else "${memory.title} ${memory.text} ${memory.ocr} ${memory.category.name} ${memory.category.label} ${memory.files.joinToString { it.name }} ${memory.card?.issuer.orEmpty()} ${memory.card?.type.orEmpty()} ${memory.identity?.kind?.label.orEmpty()} ${memory.identity?.number.orEmpty()} ${memory.identity?.name.orEmpty()} ${memory.priority.label} ${memory.tag.label}".lowercase()
            if (terms.all { haystack.contains(it) }) memory to terms.count { memory.title.contains(it, true) } else null
        }.sortedWith(compareByDescending<Pair<Memory, Int>> { it.second }.thenByDescending { it.first.createdAt }).map { it.first }
    }
}
