package com.gyftalala.omni.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gyftalala.omni.ReminderDeletion

@Composable fun ReminderDeletionDialog(request: ReminderDeletion, busy: Boolean, cancel: () -> Unit, confirm: (Set<String>) -> Unit) {
    var selected by rememberSaveable(request.id) {
        mutableStateOf(if (request.candidates.size == 1) listOf(request.candidates.single().id) else emptyList())
    }
    AlertDialog(onDismissRequest = { if (!busy) cancel() }, title = { Text("Delete reminders?") },
        text = { Column {
            Text("Choose reminders to remove. Deleting also removes their saved files and linked chat messages. This cannot be undone.")
            OmniTextButton(enabled = !busy, onClick = {
                selected = if (selected.size == request.candidates.size) emptyList() else request.candidates.map { it.id }
            }) { Text(if (selected.size == request.candidates.size) "Clear selection" else "Select all") }
            LazyColumn(Modifier.heightIn(max = 320.dp).testTag("reminder-deletion-list")) {
                items(request.candidates, key = { it.id }) { memory ->
                    fun toggle() { selected = if (memory.id in selected) selected - memory.id else selected + memory.id }
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = ::toggle).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(memory.id in selected, { toggle() }, enabled = !busy, modifier = Modifier.testTag("delete-reminder-${memory.id}"))
                        Column(Modifier.weight(1f)) {
                            Text(memory.title, color = Paper)
                            Text(memory.status, color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } },
        confirmButton = { OmniTextButton(enabled = !busy && selected.isNotEmpty(), onClick = { confirm(selected.toSet()) }) {
            Text("Delete ${selected.size} ${if (selected.size == 1) "reminder" else "reminders"}")
        } },
        dismissButton = { OmniTextButton(enabled = !busy, onClick = cancel) { Text("Keep reminders") } })
}
