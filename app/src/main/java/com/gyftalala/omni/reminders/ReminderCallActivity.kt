package com.gyftalala.omni.reminders

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gyftalala.omni.ui.*

class ReminderCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun render(intent: Intent) {
        val id = intent.getStringExtra(ReminderCallService.EXTRA_ID) ?: run { finish(); return }
        val title = intent.getStringExtra(ReminderCallService.EXTRA_TITLE).orEmpty().ifBlank { "Reminder" }
        val kind = intent.getStringExtra(ReminderCallService.EXTRA_KIND).orEmpty().ifBlank { "Task" }
        val triggerAt = intent.getLongExtra(ReminderCallService.EXTRA_TRIGGER_AT, 0L)
        setContent { OmniTheme { ReminderCallScreen(title, kind, { answer(id) }, { action(id, triggerAt, it) }, { action(id, triggerAt, 5, true) }) } }
    }

    private fun answer(id: String) {
        startService(Intent(this, ReminderCallService::class.java).setAction(ReminderCallService.ACTION_ANSWER)
            .putExtra(ReminderCallService.EXTRA_ID, id))
    }

    private fun action(id: String, triggerAt: Long, minutes: Int, retry: Boolean = false) {
        sendBroadcast(Intent(this, ReminderActionReceiver::class.java)
            .setAction(if (retry) ReminderActionReceiver.ACTION_RETRY else if (minutes == 0) ReminderActionReceiver.ACTION_DONE else ReminderActionReceiver.ACTION_SNOOZE)
            .putExtra(ReminderCallService.EXTRA_ID, id).putExtra(ReminderCallService.EXTRA_TRIGGER_AT, triggerAt)
            .putExtra(ReminderCallService.EXTRA_MINUTES, minutes))
        finishAndRemoveTask()
    }
}

@Composable private fun ReminderCallScreen(title: String, kind: String, answer: () -> Unit,
    action: (Int) -> Unit, later: () -> Unit) {
    var answered by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Ink).systemBarsPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(92.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(44.dp), tint = Ink)
            }
            Text(if (answered) kind else "Incoming reminder", color = Muted,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            Text(title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp))
            if (answered) Text("What would you like Omni to do?", color = Body,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        }
        if (!answered) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                CallButton("Later", false, Modifier.weight(1f)) { later() }
                CallButton("Answer", true, Modifier.weight(1f)) { answered = true; answer() }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ action(0) }, Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(10.dp)); Text("Done")
                }
                Text("Remind me later", color = Muted, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 15, 30, 60).forEach { minutes ->
                        OmniButton({ action(minutes) }, Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("$minutes")
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CallButton(label: String, accept: Boolean, modifier: Modifier, click: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(click, Modifier.size(72.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (accept) Mint else MaterialTheme.colorScheme.error)) {
            Icon(if (accept) Icons.Rounded.Phone else Icons.Rounded.Schedule, label, Modifier.size(30.dp), tint = Ink)
        }
        Text(label, modifier = Modifier.padding(top = 10.dp), color = Body)
    }
}
