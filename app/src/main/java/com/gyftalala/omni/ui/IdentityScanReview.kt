package com.gyftalala.omni.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.gyftalala.omni.data.IdentityDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun IdentityScanReview(identity: IdentityDetails, photo: File?, hasBack: Boolean, error: String?,
    edit: (IdentityDetails) -> Unit, retake: () -> Unit, scanBack: () -> Unit, save: () -> Unit) {
    var preview by remember(photo) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(photo) { preview = photo?.let { withContext(Dispatchers.IO) { runCatching { ImagePreviews.decode(it.readBytes()) }.getOrNull() } } }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            preview?.let { Image(it.asImageBitmap(), "Captured ${identity.kind.label}", Modifier.fillMaxWidth().heightIn(max = 220.dp), contentScale = ContentScale.Fit) }
            Text(error ?: "Check your ${identity.kind.label} details. Both original photos will be encrypted on this phone.", color = if (error == null) Muted else MaterialTheme.colorScheme.error)
            IdentityFields(identity, edit)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OmniButton(onClick = retake, modifier = Modifier.weight(1f)) { Text("Retake") }
                OmniButton(onClick = scanBack, modifier = Modifier.weight(1f)) { Text(if (hasBack) "Rescan back" else "Scan back") }
            }
            Text(if (hasBack) "Front and back captured" else "Back photo is optional", color = Muted)
        }
        OmniButton(onClick = save, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 52.dp)) { Text("Save ID") }
    }
}

@Composable fun IdentityFields(identity: IdentityDetails, edit: (IdentityDetails) -> Unit) {
    OutlinedTextField(identity.number, { edit(identity.copy(number = it.take(64))) }, label = { Text("${identity.kind.label} number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(identity.name, { edit(identity.copy(name = it.take(120))) }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(identity.birthDate, { edit(identity.copy(birthDate = it.take(30))) }, label = { Text("Date of birth") }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
