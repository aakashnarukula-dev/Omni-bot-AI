package com.gyftalala.omni.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gyftalala.omni.data.*

@Composable fun TaskOptions(priority: TaskPriority, tag: TopicTag, change: (TaskPriority, TopicTag) -> Unit) {
    Text("Priority", style = MaterialTheme.typography.labelLarge, color = Muted)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(TaskPriority.entries) { item -> OmniFilterChip(priority == item, { change(item, tag) }, { Text(item.label) }) }
    }
    Text("Topic", style = MaterialTheme.typography.labelLarge, color = Muted)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(TopicTag.entries) { item -> OmniFilterChip(tag == item, { change(priority, item) }, { Text(item.label) }) }
    }
}
