@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.gyftalala.omni.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gyftalala.omni.*
import com.gyftalala.omni.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal fun Category.icon(): ImageVector = when (this) {
    Category.REMINDER -> Icons.Rounded.NotificationsNone
    Category.PRODUCT -> Icons.Rounded.ShoppingBag
    Category.UX_DESIGN -> Icons.Rounded.AutoAwesomeMosaic
    Category.APK -> Icons.Rounded.Android
    Category.CARD -> Icons.Rounded.CreditCard
    Category.DOCUMENT -> Icons.Rounded.Description
    Category.NOTE -> Icons.AutoMirrored.Rounded.Notes
    Category.UNKNOWN -> Icons.Rounded.HelpOutline
}

@Composable fun OmniMark(size: Int = 36) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * .35).dp)).background(Raised).border(1.dp, Outline, RoundedCornerShape((size * .35).dp)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.AllInclusive, "Omni", tint = Accent, modifier = Modifier.size((size * .68).dp))
    }
}

@Composable fun OmniHeader(subtitle: String, settings: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 22.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clickable(onClick = settings)) { OmniMark() }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("Omni bot AI", style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        trailing()
    }
}

@Composable fun UnlockScreen(error: String?, unlock: () -> Unit) {
    Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(32.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                OmniMark(68)
                Text("Omni bot AI", color = Paper, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp))
            }
            Text(error ?: "Unlock your personal space with your device fingerprint, PIN or pattern.", color = Paper,
                modifier = Modifier.padding(vertical = 24.dp))
            IconButton(onClick = unlock, modifier = Modifier.size(112.dp)) {
                Icon(Icons.Rounded.Fingerprint, "Unlock Omni bot AI", tint = Paper, modifier = Modifier.size(78.dp))
            }
        }
    }
}

@Composable fun OmniApp(
    state: OmniState, model: OmniViewModel, selectedId: String?, select: (String?) -> Unit,
    pick: (String, Category?, String?) -> Unit, export: (Attachment) -> Unit,
    exportCard: (Memory) -> Unit, lock: () -> Unit, openPermission: (PermissionScreen) -> Unit, shareCard: (Memory) -> Unit = {},
    scannerOpen: Boolean = false, dismissScanner: () -> Unit = {},
    scanner: (@Composable (close: () -> Unit) -> Unit)? = null,
    openBackup: () -> Unit = {},
    requestMicrophone: ((Boolean) -> Unit) -> Unit = { it(false) },
    logOut: () -> Unit = {},
) {
    val pages = listOf("Chat", "Library", "Cards")
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val tab = pages[pager.currentPage]
    var deletingId by remember { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val view = LocalView.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val previews = remember(model.vault) { ImagePreviews(model.vault) }
    val tabStates = rememberSaveableStateHolder()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val chatScroll = rememberLazyListState(initialFirstVisibleItemIndex = state.messages.lastIndex.coerceAtLeast(0))
    val wallet = state.memories.filter { it.category == Category.CARD || it.identity != null }
    val emptyWalletScanner = tab == "Cards" && state.ready && wallet.isEmpty() && scanner != null
    val showingScanner = scanner != null && (scannerOpen || emptyWalletScanner)
    fun navigate(name: String) {
        focus.clearFocus(); keyboard?.hide(); dismissScanner()
        scope.launch { pager.scrollToPage(pages.indexOf(name).coerceAtLeast(0)) }
    }
    fun leaveScanner() {
        dismissScanner()
        if (emptyWalletScanner) navigate("Chat")
    }
    LaunchedEffect(tab) {
        if (tab == "Cards") model.refreshWallet()
    }
    var lastPage by rememberSaveable { mutableIntStateOf(pager.currentPage) }
    LaunchedEffect(pager.currentPage) {
        if (pager.currentPage != lastPage) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            lastPage = pager.currentPage
        }
    }
    LaunchedEffect(scannerOpen) { if (scannerOpen) pager.scrollToPage(2) }
    LaunchedEffect(pager.isScrollInProgress) { if (pager.isScrollInProgress) { focus.clearFocus(); keyboard?.hide() } }
    LaunchedEffect(state.error, settings) { if (!settings) state.error?.let { snackbar.showSnackbar(it); model.error(null) } }
    BackHandler(settings || selectedId != null || tab != "Chat") {
        if (settings) settings = false else if (selectedId != null) select(null) else navigate("Chat")
    }
    LaunchedEffect(settings) { if (settings) drawerState.open() else drawerState.close() }
    LaunchedEffect(drawerState.isOpen) { settings = drawerState.isOpen }
    ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
        ModalDrawerSheet(modifier = Modifier.fillMaxWidth(.8f), drawerContainerColor = Ink) {
            SettingsScreen(state, { settings = false }, model, lock, openPermission, openBackup, logOut)
        }
    }) {
    Scaffold(contentWindowInsets = WindowInsets(0), containerColor = if (showingScanner) Color.Black else Ink,
        snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("main-pages"),
            key = { pages[it] }, userScrollEnabled = selectedId == null && deletingId == null) { page ->
            val name = pages[page]
            tabStates.SaveableStateProvider(name) {
                when (name) {
                    "Chat" -> Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()
                        .padding(bottom = FloatingNavigationSpace).testTag("page-chat")) {
                        OmniHeader("Chat", { focus.clearFocus(); keyboard?.hide(); settings = true })
                        Box(Modifier.weight(1f)) {
                            ChatScreen(state, chatScroll, previews, select, { draft = it }, { id, category -> model.categorize(id, category) },
                                { id -> chooseTime(context, state.reminders.firstOrNull { it.id == id }?.triggerAt) { at -> model.schedule(id, at) } },
                                { model.requestClock(it) })
                        }
                        ChatComposer(draft, { draft = it }, { model.send(draft); draft = "" }, pick, requestMicrophone,
                            active = page == pager.currentPage && !pager.isScrollInProgress)
                    }
                    "Library" -> LibraryScreen(state.memories, previews, select, { deletingId = it }, { model.complete(it) }, { model.restoreCompleted(it) }, { model.reorderMemories(it) }) {
                        focus.clearFocus(); keyboard?.hide(); settings = true
                    }
                    "Cards" -> {
                        if (scanner != null && (scannerOpen || (state.ready && wallet.isEmpty()))) {
                            Box(Modifier.fillMaxSize().testTag("page-scanner")) {
                                // Keep camera ownership on the settled page, never on a precomposed neighbour.
                                if (pager.currentPage == page && !pager.isScrollInProgress) scanner(::leaveScanner)
                            }
                        } else WalletScreen(wallet, previews, select, add = { pick("card_scan", Category.CARD, null) },
                            share = shareCard, settings = { settings = true })
                    }
                }
            }
        }
        // Scanner owns the whole viewport. Keeping the dock here covers its status text and
        // shutter, and a stray tab press can tear down CameraX during capture.
        if (!showingScanner) FloatingNavigation(tab, ::navigate, Modifier.align(Alignment.BottomCenter))
        }
    }
    }
    state.memories.firstOrNull { it.id == deletingId }?.let { item ->
        DeleteItemDialog(item, { deletingId = null }, { deletingId = null; model.delete(item.id) })
    }
    if (!showingScanner) state.memories.firstOrNull { it.id == selectedId }?.let { memory ->
        DetailSheet(memory, state.reminders.firstOrNull { it.id == memory.id }, previews, { select(null) }, export, { exportCard(memory) },
            { kind -> pick(kind, memory.category, memory.id) }, { category -> model.categorize(memory.id, category) },
            { title, card, identity -> model.edit(memory.id, title, card, identity) }, { model.delete(memory.id); select(null) },
            { at, repeat -> model.schedule(memory.id, at, repeat) },
            { priority -> model.taskDetails(memory.id, priority, memory.tag) }, { model.complete(memory.id) })
    }
    state.reminderDeletion?.let { request ->
        ReminderDeletionDialog(request, state.busy, { model.cancelReminderDeletion(request.id) },
            { ids -> model.confirmReminderDeletion(request.id, ids) })
    }
}

@Composable private fun ChatScreen(state: OmniState, scroll: LazyListState, previews: ImagePreviews, select: (String) -> Unit, example: (String) -> Unit,
    categorize: (String, Category) -> Unit, time: (String) -> Unit, clock: (String) -> Unit) {
    val latestId = state.messages.lastOrNull()?.id
    var lastSeenId by rememberSaveable { mutableStateOf(latestId) }
    LaunchedEffect(latestId) {
        val previous = lastSeenId
        lastSeenId = latestId
        // Restored tabs stay at their saved offset. Only newly appended messages move the viewport.
        if (latestId != null && latestId != previous && (previous == null || state.messages.any { it.id == previous })) {
            // Pager can compose this child during its own measure pass. Queue the list update
            // for the next measure instead of forcing a nested measure with scrollToItem.
            scroll.requestScrollToItem(state.messages.lastIndex)
        }
    }
    if (state.messages.isEmpty()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 32.dp)) {
            Text("Less to keep\nin your head.", style = MaterialTheme.typography.headlineLarge)
            Text("A thought, a screenshot, a thing to buy.\nDrop it here. Find it when you need it.", color = Body,
                modifier = Modifier.padding(top = 16.dp, bottom = 34.dp))
            Text("Start with something small", style = MaterialTheme.typography.labelMedium, color = Muted)
            listOf("Do puja" to Category.REMINDER, "USB Type-C to Type-C cable" to Category.PRODUCT,
                "Show my saved documents" to Category.DOCUMENT).forEach { (text, category) ->
                Row(Modifier.fillMaxWidth().clickable { example(text) }.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(category.icon(), null, tint = category.tint(), modifier = Modifier.size(20.dp))
                    Text(text, Modifier.weight(1f).padding(start = 14.dp), style = MaterialTheme.typography.bodyMedium)
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Paper, modifier = Modifier.size(16.dp))
                }
                HorizontalDivider(color = Outline)
            }
        }
    } else LazyColumn(modifier = Modifier.testTag("chat-messages"), state = scroll, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(state.messages, key = { it.id }) { message ->
            val mine = message.role == Role.USER
            val memory = state.memories.firstOrNull { it.id == message.attachmentId }
            val files = message.files(memory)
            val caption = message.caption(files)
            Column(Modifier.fillMaxWidth().testTag("chat-message-${message.id}"), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                if (!mine) Text("Omni", color = Accent, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 7.dp))
                if (files.isNotEmpty() && memory != null) {
                    ChatAttachments(files, previews) { select(memory.id) }
                    if (caption.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (caption.isNotBlank()) SelectionContainer {
                    Text(caption, color = if (mine) Paper else Body, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.widthIn(max = 330.dp).then(if (mine) Modifier.clip(RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp))
                            .background(Raised).padding(horizontal = 16.dp, vertical = 12.dp) else Modifier))
                }
                if (!mine) com.gyftalala.omni.alarms.ClockAlarm.decode(message.action)?.let { alarm ->
                    OmniTextButton(onClick = { clock(message.id) }) {
                        Icon(Icons.Rounded.Alarm, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (alarm.opened || alarm.waiting) "Open Clock" else "Set alarm in Clock")
                    }
                }
                if (!mine && memory != null) {
                    Spacer(Modifier.height(12.dp))
                    if (memory.category == Category.CARD) VirtualCard(memory, Modifier.clickable { select(memory.id) })
                    else MemoryReceipt(memory, { select(memory.id) }, previews)
                    if (memory.question != null) {
                        if (memory.category == Category.REMINDER) OmniTextButton(onClick = { time(memory.id) }) {
                            Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Choose date & time")
                        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (memory.alternatives.ifEmpty { listOf(Category.UX_DESIGN, Category.PRODUCT, Category.CARD, Category.DOCUMENT, Category.NOTE) }).forEach { category ->
                                SuggestionChip(onClick = { categorize(memory.id, category) }, label = { Text(category.label) })
                            }
                        }
                    }
                }
            }
        }
        if (state.busy) item { Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
            Text("Sorting your memory…", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp))
        } }
    }
}

@Composable private fun ChatAttachments(files: List<Attachment>, previews: ImagePreviews, open: () -> Unit) {
    val images = files.filter { it.mime.startsWith("image/") }
    Column(Modifier.widthIn(max = 300.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (images.size == 1) {
            val file = images.single()
            VaultImage(file, previews, Modifier.fillMaxWidth().height(300.dp).clickable(onClickLabel = "Open photo", onClick = open),
                tag = "chat-image-${file.id}")
        } else if (images.isNotEmpty()) {
            Text("${images.size} photos", color = Muted, style = MaterialTheme.typography.labelSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.nestedScroll(KeepHorizontalScroll).testTag("photo-gallery-${images.first().id}")) {
                items(images, key = { it.id }) { file ->
                    VaultImage(file, previews, Modifier.width(240.dp).height(260.dp).clickable(onClickLabel = "Open photo", onClick = open),
                        tag = "chat-image-${file.id}")
                }
            }
        }
        files.filterNot { it.mime.startsWith("image/") }.forEach { file ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, Outline, RoundedCornerShape(16.dp))
                .clickable(onClickLabel = "Open file", onClick = open).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, Modifier.size(22.dp), tint = Muted)
                Text(file.name, Modifier.padding(start = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable fun MemoryReceipt(memory: Memory, onClick: () -> Unit, previews: ImagePreviews? = null) {
    val photo = memory.files.firstOrNull { it.mime.startsWith("image/") }
    val edge = when {
        memory.status == "Done" -> Outline
        memory.category == Category.REMINDER && memory.priority == TaskPriority.URGENT -> Accent.copy(alpha = .65f)
        memory.category == Category.REMINDER && memory.priority == TaskPriority.LOW -> Muted.copy(alpha = .45f)
        else -> Outline
    }
    Row(Modifier.fillMaxWidth().alpha(if (memory.status == "Done") .58f else 1f).clip(RoundedCornerShape(16.dp)).background(Panel).border(1.dp, edge, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (photo != null && previews != null) {
            VaultImage(photo, previews, Modifier.size(56.dp), tag = "receipt-image-${memory.id}", thumbnail = true)
        } else Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(memory.category.tint().copy(alpha = .09f)),
            contentAlignment = Alignment.Center) {
            Icon(memory.category.icon(), null, tint = memory.category.tint(), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(memory.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(memory.category.label + (if (memory.category == Category.REMINDER || memory.status == "Done") " · ${memory.status}" else "") + if (memory.files.isNotEmpty()) " • ${memory.files.size} ${if (memory.files.size == 1) "file" else "files"}" else "",
                color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
        Icon(if (memory.status == "Done") Icons.Rounded.CheckCircle else Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = if (memory.status == "Done") Mint else Muted, modifier = Modifier.size(20.dp))
    }
}

@Composable private fun LibraryScreen(memories: List<Memory>, previews: ImagePreviews, select: (String) -> Unit, delete: (String) -> Unit, complete: (String) -> Unit, restore: (String) -> Unit, reorder: (List<String>) -> Unit, settings: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val found = remember(memories, query, category) { MemorySearch.find(memories.filter { it.status != "Done" && (category == null || it.category.name == category) }, query).sortedByDescending { it.sortOrder } }
    val completed = remember(memories, query, category) { MemorySearch.find(memories.filter { it.status == "Done" && (category == null || it.category.name == category) }, query) }
    val counts = remember(memories) { memories.filter { it.status != "Done" }.groupingBy { it.category }.eachCount() }
    val categories = remember(counts) { Category.entries.sortedByDescending { counts[it] ?: 0 } }
    Column(Modifier.fillMaxSize().testTag("page-library")) {
        OmniHeader("Everything you save", settings, trailing = {
            IconButton({ searching = !searching; if (!searching) query = "" }) {
                Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, if (searching) "Close search" else "Search", tint = Paper)
            }
        })
        if (searching) OutlinedTextField(query, { query = it }, placeholder = { Text("Search names, words or contents") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
        LazyRow(modifier = Modifier.nestedScroll(KeepHorizontalScroll).testTag("category-pills"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OmniFilterChip(selected = category == null, onClick = { category = null }, label = { Text("All ${memories.size}") }) }
            items(categories, key = { it.name }) { item -> OmniFilterChip(selected = category == item.name, onClick = { category = item.name },
                label = { Text("${item.label} ${counts[item] ?: 0}") }) }
        }
        if (found.isEmpty() && completed.isEmpty()) EmptyState(if (query.isBlank()) "A place for what matters." else "Nothing matches yet.",
            if (query.isBlank()) "Send a message or attach a file in Chat to start your collection." else "Try another name or category.")
        else {
            var ordered by remember(found.map { it.id }) { mutableStateOf(found) }
            LaunchedEffect(found.map { it.id to it.sortOrder }) { ordered = found }
            LazyColumn(Modifier.testTag("library-items"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ordered, key = { it.id }) { item ->
                Box(Modifier.pointerInput(item.id) {
                    var distance = 0f
                    detectDragGesturesAfterLongPress(
                        onDragStart = { distance = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            distance += amount.y
                            val from = ordered.indexOfFirst { it.id == item.id }
                            val to = when {
                                distance > 56f && from < ordered.lastIndex -> from + 1
                                distance < -56f && from > 0 -> from - 1
                                else -> from
                            }
                            if (to != from) {
                                ordered = ordered.toMutableList().also { list -> val moved = list.removeAt(from); list.add(to, moved) }
                                distance = 0f
                                reorder(ordered.map { it.id })
                            }
                        },
                        onDragEnd = {}, onDragCancel = {})
                }) {
                    SwipeMemory(item, { delete(item.id) }, { complete(item.id) }) { MemoryReceipt(item, { select(item.id) }, previews) }
                }
            }
            if (completed.isNotEmpty()) item(key = "completed-heading") {
                Text("Completed", color = Muted, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
            }
            items(completed, key = { "completed-${it.id}" }) { item ->
                SwipeMemory(item, { delete(item.id) }, { complete(item.id) }, restore = { restore(item.id) }) { MemoryReceipt(item, { select(item.id) }, previews) }
            }
        }
        }
    }
}

@Composable fun VirtualCard(memory: Memory, modifier: Modifier = Modifier) {
    val card = memory.card ?: CardDetails()
    val finish = CardPalette.forMemory(memory)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(Brush.linearGradient(listOf(Color(finish.start), Color(finish.end))))
        .border(1.dp, Color(finish.edge).copy(alpha = .6f), RoundedCornerShape(24.dp)).padding(24.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(card.issuer.ifBlank { memory.title }, color = Paper, style = MaterialTheme.typography.titleLarge)
                Text(card.type, color = Body, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
            }
            Icon(Icons.Rounded.Contactless, null, tint = Accent)
        }
        Spacer(Modifier.height(30.dp))
        Text(card.number.chunked(4).joinToString(" ").ifBlank { "Number not read" }, fontSize = 21.sp, fontWeight = FontWeight.Medium,
            color = Paper, letterSpacing = .5.sp)
        Spacer(Modifier.height(16.dp))
        Text(card.holder.ifBlank { "Add cardholder name" }, color = Paper, fontSize = 13.sp)
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) {
            Column { Text("Valid through", color = Body, fontSize = 10.sp); Text(card.expiry.ifBlank { "Not read" }, color = Paper, fontSize = 13.sp) }
            Column { Text("CVV", color = Body, fontSize = 10.sp); Text(card.cvv.ifBlank { "Not read" }, color = Paper, fontSize = 13.sp) }
            Spacer(Modifier.weight(1f))
            Text(card.network, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

@Composable private fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, color = Muted, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable fun VaultImage(file: Attachment, previews: ImagePreviews, modifier: Modifier,
    tag: String = "detail-image-${file.id}", thumbnail: Boolean = false) {
    var preview by remember(file.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(file.id) { mutableStateOf(false) }
    LaunchedEffect(file.id, previews) {
        try { preview = previews.load(file) }
        catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            failed = true
        }
    }
    val shape = RoundedCornerShape(if (thumbnail) 10.dp else 18.dp)
    Box(modifier.testTag("$tag-container").clip(shape).background(Panel).border(1.dp, Outline, shape),
        contentAlignment = Alignment.Center) {
        preview?.let { bitmap ->
            Image(bitmap.asImageBitmap(), file.name, contentScale = if (thumbnail) ContentScale.Crop else ContentScale.Fit,
                modifier = (if (thumbnail) Modifier.matchParentSize() else Modifier.fillMaxWidth()).testTag(tag))
        } ?: if (failed && thumbnail) Icon(Icons.Rounded.BrokenImage, "Preview unavailable", tint = Muted, modifier = Modifier.size(22.dp))
        else if (failed) Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.BrokenImage, null)
            Text("Preview unavailable", color = Paper, style = MaterialTheme.typography.bodySmall)
            Text(file.name, color = Paper, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else CircularProgressIndicator(Modifier.size(24.dp), color = Paper, strokeWidth = 2.dp)
    }
}
