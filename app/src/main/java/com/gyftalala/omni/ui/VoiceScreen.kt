package com.gyftalala.omni.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gyftalala.omni.OmniViewModel
import com.gyftalala.omni.ai.TimeParser
import com.gyftalala.omni.data.*
import com.gyftalala.omni.voice.VoiceInput
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun VoiceScreen(allowed: Boolean, requestMic: () -> Unit, openSettings: () -> Unit, model: OmniViewModel,
    close: () -> Unit, saved: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val input = remember { VoiceInput(context) }
    var text by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var language by remember { mutableStateOf("en-IN") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<VoiceItem>?>(null) }
    DisposableEffect(input, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { input.cancel(); listening = false } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { input.cancel(); lifecycle.lifecycle.removeObserver(observer) }
    }
    BackHandler { if (!working && items != null) items = null else if (!working) close() }
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (items != null) items = null else close() }, enabled = !working) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close voice capture")
                }
                Text(if (items == null) "Speak to Omni" else "Review your items", style = MaterialTheme.typography.headlineSmall)
            }
            if (items != null) VoiceReview(items!!, working, error, { items = it }, {
                working = true; error = null
                scope.launch {
                    try { val message = model.saveVoice(items!!); saved(message) }
                    catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        error = failure.message ?: "Could not save. Please try again."
                    } finally { working = false }
                }
            }) else Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("A few words. Everything in place.", style = MaterialTheme.typography.headlineSmall)
                Text("Say a task, something to buy, or ask for a saved item. Review each result before saving.", color = Muted)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("en-IN" to "English", "hi-IN" to "Hindi", "te-IN" to "Telugu")) { (code, label) ->
                        OmniFilterChip(language == code, { if (!listening) language = code }, { Text(label) })
                    }
                }
                Text(if (input.onDevice) "Speech is recognized on this phone." else "Android speech service may process audio online.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Canvas(Modifier.fillMaxWidth().height(64.dp).background(Panel, RoundedCornerShape(20.dp))) {
                    val count = 28
                    val step = size.width / (count + 1)
                    repeat(count) { i ->
                        val envelope = (1f - kotlin.math.abs(i - count / 2f) / (count / 2f)).coerceAtLeast(.15f)
                        val height = 6.dp.toPx() + if (listening) level * size.height * .75f * envelope else 0f
                        drawLine(Accent, Offset(step * (i + 1), (size.height - height) / 2), Offset(step * (i + 1), (size.height + height) / 2), 3.dp.toPx(), StrokeCap.Round)
                    }
                }
                OutlinedTextField(text, { text = it.take(16000) }, label = { Text("Transcript") }, minLines = 3, maxLines = 8,
                    readOnly = listening || working, modifier = Modifier.fillMaxWidth().testTag("voice-transcript"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!allowed) {
                    OmniButton(onClick = requestMic, modifier = Modifier.fillMaxWidth()) { Text("Allow microphone") }
                    OmniTextButton(onClick = openSettings) { Text("Open microphone settings") }
                } else OmniButton(onClick = {
                    if (listening) input.stop() else {
                        error = null; listening = true
                        input.start(language, { text = it }, { level = it }, { reason -> listening = false; level = 0f; error = reason })
                    }
                }, enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(if (listening) Icons.Rounded.Stop else Icons.Rounded.MicNone, null)
                    Spacer(Modifier.width(10.dp)); Text(if (listening) "Stop recording" else "Start recording")
                }
                OmniButton(onClick = {
                    input.cancel(); listening = false; working = true; error = null
                    scope.launch {
                        try { items = model.suggestVoice(text) }
                        catch (failure: Exception) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            error = failure.message ?: "Could not sort the transcript. Try again."
                        } finally { working = false }
                    }
                }, enabled = text.isNotBlank() && !listening && !working, modifier = Modifier.fillMaxWidth()) {
                    if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Review items")
                }
            }
        }
    }
}

@Composable fun VoiceReview(items: List<VoiceItem>, working: Boolean, error: String?, edit: (List<VoiceItem>) -> Unit, save: () -> Unit) {
    val context = LocalContext.current
    fun change(item: VoiceItem) = edit(items.map { if (it.id == item.id) item else it })
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).testTag("voice-review-items"), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("${items.count { it.selected }} of ${items.size} selected. Edit or deselect anything.", color = Muted) }
            items(items, key = { it.id }) { item ->
                val deleting = com.gyftalala.omni.ai.ReminderCommand.parse(item.text) != null
                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(item.selected, { change(item.copy(selected = it)) }, enabled = !working, modifier = Modifier.testTag("voice-select-${item.id}"))
                        Text(if (deleting) "Delete reminders" else if (item.query) "Find saved items" else item.category.label, style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedTextField(item.text, { change(item.copy(text = it.take(16000), query = com.gyftalala.omni.ai.IntentEngine().isRetrievalQuery(it))) }, enabled = !working,
                        modifier = Modifier.fillMaxWidth().testTag("voice-item-${item.id}"), label = { Text("Message") })
                    if (deleting) Text("Choose matching reminders and confirm deletion in chat.", color = Muted)
                    if (!item.query && !deleting) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(Category.REMINDER, Category.PRODUCT, Category.NOTE, Category.DOCUMENT, Category.UX_DESIGN)) { category ->
                            OmniFilterChip(item.category == category, { if (!working) change(item.copy(category = category, at = null)) }, { Text(category.label) })
                        }
                    }
                    if (item.category == Category.REMINDER && !item.query && !deleting) {
                        TaskOptions(item.priority, item.tag) { priority, tag -> if (!working) change(item.copy(priority = priority, tag = tag)) }
                        val at = item.at ?: com.gyftalala.omni.ai.VoiceParser.time(item.text)?.at
                        Text(at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a")) }
                            ?: "Needs a time. Choose now or answer in chat after saving.", color = Muted)
                        OmniTextButton(onClick = { chooseTime(context) { change(item.copy(at = it)) } }, enabled = !working) { Text("Choose date & time") }
                    }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
        OmniButton(onClick = save, enabled = !working && items.any { it.selected }, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 52.dp)) {
            if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(if (items.any { it.selected && com.gyftalala.omni.ai.ReminderCommand.parse(it.text) != null }) "Continue to chat"
                else if (items.filter { it.selected }.all { it.query }) "Find items" else "Save selected items")
        }
    }
}
