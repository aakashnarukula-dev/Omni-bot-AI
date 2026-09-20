package com.gyftalala.omni.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal val FloatingNavigationSpace = 84.dp // 64 dp dock plus 10 dp above and below.

/** Compact shared dock. Content reserves its height, including with the keyboard open. */
@Composable fun FloatingNavigation(selected: String, navigate: (String) -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    Box(modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        Row(Modifier.height(64.dp)
            .shadow(12.dp, CircleShape).clip(CircleShape).background(Color(0xFF1C1E20))
            .padding(horizontal = 8.dp).selectableGroup().testTag("floating-navigation"),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Chat" to Icons.Rounded.ChatBubbleOutline, "Library" to Icons.Rounded.GridView,
                "Cards" to Icons.Rounded.CreditCard).forEach { (name, icon) ->
                Box(Modifier.size(48.dp).clip(CircleShape)
                    .background(if (selected == name) Paper else Color.Transparent)
                    .selectable(selected == name, role = Role.Tab, onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        navigate(name)
                    }),
                    contentAlignment = Alignment.Center) {
                    Icon(icon, name, tint = if (selected == name) Ink else Body, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
