package com.gyftalala.omni.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.gyftalala.omni.MemorySearch
import com.gyftalala.omni.data.Memory

@Composable fun WalletScreen(cards: List<Memory>, previews: ImagePreviews, select: (String) -> Unit,
    add: () -> Unit, share: (Memory) -> Unit = {}, settings: (() -> Unit)? = null) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var active by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val motion = remember { WalletMotion(expanded, scope) { expanded = it } }
    val list = rememberLazyListState()
    val shown = remember(cards, filter, query) {
        MemorySearch.find(cards.filter { filter == "All" || if (filter == "IDs") it.identity != null else it.identity == null }, query)
    }
    // Older cards remain lazy. Keeping them before the recent stack preserves card order in both states.
    // Keep the touched lazy row alive until release; removing it at drag start cancels its gesture.
    val showOlder = motion.progress > .001f || motion.dragging || motion.animating
    val older = if (showOlder) shown.drop(5).asReversed() else emptyList()
    val dragState = rememberDraggableState { motion.drag(it / density) }
    val drag = Modifier.draggable(dragState, Orientation.Vertical, startDragImmediately = motion.animating,
        onDragStarted = { active = null; motion.begin() }, onDragStopped = { motion.release(it / density) })
    LaunchedEffect(filter, query) { active = null; list.requestScrollToItem(0) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OmniHeader("Cards & docs", settings ?: {}, trailing = {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, if (searching) "Close wallet search" else "Search wallet", tint = Muted)
                }
            })
            Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
                Text("Your wallet", style = MaterialTheme.typography.headlineSmall)
                Text("${cards.size} cards & IDs", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (searching) OutlinedTextField(query, { query = it }, placeholder = { Text("Find a card or ID") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp), shape = RoundedCornerShape(18.dp))
            LazyRow(modifier = Modifier.nestedScroll(KeepHorizontalScroll), contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("All", "Cards", "IDs")) { label -> OmniFilterChip(filter == label, { filter = label }, { Text(label) }) }
            }
            if (shown.isEmpty()) Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (cards.isEmpty()) "Keep your cards together." else "No matching cards or IDs.", style = MaterialTheme.typography.titleMedium)
                Text("Scan a payment card, PAN, Aadhaar or driving licence. Keep both sides in your encrypted wallet.", color = Muted)
            } else {
                Row(Modifier.fillMaxWidth().testTag("wallet-drag-handle").then(drag).padding(horizontal = 22.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (expanded) "Drag top edge to close" else "Pull down to expand", color = Muted, style = MaterialTheme.typography.bodySmall)
                    OmniTextButton(onClick = { active = null; motion.settle(if (expanded) 0f else 1f) }, modifier = Modifier.testTag("wallet-expand")) {
                        Text(if (expanded) "Collapse cards" else "Expand ${shown.size}")
                        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    }
                }
                LazyColumn(Modifier.weight(1f).testTag("wallet-list"), state = list,
                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(older, key = { it.id }) { memory ->
                        WalletFace(memory, previews, active == memory.id, { active = if (active == memory.id) null else memory.id },
                            { select(memory.id) }, { share(memory) }, drag)
                    }
                    item(key = "stack") {
                        val stack = shown.take(5).asReversed()
                        Layout(content = {
                            stack.forEach { memory ->
                                WalletFace(memory, previews, active == memory.id, { active = if (active == memory.id) null else memory.id },
                                    { select(memory.id) }, { share(memory) }, drag)
                            }
                        }, modifier = Modifier.testTag("wallet-stack").semantics {
                            progressBarRangeInfo = ProgressBarRangeInfo(motion.progress, 0f..1f)
                        }.then(if (!expanded) drag else Modifier)) { measurables, constraints ->
                            val children = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
                            val cardHeight = children.firstOrNull()?.height ?: 0
                            val peek = cardHeight * .18f
                            val openStep = cardHeight + 18.dp.toPx()
                            val spread = motion.progress
                            val step = peek + (openStep - peek) * spread
                            val height = (cardHeight + children.lastIndex.coerceAtLeast(0) * step).toInt()
                            layout(constraints.maxWidth, height) {
                                children.forEachIndexed { i, child ->
                                    val scale = WalletPhysics.scale(children.lastIndex - i, spread)
                                    child.placeRelativeWithLayer(0, (i * step).toInt(), zIndex = i.toFloat()) {
                                        scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(.5f, 0f)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Surface(onClick = add, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).testTag("wallet-add"),
            shape = RoundedCornerShape(50), color = Raised, contentColor = Paper, shadowElevation = 12.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = .3f))) {
            Row(Modifier.padding(horizontal = 22.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ADD CARD", fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.8.sp)
            }
        }
    }
}

@Composable private fun WalletFace(memory: Memory, previews: ImagePreviews, active: Boolean, toggle: () -> Unit,
    details: () -> Unit, share: () -> Unit, edgeDrag: Modifier) {
    Box(Modifier.fillMaxWidth().testTag("wallet-card-${memory.id}").clip(RoundedCornerShape(18.dp))) {
        CompactWalletCard(memory, previews, Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = toggle),
            headerModifier = Modifier.testTag("wallet-edge-${memory.id}").then(edgeDrag))
        if (active) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp).testTag("wallet-actions-${memory.id}"),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = details, modifier = Modifier.testTag("wallet-details-${memory.id}"), color = Ink.copy(alpha = .94f),
                shape = RoundedCornerShape(50), border = androidx.compose.foundation.BorderStroke(.5.dp, Paper.copy(alpha = .2f))) {
                Text("DETAILS", color = Paper, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
            }
            Surface(onClick = share, modifier = Modifier.testTag("wallet-share-${memory.id}"), color = Paper,
                shape = RoundedCornerShape(50), shadowElevation = 8.dp) {
                Text("SHARE", color = Ink, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.6.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
            }
        }
    }
}

@Composable fun IdentityCard(memory: Memory, previews: ImagePreviews, modifier: Modifier = Modifier) {
    val id = memory.identity ?: return
    val finish = CardPalette.forMemory(memory)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(Brush.linearGradient(listOf(Color(finish.start), Color(finish.end))))
        .border(1.dp, Color(finish.edge), RoundedCornerShape(24.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(id.kind.label, style = MaterialTheme.typography.titleLarge, color = Paper)
            Icon(Icons.Rounded.Badge, null, tint = Accent)
        }
        memory.files.firstOrNull { it.mime.startsWith("image/") }?.let {
            VaultImage(it, previews, Modifier.fillMaxWidth().height(128.dp), tag = "wallet-id-image-${it.id}")
        }
        Text(id.name.ifBlank { "Name not read" }, style = MaterialTheme.typography.titleMedium, color = Paper)
        Text(id.number.ifBlank { "Number not read" }, style = MaterialTheme.typography.bodyLarge, color = Paper)
        if (id.birthDate.isNotBlank()) Text("Born ${id.birthDate}", style = MaterialTheme.typography.bodySmall, color = Body)
    }
}
