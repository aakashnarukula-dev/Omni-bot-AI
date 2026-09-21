@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gyftalala.omni.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gyftalala.omni.OmniState
import com.gyftalala.omni.OmniViewModel
import com.gyftalala.omni.reminders.ReminderScheduler
import kotlinx.coroutines.launch

private fun modelLabel(id: String): String = id.split('-').joinToString(" ") {
    when (it.lowercase()) { "gemini" -> "Gemini"; "lite" -> "Lite"; else -> it.replaceFirstChar(Char::uppercaseChar) }
}.replace("Flash Lite", "Flash-Lite")

enum class PermissionScreen { NOTIFICATIONS, EXACT_ALARMS, FULL_SCREEN, MICROPHONE }

@Composable internal fun SettingsScreen(state: OmniState, dismiss: () -> Unit, model: OmniViewModel, lock: () -> Unit,
    openPermission: (PermissionScreen) -> Unit, openBackup: () -> Unit = {}, logOut: () -> Unit = {}) {
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scheduler = remember { ReminderScheduler(context) }
    var notifications by remember { mutableStateOf(scheduler.notificationsAllowed()) }
    fun microphoneAllowed() = androidx.core.content.ContextCompat.checkSelfPermission(context,
        android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var microphone by remember { mutableStateOf(microphoneAllowed()) }
    var exactAlarms by remember { mutableStateOf(scheduler.exactAllowed()) }
    var fullScreen by remember { mutableStateOf(scheduler.fullScreenAllowed()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun refreshPermissions() {
        microphone = microphoneAllowed()
        notifications = scheduler.notificationsAllowed()
        exactAlarms = scheduler.exactAllowed()
        fullScreen = scheduler.fullScreenAllowed()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshPermissions() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); model.error(null) } }
    Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text("Settings", style = MaterialTheme.typography.headlineSmall) },
            navigationIcon = { IconButton(onClick = dismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back from settings") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            SettingsRow(Icons.Rounded.AutoAwesome, "AI & connection", if (state.hasKey) "Gemini connected · ${modelLabel(state.model)}" else "No API key saved",
                enabled = !state.busy && state.ready) { dialog = "ai" }

            if (!notifications || !exactAlarms || !fullScreen || !microphone) {
                Text("Permissions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 30.dp, bottom = 4.dp))
                if (!microphone) {
                    SettingsRow(Icons.Rounded.MicNone, "Allow microphone", "Required for voice typing") { openPermission(PermissionScreen.MICROPHONE) }
                    HorizontalDivider(color = Outline)
                }
                if (!notifications) {
                    SettingsRow(Icons.Rounded.NotificationsNone, "Allow notifications", "Required for reminder alerts") {
                        openPermission(PermissionScreen.NOTIFICATIONS)
                    }
                    HorizontalDivider(color = Outline)
                }
                if (!exactAlarms && Build.VERSION.SDK_INT >= 31) {
                    SettingsRow(Icons.Rounded.Alarm, "Allow precise reminders", "Required for on-time alerts") {
                        openPermission(PermissionScreen.EXACT_ALARMS)
                    }
                    HorizontalDivider(color = Outline)
                }
                if (!fullScreen && Build.VERSION.SDK_INT >= 34) {
                    SettingsRow(Icons.Rounded.Phone, "Allow reminder calls", "Required for full-screen reminder alerts") {
                        openPermission(PermissionScreen.FULL_SCREEN)
                    }
                }
                HorizontalDivider(color = Outline, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            }
            SettingsRow(Icons.Rounded.Backup, "Backup & restore", "Daily encrypted backup and recovery", enabled = !state.busy && state.ready, onClick = openBackup)
            HorizontalDivider(color = Outline)
            OmniTextButton({ dialog = "logout" }, Modifier.fillMaxWidth().padding(top = 20.dp)) {
                Icon(Icons.Rounded.Logout, null); Spacer(Modifier.width(12.dp)); Text("Log out")
            }
        }
    }

    when (dialog) {
        "logout" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Log out?") },
            text = { Text("Your saved items and backups will stay. Daily backups pause until you sign in again.") },
            confirmButton = { OmniTextButton({ dialog = null; dismiss(); logOut() }) { Text("Log out") } },
            dismissButton = { OmniTextButton({ dialog = null }) { Text("Cancel") } })
        "ai" -> AiDialog(state, dismiss = { dialog = null }, model = model, scope = scope)
        "remove-key" -> AlertDialog(modifier = Modifier.border(1.dp, Outline, RoundedCornerShape(28.dp)), onDismissRequest = { if (!state.busy) dialog = null },
            title = { Text("Remove API key?") }, text = { Text("Gemini AI will turn off. Your saved items and reminders will stay on this phone.") },
            confirmButton = { OmniTextButton(enabled = !state.busy, onClick = { scope.launch {
                model.settings("", state.model, false).join()
                if (model.state.value.error == null) { dialog = null; snackbar.showSnackbar("API key removed") }
            } }) { Text("Remove key", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { OmniTextButton(enabled = !state.busy, onClick = { dialog = "key" }) { Text("Keep key") } })
        "model" -> ModelDialog(state, dismiss = { dialog = null }, load = { model.loadModels(null) }, save = { id ->
            scope.launch {
                model.settings(null, id, state.cloud).join()
                if (model.state.value.error == null) { dialog = null; snackbar.showSnackbar("Model updated") }
            }
        })
    }
}

@Composable private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, enabled: Boolean = true,
    actionAvailable: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().then(if (actionAvailable) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
        .padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
        }
        if (actionAvailable) Icon(Icons.Rounded.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
    }
}

@Composable private fun AiDialog(state: OmniState, dismiss: () -> Unit, model: OmniViewModel, scope: kotlinx.coroutines.CoroutineScope) {
    var key by remember { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(state.model) }
    var expanded by remember { mutableStateOf(false) }
    val choices = (listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", state.model) + state.availableModels.map { it.id }).distinct()
    ModalBottomSheet(onDismissRequest = { if (!state.busy) dismiss() }, containerColor = Panel) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("AI & connection", style = MaterialTheme.typography.headlineSmall)
            Text(if (state.hasKey) "API key connected and encrypted on this phone. Enter a new key only to replace it."
                else "No API key saved. Add one to enable Gemini understanding.", color = Muted)
            OutlinedTextField(key, { key = it }, label = { Text(if (state.hasKey) "Replace API key (optional)" else "Gemini API key") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true,
                enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            Box {
                OutlinedTextField(modelLabel(selected), {}, readOnly = true, label = { Text("AI model") }, trailingIcon = {
                    IconButton({ expanded = !expanded }) { Icon(Icons.Rounded.ExpandMore, "Choose model") }
                }, modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded })
                DropdownMenu(expanded, { expanded = false }, modifier = Modifier.fillMaxWidth(.86f)) {
                    choices.forEach { id -> DropdownMenuItem(text = { Text(modelLabel(id)) }, onClick = { selected = id; expanded = false }) }
                    DropdownMenuItem(text = { Text(if (state.loadingModels) "Loading models…" else "Refresh available models") }, enabled = state.hasKey && !state.loadingModels,
                        onClick = { model.loadModels(null); expanded = false })
                }
            }
            OmniButton(onClick = { model.checkModel(null, selected) }, enabled = state.hasKey && !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                if (state.modelCheck?.checking == true) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Wifi, null, Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp)); Text(if (state.modelCheck?.checking == true) "Checking connection…" else "Check connection")
            }
            state.modelCheck?.takeIf { !it.checking }?.let { check ->
                val color = if (check.passed) Mint else MaterialTheme.colorScheme.error
                Text(if (check.passed) "Connection verified" else "Connection failed · ${check.message}", color = color,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                OmniButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("Cancel") }
                OmniButton(onClick = { scope.launch { model.settings(key.trim().takeIf { it.isNotBlank() }, selected, true).join(); if (model.state.value.error == null) { dismiss() } } },
                    enabled = !state.busy && (key.isNotBlank() || selected != state.model), modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(if (state.hasKey && key.isBlank()) "Save model" else "Save & connect")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable private fun KeyDialog(state: OmniState, enableAfterKey: Boolean, dismiss: () -> Unit, save: (String) -> Unit, remove: () -> Unit) {
    // Never copy the saved secret into Compose state or the clipboard. Unsubmitted input stays in memory only.
    var key by remember { mutableStateOf("") }
    val context = LocalContext.current
    AlertDialog(modifier = Modifier.border(1.dp, Outline, RoundedCornerShape(28.dp)), onDismissRequest = { if (!state.busy) dismiss() },
        title = { Text(if (state.hasKey) "Manage API key" else "Connect Gemini") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(if (state.hasKey) "Your key is saved and encrypted. Paste a new key only when you want to replace it."
                else "Add your Gemini API key. It will be encrypted on this phone.", color = Muted)
            OutlinedTextField(key, { key = it }, label = { Text(if (state.hasKey) "New API key" else "Gemini API key") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
            OmniTextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }) { Text("Get a key from Google AI Studio") }
            if (state.hasKey) OmniTextButton(onClick = remove, enabled = !state.busy) { Text("Remove API key", color = MaterialTheme.colorScheme.error) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { OmniTextButton(onClick = { save(key.trim()) }, enabled = key.isNotBlank() && !state.busy) {
            Text(if (state.busy) "Saving…" else if (enableAfterKey) "Save & enable AI" else "Save key")
        } }, dismissButton = { OmniTextButton(onClick = dismiss, enabled = !state.busy) { Text("Cancel") } })
}

@Composable private fun ModelDialog(state: OmniState, dismiss: () -> Unit, load: () -> Unit, save: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(state.model) }
    var more by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf(state.model) }
    val choices = (listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", state.model) +
        if (more) state.availableModels.map { it.id } else emptyList()).distinct()
    val chosen = if (manual) custom.trim() else selected
    AlertDialog(modifier = Modifier.border(1.dp, Outline, RoundedCornerShape(28.dp)), onDismissRequest = { if (!state.busy) dismiss() }, title = { Text("Choose AI model") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Choose how Omni understands your messages and images.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            choices.forEach { id ->
                Row(Modifier.fillMaxWidth().selectable(selected = selected == id && !manual, enabled = !state.busy,
                    role = Role.RadioButton, onClick = { selected = id; manual = false }).padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == id && !manual, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(modelLabel(id), style = MaterialTheme.typography.titleMedium)
                        Text(when (id) {
                            "gemini-3.8-flash" -> "Recommended for understanding"
                            "gemini-3.5-flash-lite" -> "Faster responses, lower cost"
                            else -> if (id.contains("preview")) "Preview model" else "Available model"
                        }, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider(color = Outline)
            OmniTextButton(onClick = { more = true; load() }, enabled = state.hasKey && !state.busy) {
                Text(if (state.loadingModels) "Finding models…" else if (more) "Refresh available models" else "Browse available models")
            }
            if (more) state.modelListStatus?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
            Text("Availability depends on your Google project.", color = Muted, style = MaterialTheme.typography.bodySmall)
            OmniTextButton(onClick = { custom = selected; manual = !manual }, enabled = !state.busy) { Text(if (manual) "Use model list" else "Enter model ID") }
            if (manual) OutlinedTextField(custom, { custom = it }, singleLine = true, enabled = !state.busy,
                label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { OmniTextButton(onClick = { save(chosen) }, enabled = !state.busy && chosen.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { Text("Use model") } },
        dismissButton = { OmniTextButton(onClick = dismiss, enabled = !state.busy) { Text("Cancel") } })
}

@Composable private fun PrivacyDialog(dismiss: () -> Unit) {
    AlertDialog(modifier = Modifier.border(1.dp, Outline, RoundedCornerShape(28.dp)), onDismissRequest = dismiss, title = { Text("Privacy & security") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PrivacyDetail("Device unlock", "Your fingerprint, PIN or pattern protects Omni whenever you return. Full card details are visible after unlock.")
            PrivacyDetail("Stored on this phone", "Files, messages and your API key are encrypted on this phone. Backup & restore supports file backups and optional daily encrypted cloud backups. Backups exclude your API key. Firebase stores an account-protected recovery key, so new backups need no password. After reinstalling, sign in with the same Google account and confirm restoration. Only completed backups can be recovered.")
            PrivacyDetail("Learning your preferences", "Manual category changes guide future text messages and stay in your encrypted backup. When Gemini is on, up to four relevant text corrections may be sent with a new request. Cards, IDs, attachments and recognized sensitive information are excluded from these examples.")
            PrivacyDetail("When Gemini is on", "Eligible messages and images are sent to Google for understanding. Recognized cards, bank and medical documents stay on this device. Detection can miss sensitive content. Scan cards and IDs from Cards. Wallet details are never sent to Gemini, including after category changes. Optional backups include them only as encrypted data. Turn Gemini off for local text and attachment handling.")
            PrivacyDetail("Google's data use", "Use a billing-enabled Google project for private content. Google's free tier may use inputs and outputs to improve its products. Paid services can still retain data for abuse monitoring.")
            PrivacyDetail("Connection checks", "A check sends only a sample cable message and uses your API quota. It never sends your saved items or turns Gemini on.")
        } }, confirmButton = { OmniTextButton(onClick = dismiss) { Text("Done") } })
}

@Composable private fun PrivacyDetail(title: String, text: String) {
    Column { Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 5.dp)) }
}
