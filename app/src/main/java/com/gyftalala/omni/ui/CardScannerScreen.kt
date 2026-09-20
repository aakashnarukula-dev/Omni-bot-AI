package com.gyftalala.omni.ui

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gyftalala.omni.ai.CardScanReader
import com.gyftalala.omni.ai.OcrReader
import com.gyftalala.omni.ai.StableCardRead
import com.gyftalala.omni.ai.IdentityReader
import com.gyftalala.omni.ai.StableIdentityRead
import com.gyftalala.omni.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ScannedCard(val photos: List<File>, val details: CardDetails, val identity: IdentityDetails? = null)

private enum class ScanKind(val label: String, val idKind: IdKind? = null) {
    CREDIT("Credit"), DEBIT("Debit"), PAN("PAN", IdKind.PAN), AADHAAR("Aadhaar", IdKind.AADHAAR), DL("DL", IdKind.DL), OTHER("Other", IdKind.OTHER)
}

@Composable fun CardScannerScreen(cameraAllowed: Boolean, initial: CardDetails?, requestCamera: () -> Unit,
    openSettings: () -> Unit, close: () -> Unit, save: (ScannedCard) -> Unit, initialIdentity: IdentityDetails? = null, embedded: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(initialIdentity?.let { id -> ScanKind.entries.first { it.idKind == id.kind } }
        ?: if (initial?.type?.contains("debit", true) == true) ScanKind.DEBIT else ScanKind.CREDIT) }
    var identity by remember { mutableStateOf(initialIdentity) }
    var palette by remember { mutableStateOf(emptyList<Int>()) }
    val owned = remember { mutableSetOf<File>() }
    var front by remember { mutableStateOf<File?>(null) }
    var back by remember { mutableStateOf<File?>(null) }
    var details by remember { mutableStateOf(initial ?: CardDetails(type = if (kind == ScanKind.DEBIT) "Debit card" else "Credit card")) }
    var scanningBack by remember { mutableStateOf(initial != null || initialIdentity != null) }
    var review by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { owned.forEach(File::delete); owned.clear() } }
    LaunchedEffect(Unit) { if (!cameraAllowed) requestCamera() }
    BackHandler { if (!reading && scanningBack && front != null && !review) review = true else close() }

    fun captured(file: File) {
        owned += file
        reading = true
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val reader = OcrReader()
                    try { reader.read(context, FileProvider.getUriForFile(context, "${context.packageName}.files", file)) }
                    finally { reader.close() }
                }
                if (scanningBack && (if (kind.idKind != null) identity?.let { IdentityReader.different(text, it) } == true else CardScanReader.differentCard(text, details))) {
                    file.delete(); owned.remove(file)
                    error = "This looks like a different card. Scan the matching back."
                    return@launch
                }
                if (scanningBack) { back?.let { it.delete(); owned.remove(it) }; back = file }
                else { front?.let { it.delete(); owned.remove(it) }; front = file }
                if (kind.idKind != null) identity = IdentityReader.extract(text, kind.idKind!!, if (scanningBack) identity else initialIdentity)
                else details = CardScanReader.extract(text, scanningBack, if (scanningBack) details.copy(cvv = "") else initial)
                    .copy(type = if (kind == ScanKind.DEBIT) "Debit card" else "Credit card")
                if (!scanningBack || palette.isEmpty()) palette = withContext(Dispatchers.IO) {
                    runCatching { PhotoPalette.fromUri(context, FileProvider.getUriForFile(context, "${context.packageName}.files", file)) }.getOrDefault(emptyList())
                }
                error = if (text.isBlank()) "No text was read. Retake the photo or fill in the details." else null
                review = true
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                file.delete(); owned.remove(file)
                error = "Could not read that photo. Try again with more light."
            } finally { reading = false }
        }
    }

    Surface(Modifier.fillMaxSize(), color = if (review) Ink else androidx.compose.ui.graphics.Color.Black) {
        Column(Modifier.statusBarsPadding().then(if (embedded) Modifier else Modifier.navigationBarsPadding()).imePadding()) {
            if (review || !cameraAllowed) Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close card scanner") }
                Text(if (review) if (kind.idKind == null) "Review card" else "Review ID" else "Scan a card", style = MaterialTheme.typography.headlineSmall)
            }
            if (!cameraAllowed) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Icon(Icons.Rounded.PhotoCamera, null, tint = Accent, modifier = Modifier.size(40.dp))
                    Text("Allow camera access to scan your card.", style = MaterialTheme.typography.titleLarge)
                    Text("Card details are read on this phone. Enable Camera in Android permissions, then return here.", color = Muted)
                    OmniButton(onClick = openSettings) { Text("Open camera settings") }
                }
            } else if (review) {
                val retake = {
                    owned.forEach(File::delete); owned.clear(); front = null; back = null
                    details = initial ?: CardDetails(type = if (kind == ScanKind.DEBIT) "Debit card" else "Credit card")
                    identity = initialIdentity ?: kind.idKind?.let { IdentityDetails(it) }
                    scanningBack = initial != null || initialIdentity != null; error = null; review = false
                }
                val scanBack = { scanningBack = true; error = null; review = false }
                val saveScan = {
                    val photos = listOfNotNull(front, back)
                    if (photos.isNotEmpty()) {
                        owned.removeAll(photos.toSet())
                        save(ScannedCard(photos, details, identity))
                    }
                }
                if (kind.idKind == null) CardScanReview(details, back != null, error, { details = it }, retake, scanBack, saveScan, palette)
                else IdentityScanReview(identity ?: IdentityDetails(kind.idKind!!), front ?: back, back != null, error,
                    { identity = it }, retake, scanBack, saveScan)
            } else {
                val chooseKind: (String) -> Unit = { label ->
                    val option = ScanKind.entries.first { it.label == label }
                    kind = option; identity = option.idKind?.let { IdentityDetails(it) }; error = null; palette = emptyList()
                    details = CardDetails(type = if (option == ScanKind.DEBIT) "Debit card" else "Credit card")
                }
                key(kind) { LiveCardScanner(scanningBack, details, reading, error, ::captured, { error = it }, kind.idKind, identity,
                    kind.label, if (front == null && back == null && initial == null && initialIdentity == null) ScanKind.entries.map { it.label } else emptyList(),
                    chooseKind, close) }
            }
        }
    }
}

@Composable private fun LiveCardScanner(back: Boolean, existing: CardDetails, reading: Boolean, error: String?,
    captured: (File) -> Unit, failed: (String) -> Unit, idKind: IdKind? = null, identity: IdentityDetails? = null,
    kind: String, types: List<String>, selectType: (String) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val camera = remember { CardCamera(context) }
    val stable = remember { StableCardRead() }
    val stableId = remember { StableIdentityRead() }
    var detectedId by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var detected by remember { mutableStateOf(CardDetails(type = "Payment card")) }
    val currentCaptured by rememberUpdatedState(captured)
    val currentFailed by rememberUpdatedState(failed)
    val currentReading by rememberUpdatedState(reading)
    fun capture() {
        if (!ready || capturing || currentReading) return
        capturing = true
        camera.capture({ file -> currentCaptured(file); capturing = false }, { capturing = false; currentFailed(it) })
    }
    DisposableEffect(camera) { onDispose { camera.close() } }
    ScannerChrome(
        label = if (back) "ALIGN BACK · ${kind.uppercase()}" else "ALIGN CARD · ${kind.uppercase()}",
        types = types, selected = kind, select = selectType, back = back, ready = ready, busy = reading || capturing,
        status = error ?: when {
            reading || capturing -> "Reading your card…"
            !ready -> "Starting camera…"
            idKind != null && detectedId.isNotBlank() -> "Number detected · Hold steady"
            back && detected.cvv.isNotBlank() -> "Security code detected · Hold steady"
            !back && detected.number.isNotBlank() -> "Number detected · Hold steady"
            else -> "Hold steady for auto capture, or tap the shutter"
        }, error = error != null, flash = flash, torch = torch,
        toggleTorch = { torch = !torch; camera.torch(torch) }, capture = ::capture, close = close,
    ) {
            AndroidView(factory = { ctx -> PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                camera.start(this, lifecycle, { hasFlash -> ready = true; flash = hasFlash }, { text ->
                    if (!capturing && !currentReading) {
                        // Front scans never carry fields from earlier frames into a new candidate.
                        detected = if (back && CardScanReader.differentCard(text, existing)) CardDetails()
                            else CardScanReader.extract(text, back, if (back) existing.copy(cvv = "") else null)
                        if (idKind != null) {
                            detectedId = if (back && identity != null && IdentityReader.different(text, identity)) "" else IdentityReader.extract(text, idKind).number
                            if (stableId.accept(detectedId)) capture()
                        } else if (stable.accept(detected, back)) capture()
                    }
                }, { currentFailed(it) })
            } }, modifier = Modifier.fillMaxSize())
    }
}

@Composable fun CardScanReview(card: CardDetails, hasBack: Boolean, error: String?, edit: (CardDetails) -> Unit,
    retake: () -> Unit, scanBack: () -> Unit, save: () -> Unit, palette: List<Int> = emptyList()) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VirtualCard(Memory("scan-preview", "Your card", "", Category.CARD, card = card, palette = palette))
            Text(error ?: "Check the details against your card. Anything unreadable stays empty.", color = if (error == null) Muted else MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OmniButton(onClick = retake, modifier = Modifier.weight(1f)) { Text("Retake") }
                OmniButton(onClick = scanBack, modifier = Modifier.weight(1f)) { Text(if (hasBack) "Rescan back" else "Scan back") }
            }
            listOf("Bank / issuer" to card.issuer, "Cardholder" to card.holder, "Card number" to card.number,
                "Expiry" to card.expiry, "CVV" to card.cvv, "Card type" to card.type, "Network" to card.network).forEachIndexed { index, (label, value) ->
                OutlinedTextField(value, { text -> edit(when (index) {
                    0 -> card.copy(issuer = text); 1 -> card.copy(holder = text)
                    2 -> card.copy(number = text.filter(Char::isDigit).take(19)); 3 -> card.copy(expiry = text)
                    4 -> card.copy(cvv = text.filter(Char::isDigit).take(4)); 5 -> card.copy(type = text); else -> card.copy(network = text)
                }) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
        }
        OmniButton(onClick = save, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 52.dp)) { Text("Save card") }
    }
}
