@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.gyftalala.omni.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gyftalala.omni.backup.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun BackupScreen(state: BackupState, createFile: () -> Unit, chooseFile: () -> Unit,
    create: (CharArray) -> Unit, inspect: (CharArray) -> Unit, restore: () -> Unit, cancel: () -> Unit, dismiss: () -> Unit, allowCreate: Boolean = true) {
    val focus = LocalFocusManager.current
    var password by remember(state.step) { mutableStateOf("") }
    var confirmation by remember(state.step) { mutableStateOf("") }
    var acknowledged by remember(state.step) { mutableStateOf(false) }
    val atHome = state.step == BackupStep.HOME
    fun back() { password = ""; confirmation = ""; focus.clearFocus(); if (atHome) dismiss() else cancel() }
    BackHandler { back() }
    Scaffold(containerColor = Ink, topBar = {
        TopAppBar(title = { Text("Backup & restore", style = MaterialTheme.typography.headlineSmall) },
            navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back from backup") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when (state.step) {
                BackupStep.HOME -> {
                    Icon(Icons.Rounded.EnhancedEncryption, null, tint = Accent, modifier = Modifier.size(36.dp))
                    Text(if (allowCreate) "Keep a copy of your space." else "Import an older backup", style = MaterialTheme.typography.headlineSmall)
                    Text(if (allowCreate) "Save your chats, files, cards, IDs and reminders in one password-protected backup." else "Older password-protected files need their original password. New Google account backups open without one.", color = Body)
                    if (allowCreate) OmniButton(createFile, Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("create-backup"), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Rounded.SaveAlt, null); Spacer(Modifier.width(12.dp)); Text("Create backup")
                    }
                    OmniButton(chooseFile, Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("choose-backup"), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Rounded.Restore, null); Spacer(Modifier.width(12.dp)); Text("Restore backup")
                    }
                    if (allowCreate) Text("Keep a copy outside this phone. Backups are manual; changes made later need a new backup.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    Text("Your Gemini API key is not included. Nothing is sent to AI.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
                BackupStep.CREATE_PASSWORD, BackupStep.RESTORE_PASSWORD -> {
                    val creating = state.step == BackupStep.CREATE_PASSWORD
                    Text(if (creating) "Protect your backup" else "Unlock your backup", style = MaterialTheme.typography.headlineSmall)
                    Text(if (creating) "Choose a long, unique password. You will need it to restore on this phone or another phone."
                        else "Enter the password used when this backup was created.", color = Body)
                    OutlinedTextField(password, { if (it.length <= 256) password = it }, label = { Text("Backup password") },
                        supportingText = { Text("12–256 characters. Spaces are allowed.") },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("backup-password"))
                    if (creating) {
                        OutlinedTextField(confirmation, { if (it.length <= 256) confirmation = it }, label = { Text("Confirm password") },
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true, isError = confirmation.isNotEmpty() && confirmation != password,
                            supportingText = { if (confirmation.isNotEmpty() && confirmation != password) Text("Passwords do not match.") },
                            modifier = Modifier.fillMaxWidth().testTag("backup-confirm-password"))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(acknowledged, { acknowledged = it }, modifier = Modifier.testTag("backup-password-acknowledge"))
                            Text("I understand Omni cannot recover a forgotten backup password.", style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                        }
                    }
                    OmniButton(onClick = {
                        val secret = password.toCharArray(); password = ""; confirmation = ""; focus.clearFocus()
                        if (creating) create(secret) else inspect(secret)
                    }, enabled = BackupCipher.validPassword(password.toCharArray()) && (!creating || (acknowledged && password == confirmation)),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("submit-backup-password"), shape = RoundedCornerShape(16.dp)) {
                        Text(if (creating) "Save encrypted backup" else "Check backup")
                    }
                    if (!creating) Text("Your current items stay unchanged while the backup is checked.", color = Muted)
                }
                BackupStep.WORKING -> {
                    CircularProgressIndicator(Modifier.size(32.dp), color = Accent, strokeWidth = 2.dp)
                    Text(state.status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("backup-progress"))
                    Text("Keep Omni open until this finishes.", color = Muted)
                    OmniTextButton(cancel) { Text("Cancel") }
                }
                BackupStep.PREVIEW -> state.preview?.let { preview ->
                    Icon(Icons.Rounded.VerifiedUser, null, tint = Mint, modifier = Modifier.size(36.dp))
                    Text("Backup verified", style = MaterialTheme.typography.headlineSmall)
                    Text(Instant.ofEpochMilli(preview.created).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")), color = Muted)
                    Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${preview.items} saved items")
                            Text("${preview.messages} messages")
                            Text("${preview.files} files · ${android.text.format.Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, preview.bytes)}")
                            Text("${preview.reminders} reminders")
                        }
                    }
                    Text("Missing items will be added. Existing items and edits on this phone will be kept. Restoring the same backup again will not duplicate records.", color = Body)
                    Text("Past reminders that were still pending may alert after restore. Notification permissions belong to this phone.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    OmniButton(restore, Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("confirm-backup-restore"), shape = RoundedCornerShape(16.dp)) { Text("Restore missing items") }
                    OmniTextButton(cancel) { Text("Cancel") }
                }
                BackupStep.DONE -> {
                    Icon(Icons.Rounded.CheckCircleOutline, null, tint = Mint, modifier = Modifier.size(36.dp))
                    Text("All set", style = MaterialTheme.typography.headlineSmall)
                    Text(state.status, color = Body, modifier = Modifier.testTag("backup-result"))
                    OmniButton(cancel, Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp)) { Text("Done") }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("backup-error")) }
        }
    }
}
