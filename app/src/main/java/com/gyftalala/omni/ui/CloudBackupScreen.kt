@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.gyftalala.omni.ui

import android.app.Activity
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gyftalala.omni.cloud.*
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun GoogleWelcomeScreen(signIn: () -> Unit, enabled: Boolean = true, error: String? = null) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        OmniMark(64)
        Spacer(Modifier.height(28.dp))
        Text("Omni bot AI", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text("Notes, Reminders, Docs & More", color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        OmniButton(signIn, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("welcome-google-sign-in"), enabled = enabled,
            shape = RoundedCornerShape(28.dp)) { Text("Sign in with Google") }
        Spacer(Modifier.height(16.dp))
        Text(error ?: "Encrypted and safely connected to your Google account.", color = Muted,
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable internal fun CloudBackupScreen(state: CloudState, controller: CloudBackupController,
    signIn: () -> Unit, choosePhone: () -> Unit, savePhone: () -> Unit, legacy: () -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    var schedule by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    fun back() {
        if (state.onboarding) (context as? Activity)?.finish()
        else if (state.step == CloudStep.AVAILABLE || state.step == CloudStep.WORKING) controller.cancel()
        else dismiss()
    }
    BackHandler { back() }
    if (!state.ready || (state.onboarding && state.accountEmail == null)) {
        GoogleWelcomeScreen(signIn, enabled = state.ready && state.configured, error = state.error)
        return
    }
    Scaffold(containerColor = Ink, topBar = {
        TopAppBar(title = { Text(if (state.onboarding) "Your backups" else "Backup & restore", style = MaterialTheme.typography.headlineSmall) },
            navigationIcon = { IconButton(::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back from cloud backup") } },
            actions = {
                if (!state.onboarding && state.step == CloudStep.HOME) Box {
                    IconButton({ more = true }) { Icon(Icons.Rounded.MoreVert, "Backup options") }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text("Choose backup file") }, onClick = { more = false; choosePhone() })
                    }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state.onboarding) state.accountEmail?.let { Text(it, color = Muted, modifier = Modifier.testTag("backup-account-email")) }
            when (state.step) {
                CloudStep.WORKING -> {
                    Spacer(Modifier.height(28.dp))
                    CircularProgressIndicator(Modifier.size(30.dp), color = Accent)
                    Text(state.status, color = Body, modifier = Modifier.testTag("cloud-progress"))
                    if (!state.onboarding) OmniTextButton(controller::cancel) { Text("Cancel") }
                }
                CloudStep.AVAILABLE -> {
                    val preview = state.preview!!
                    Text("Your memories are here.", style = MaterialTheme.typography.headlineMedium)
                    Surface(color = Panel, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Outline)) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(if (state.source == "Cloud") Icons.Rounded.CloudDone else Icons.Rounded.FolderOpen, null, tint = Accent)
                            Text("Newest verified backup", style = MaterialTheme.typography.titleMedium)
                            Text(cloudDate(preview.created), color = Body)
                            Text(state.source, color = Muted)
                            Text("${preview.items} saved items · ${preview.messages} messages\n${preview.files} files · ${preview.reminders} reminders", color = Body)
                        }
                    }
                    Text("Restore adds missing items. Anything already on this phone stays.", color = Muted)
                    CloudButton("Restore", controller::restore, "confirm-cloud-restore")
                    OmniTextButton(if (state.onboarding) controller::skipRestore else controller::cancel, Modifier.fillMaxWidth()) {
                        Text(if (state.onboarding) "Skip restore" else "Cancel")
                    }
                    if (state.ignored > 0) Text("${state.ignored} files could not be verified. This is the newest backup successfully checked.", color = Muted)
                }
                CloudStep.HOME -> {
                    if (state.onboarding) {
                        Text("Start where you left off.", style = MaterialTheme.typography.headlineMedium)
                        Text(if (state.cloudChecked && state.candidates.isEmpty()) "No matching cloud backup found. Have a backup on this phone?" else state.status, color = Body)
                        CloudButton("Check backups on this phone", choosePhone, "choose-phone-backups")
                        if (state.candidates.isNotEmpty()) CloudButton("Check newest backup", { controller.discover(inspect = true, prompt = true) }, "cloud-restore")
                        if (!state.cloudChecked) OmniTextButton({ controller.discover(inspect = true, prompt = true) }) { Text("Retry cloud check") }
                        OmniTextButton(controller::skipRestore, Modifier.fillMaxWidth()) { Text("Skip restore") }
                        Text("Daily backup starts after setup. Existing backup files are kept.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    } else if (!state.configured) {
                        Text("Cloud backup is not connected yet.", color = Body)
                    } else if (state.accountEmail == null) {
                        Text("Safe, even on a new phone.", style = MaterialTheme.typography.headlineMedium)
                        Text("Sign in once. Daily backups and recovery use the same Google account.", color = Body)
                        CloudButton("Sign in with Google", signIn, "backup-sign-in")
                    } else {
                        Text("Keep your notes, reminders and files backed up on this phone and in the cloud.", color = Muted)
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Last backup", style = MaterialTheme.typography.titleMedium)
                            Text("Cloud: ${if (state.lastSuccess > 0) cloudDate(state.lastSuccess) else "Not backed up yet"}", color = Body)
                            Text("Phone: ${if (state.localSuccess > 0) cloudDate(state.localSuccess) else "Not backed up yet"}", color = Body)
                            if (state.backupBytes > 0) Text("Size: ${android.text.format.Formatter.formatShortFileSize(context, state.backupBytes)}", color = Muted)
                            if (state.backingUp) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = Accent)
                            if (state.backupStatus.isNotBlank()) Text(state.backupStatus, color = if (state.backupStatus == "Backup saved on phone and in cloud.") Mint else Muted,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("backup-transfer-status"))
                        }
                        OmniButton(if (state.keyReady) controller::backUpNow else controller::enableDaily,
                            Modifier.widthIn(min = 160.dp).heightIn(min = 52.dp).testTag("cloud-back-up-now"), enabled = !state.backingUp) {
                            Text(if (state.backingUp) "Backing up…" else "Back up")
                        }
                        HorizontalDivider(color = Outline, modifier = Modifier.padding(vertical = 8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Google account", style = MaterialTheme.typography.titleMedium)
                            Text(state.accountEmail, color = Muted, modifier = Modifier.testTag("backup-account-email"))
                        }
                        val time = LocalTime.of(state.hour, state.minute).format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"))
                        Row(Modifier.fillMaxWidth().clickable(enabled = state.keyReady && !state.backingUp) { schedule = true }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Automatic backups", style = MaterialTheme.typography.titleMedium)
                                Text(if (state.enabled) "Daily at $time" else "Off", color = Muted)
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Back up using cellular", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Switch(!state.wifiOnly, { controller.settings(state.enabled, state.hour, state.minute, !it) }, enabled = state.keyReady && !state.backingUp)
                        }
                        HorizontalDivider(color = Outline, modifier = Modifier.padding(vertical = 8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null, tint = Accent, modifier = Modifier.size(22.dp))
                            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Encrypted backup", style = MaterialTheme.typography.titleMedium)
                                Text("On · Protected by your Google account", color = Muted, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        OmniTextButton({ controller.discover(inspect = true) }, enabled = !state.backingUp) { Text("Restore backup") }
                    }

                }
                CloudStep.DONE -> {
                    Icon(Icons.Rounded.CheckCircleOutline, null, tint = Mint, modifier = Modifier.size(38.dp))
                    Text("All set.", style = MaterialTheme.typography.headlineSmall)
                    Text(state.status, color = Body, modifier = Modifier.testTag("cloud-result"))
                    CloudButton("Done", dismiss, "cloud-done")
                }
            }
            if (state.step == CloudStep.HOME && !state.onboarding && state.status.isNotBlank()) Text(state.status, color = Muted)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cloud-error")) }
        }
    }
    if (schedule) ModalBottomSheet(onDismissRequest = { schedule = false }, containerColor = Panel) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Automatic backups", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Back up every day", Modifier.weight(1f))
                Switch(state.enabled, { controller.settings(it, state.hour, state.minute, state.wifiOnly) },
                    modifier = Modifier.testTag("daily-backup-toggle"))
            }
            val time = LocalTime.of(state.hour, state.minute).format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"))
            OmniButton({ TimePickerDialog(context, { _, h, m -> controller.settings(state.enabled, h, m, state.wifiOnly) },
                state.hour, state.minute, DateFormat.is24HourFormat(context)).show() }, Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Schedule, null); Spacer(Modifier.width(12.dp)); Text("Backup time · $time")
            }
        }
    }
}
@Composable private fun CloudButton(text: String, click: () -> Unit, tag: String) {
    OmniButton(click, Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag(tag), shape = RoundedCornerShape(24.dp)) { Text(text) }
}
private fun cloudDate(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a"))
