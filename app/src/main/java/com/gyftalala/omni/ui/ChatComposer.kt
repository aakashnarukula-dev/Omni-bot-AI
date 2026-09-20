package com.gyftalala.omni.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gyftalala.omni.data.Category
import com.gyftalala.omni.voice.SpeechInput
import com.gyftalala.omni.voice.VoiceInput
import java.util.Locale

@Composable internal fun ChatComposer(draft: String, onDraft: (String) -> Unit, onSend: () -> Unit,
    pick: (String, Category?, String?) -> Unit, requestMicrophone: ((Boolean) -> Unit) -> Unit,
    speechFactory: (() -> SpeechInput)? = null, active: Boolean = true) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val speech = remember { speechFactory?.invoke() ?: VoiceInput(context) }
    val changeDraft by rememberUpdatedState(onDraft)
    var attachMenu by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var onlineConsent by remember { mutableStateOf(false) }
    var onlineAccepted by remember { mutableStateOf(false) }
    fun cancelSpeech() { generation++; requesting = false; speech.cancel(); listening = false }
    LaunchedEffect(active) {
        if (!active) { cancelSpeech(); attachMenu = false; onlineConsent = false }
    }
    fun startSpeech() {
        cancelSpeech(); error = null; listening = true
        focus.clearFocus(); keyboard?.hide()
        val token = generation
        speech.start(Locale.getDefault().toLanguageTag(), { text ->
            if (generation == token) changeDraft(text.take(16000))
        }, {}, { reason -> if (generation == token) { listening = false; error = reason } })
    }
    fun requestSpeech() {
        if (requesting) return
        error = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startSpeech()
        else {
            requesting = true
            val token = generation
            requestMicrophone { allowed ->
                if (generation == token) {
                    requesting = false
                    if (allowed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startSpeech()
                    else if (!allowed) error = "Microphone access is off. Allow it in Android app permissions, or type your message."
                }
            }
        }
    }
    fun send() { if (draft.isNotBlank()) { cancelSpeech(); error = null; onSend() } }
    DisposableEffect(speech, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            // Permission callbacks must not restart recording after an ordinary background exit.
            if (listening) cancelSpeech()
        } }
        lifecycle.addObserver(observer)
        onDispose { cancelSpeech(); lifecycle.removeObserver(observer) }
    }
    Column(Modifier.testTag("chat-composer")) {
        if (listening || error != null) Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (listening) "Listening…" else error.orEmpty(), color = if (listening) Accent else Muted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).testTag("dictation-status"))
            if (listening) OmniTextButton(onClick = { speech.stop() }) { Text("Stop") }
            else IconButton(onClick = { error = null }) { Icon(Icons.Rounded.Close, "Dismiss voice message", Modifier.size(18.dp)) }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clip(CircleShape).background(Panel)
            .border(1.dp, Outline, CircleShape).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { cancelSpeech(); focus.clearFocus(); keyboard?.hide(); attachMenu = true },
                    modifier = Modifier.size(48.dp).background(Raised, CircleShape).border(1.dp, Outline, CircleShape)) {
                    Icon(Icons.Rounded.Add, "Attach", tint = Paper, modifier = Modifier.size(24.dp))
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    listOf(Triple("Camera", "camera", Icons.Rounded.PhotoCamera), Triple("Photos", "image", Icons.Rounded.PhotoLibrary),
                        Triple("Documents", "file", Icons.Rounded.Description)).forEach { (label, kind, icon) ->
                        DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null, tint = Accent) },
                            onClick = { attachMenu = false; pick(kind, null, null) })
                    }
                }
            }
            TextField(value = draft, onValueChange = { cancelSpeech(); error = null; onDraft(it.take(16000)) },
                modifier = Modifier.weight(1f).testTag("chat-draft"),
                placeholder = { Text(if (listening) "Listening…" else "Send it. I'll remember.", color = Muted, fontSize = 14.sp) }, maxLines = 5,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
            val hasText = draft.isNotBlank()
            IconButton(onClick = {
                when {
                    hasText -> send()
                    listening -> cancelSpeech()
                    !speech.onDevice && !onlineAccepted -> onlineConsent = true
                    else -> requestSpeech()
                }
            }, enabled = !requesting,
                modifier = Modifier.size(48.dp).background(if (hasText) Accent else Raised, CircleShape)) {
                Icon(if (hasText) Icons.Rounded.ArrowUpward else Icons.Rounded.MicNone,
                    if (hasText) "Send message" else if (listening) "Stop dictation" else "Dictate message",
                    tint = if (hasText) Ink else Accent)
            }
        }
    }
    if (onlineConsent) AlertDialog(onDismissRequest = { onlineConsent = false }, title = { Text("Use Android voice input?") },
        text = { Text("On-device speech is unavailable. Android's speech service may process your voice online. The transcript stays in your draft until you tap Send.") },
        confirmButton = { OmniTextButton(onClick = { onlineConsent = false; onlineAccepted = true; requestSpeech() }) { Text("Use voice input") } },
        dismissButton = { OmniTextButton(onClick = { onlineConsent = false }) { Text("Cancel") } })
}
