package com.gyftalala.omni.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun FirstPermissionsScreen(allow: () -> Unit, skip: () -> Unit, enabled: Boolean) {
    BackHandler(enabled) { skip() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OmniMark(42)
            Text("Omni bot AI", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 14.dp))
        }
        Spacer(Modifier.height(40.dp))
        Text("A few permissions", style = MaterialTheme.typography.headlineSmall)
        Text("Enable voice typing and on-time reminder calls.", color = Muted, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp))
        listOf(Icons.Rounded.MicNone to "Microphone for voice typing",
            Icons.Rounded.NotificationsNone to "Notifications for reminder alerts",
            Icons.Rounded.Alarm to "Alarms for precise reminder times").forEach { (icon, label) ->
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Accent)
                Text(label, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(28.dp))
        OmniButton(allow, Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("first-permissions-allow"), enabled = enabled) {
            Text("Allow permissions")
        }
        OmniTextButton(skip, Modifier.fillMaxWidth(), enabled = enabled) { Text("Not now") }
    }
}
