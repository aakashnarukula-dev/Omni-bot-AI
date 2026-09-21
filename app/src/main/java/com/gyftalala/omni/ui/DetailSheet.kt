@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gyftalala.omni.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.TimePicker
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.gyftalala.omni.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

fun chooseTime(context: Context, initialAt: Long? = null, chosen: (Long) -> Unit) {
    val now = ZonedDateTime.now()
    val saved = initialAt?.let { Instant.ofEpochMilli(it).atZone(now.zone) }
    val initial = saved?.takeIf { it.isAfter(now) } ?: now.plusMinutes(5)
    DatePickerDialog(context, com.gyftalala.omni.R.style.Theme_Omni_Dialog, { _, year, month, day ->
        var hour = initial.hour
        var minute = initial.minute
        object : TimePickerDialog(context, com.gyftalala.omni.R.style.Theme_Omni_Dialog, null,
            initial.hour, initial.minute, android.text.format.DateFormat.is24HourFormat(context)) {
            override fun onTimeChanged(view: TimePicker, selectedHour: Int, selectedMinute: Int) {
                super.onTimeChanged(view, selectedHour, selectedMinute)
                hour = selectedHour; minute = selectedMinute
            }
        }.apply {
            setTitle("Choose time")
            setButton(TimePickerDialog.BUTTON_POSITIVE, "Save", this)
            setOnShowListener {
                getButton(TimePickerDialog.BUTTON_POSITIVE).setOnClickListener {
                    window?.decorView?.findFocus()?.clearFocus()
                    val at = LocalDate.of(year, month + 1, day).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    if (at <= System.currentTimeMillis()) setTitle("Choose a future time")
                    else { chosen(at); dismiss() }
                }
            }
        }.show()
    }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
        setTitle("Choose date")
        datePicker.minDate = System.currentTimeMillis() - 1000
        setButton(DatePickerDialog.BUTTON_POSITIVE, "Next", this)
    }.show()
}

@Composable internal fun DetailSheet(memory: Memory, reminder: Reminder?, previews: ImagePreviews,
    dismiss: () -> Unit, export: (Attachment) -> Unit, exportCard: () -> Unit, attach: (String) -> Unit,
    categorize: (Category) -> Unit, save: (String, CardDetails?, IdentityDetails?) -> Unit, delete: () -> Unit,
    schedule: (Long, String) -> Unit, priority: (TaskPriority) -> Unit, complete: () -> Unit) {
    var renaming by remember(memory.id) { mutableStateOf(false) }
    var editingDetails by remember(memory.id) { mutableStateOf(false) }
    var title by remember(memory.id, memory.title) { mutableStateOf(memory.title) }
    var card by remember(memory.id, memory.card) { mutableStateOf(memory.card) }
    var identity by remember(memory.id, memory.identity) { mutableStateOf(memory.identity) }
    var categoryMenu by remember { mutableStateOf(false) }
    var priorityMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var original by remember(memory.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val isReminder = memory.category == Category.REMINDER
    val done = memory.status == "Done" || reminder?.completed == true
    val daily = reminder?.repeat == "daily"
    fun saveTitle() {
        if (title.isNotBlank()) { save(title.trim(), memory.card, memory.identity); renaming = false; keyboard?.hide() }
    }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Ink,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().testTag("item-detail")) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp).padding(bottom = 20.dp)) {
                Box {
                    Row(Modifier.testTag("detail-category").heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = "Change category") { categoryMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(memory.category.icon(), null, Modifier.size(18.dp), tint = memory.category.tint())
                        Text(memory.category.label, color = memory.category.tint(), style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 8.dp, end = 4.dp))
                        Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = Muted)
                    }
                    DropdownMenu(categoryMenu, { categoryMenu = false }) {
                        Category.entries.filter { it != Category.UNKNOWN }.forEach { category ->
                            DropdownMenuItem(text = { Text(category.label) }, onClick = { categoryMenu = false; categorize(category) },
                                leadingIcon = { Icon(category.icon(), null, tint = category.tint()) },
                                trailingIcon = { if (category == memory.category) Icon(Icons.Rounded.Check, null) })
                        }
                    }
                }
                if (renaming) {
                    OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("detail-title-input"),
                        label = { Text("Name") }, textStyle = MaterialTheme.typography.headlineSmall,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { saveTitle() }))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OmniTextButton({ title = memory.title; renaming = false; keyboard?.hide() }) { Text("Cancel") }
                        OmniTextButton({ saveTitle() }, enabled = title.isNotBlank()) { Text("Save name") }
                    }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                } else Text(memory.title, style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("detail-title")
                        .clickable(onClickLabel = "Rename item") { title = memory.title; renaming = true }.padding(vertical = 4.dp))

                if (isReminder) {
                    Spacer(Modifier.height(22.dp))
                    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp))) {
                        Row(Modifier.fillMaxWidth().testTag("reminder-schedule")
                            .clickable(onClickLabel = "Change date and time") {
                                chooseTime(context, reminder?.triggerAt) { schedule(it, if (daily) "daily" else "none") }
                            }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CalendarToday, null, Modifier.size(22.dp), tint = Accent)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text("Date & time", style = MaterialTheme.typography.labelMedium, color = Muted)
                                Text(reminder?.let {
                                    Instant.ofEpochMilli(it.triggerAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a"))
                                } ?: "Choose when", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                            }
                            Text(if (reminder == null) "Set" else "Change", color = Accent, style = MaterialTheme.typography.labelMedium)
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = Outline)
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Repeat, null, Modifier.size(22.dp), tint = Muted)
                            Text(when (reminder?.repeat) {
                                "weekdays" -> "Repeat weekdays"
                                "weekends" -> "Repeat weekends"
                                else -> "Repeat daily"
                            }, Modifier.weight(1f).padding(start = 14.dp), style = MaterialTheme.typography.bodyMedium)
                            Switch(daily, { enabled -> reminder?.let { schedule(it.triggerAt, if (enabled) "daily" else "none") } },
                                enabled = reminder != null && !done && reminder.triggerAt > System.currentTimeMillis(),
                                modifier = Modifier.testTag("reminder-repeat"))
                        }
                        reminder?.let {
                            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = Outline)
                            Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(22.dp), tint = Accent)
                                Column(Modifier.padding(start = 14.dp)) {
                                    Text("${it.kind.label} · Reminder call", style = MaterialTheme.typography.bodyMedium)
                                    Text("Done or remind again in 5, 15, 30, or 60 minutes", color = Muted,
                                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp))
                                }
                            }
                        }
                    }
                    if (done) Text("Completed", color = Mint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                    else if (reminder == null) Text("Set a time to receive a reminder.", color = Muted,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                    else if (reminder.delivered) Text("Reminder sent", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("reminder-priority")
                        .clickable(onClickLabel = "Change priority") { priorityMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                        Text("Priority", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodyMedium)
                        // Keep popup anchor at trailing edge. Anchoring to full-width row places menu at sheet start.
                        Box(Modifier.wrapContentWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(memory.priority.label, color = if (memory.priority == TaskPriority.URGENT) Accent else Body, style = MaterialTheme.typography.bodyMedium)
                                Icon(Icons.Rounded.ExpandMore, null, Modifier.padding(start = 4.dp).size(18.dp), tint = Muted)
                            }
                            DropdownMenu(priorityMenu, { priorityMenu = false }) {
                                TaskPriority.entries.forEach { value -> DropdownMenuItem(text = { Text(value.label) },
                                    onClick = { priorityMenu = false; priority(value) }) }
                            }
                        }
                    }
                }
                if (memory.category == Category.CARD) {
                    Spacer(Modifier.height(16.dp))
                    VirtualCard(memory)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OmniTextButton({ attach("card_scan") }) { Text("Scan other side") }
                        OmniTextButton(exportCard) { Text("Save card image") }
                    }
                }
                if (memory.identity != null) {
                    IdentityCard(memory, previews)
                    OmniTextButton({ attach("card_scan") }) { Text("Scan other side") }
                }
                if (memory.card != null || memory.identity != null) OmniTextButton({ editingDetails = !editingDetails }) {
                    Text(if (editingDetails) "Cancel editing" else "Edit card details")
                }
                if (editingDetails) {
                    card?.let { current ->
                        listOf("Bank / issuer" to current.issuer, "Cardholder" to current.holder, "Number" to current.number,
                            "Expiry" to current.expiry, "CVV" to current.cvv, "Type" to current.type, "Network" to current.network).forEachIndexed { index, (label, value) ->
                            OutlinedTextField(value, { text -> card = when (index) {
                                0 -> current.copy(issuer = text); 1 -> current.copy(holder = text); 2 -> current.copy(number = text.filter(Char::isDigit).take(19))
                                3 -> current.copy(expiry = text); 4 -> current.copy(cvv = text.filter(Char::isDigit).take(4)); 5 -> current.copy(type = text); else -> current.copy(network = text)
                            } }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                        }
                    }
                    identity?.let { IdentityFields(it) { changed -> identity = changed } }
                    OmniButton({ save(memory.title, card, identity); editingDetails = false }, modifier = Modifier.padding(vertical = 12.dp)) { Text("Save details") }
                }
                if (memory.text.isNotBlank() && (!isReminder || !memory.text.equals(memory.title, ignoreCase = true))) {
                    if (isReminder) OmniTextButton({ original = !original }) {
                        Text("Original message", color = Muted)
                        Icon(if (original) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = Muted)
                    }
                    if (!isReminder || original) SelectionContainer { Text(memory.text, color = Body, modifier = Modifier.padding(vertical = 12.dp)) }
                }
                Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(memory.text)?.value?.let { link ->
                    OmniTextButton({ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } }) {
                        Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Open saved link")
                    }
                }
                if (memory.files.isNotEmpty()) {
                    Text("Original files", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
                    memory.files.forEach { file ->
                        if (file.mime.startsWith("image/")) VaultImage(file, previews, Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp))
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(file.name, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                                Text(android.text.format.Formatter.formatFileSize(context, file.size), color = Muted, style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton({ export(file) }) { Icon(Icons.Rounded.FileDownload, "Save original ${file.name}") }
                        }
                    }
                }
                if (memory.ocr.isNotBlank()) {
                    var showOcr by remember { mutableStateOf(false) }
                    OmniTextButton({ showOcr = !showOcr }) { Text(if (showOcr) "Close extracted text" else "View extracted text") }
                    if (showOcr) SelectionContainer { Text(memory.ocr, color = Body, style = MaterialTheme.typography.bodySmall) }
                }
            }
            HorizontalDivider(color = Outline)
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp).testTag("detail-actions"),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                OmniTextButton({ confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("detail-delete")) {
                    Text("Delete item", color = MaterialTheme.colorScheme.error)
                }
                if (isReminder) OmniButton(complete, enabled = !done, modifier = Modifier.weight(1f).heightIn(min = 50.dp).testTag("reminder-complete")) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (done) "Done" else "Mark done")
                }
            }
        }
    }
    if (confirmDelete) DeleteItemDialog(memory, { confirmDelete = false }, delete)
}

@Composable internal fun DeleteItemDialog(memory: Memory, dismiss: () -> Unit, delete: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Delete this item?") },
        text = { Text("“${memory.title}” and its attached files and reminder will be permanently deleted from this device.") },
        confirmButton = { OmniTextButton(delete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { OmniTextButton(dismiss) { Text("Cancel") } })
}
