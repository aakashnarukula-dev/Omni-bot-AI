package com.gyftalala.omni.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Camera chrome also rendered with a synthetic preview in layout tests. */
@Composable fun ScannerChrome(label: String, types: List<String>, selected: String, select: (String) -> Unit,
    back: Boolean, ready: Boolean, busy: Boolean, status: String, error: Boolean,
    flash: Boolean, torch: Boolean, toggleTorch: () -> Unit, capture: () -> Unit, close: () -> Unit,
    preview: @Composable () -> Unit) {
    val scrim = Color.Black.copy(alpha = .62f)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("card-scanner")) {
        val frameWidth = minOf(maxWidth - 40.dp, 520.dp)
        val frameHeight = frameWidth / 1.586f
        // Short screens scroll instead of squeezing the shutter or covering it with navigation.
        val contentHeight = maxOf(maxHeight, frameHeight + 302.dp)
        preview()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).height(contentHeight),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.fillMaxWidth().weight(1f).background(scrim))
            Row(Modifier.fillMaxWidth().height(frameHeight)) {
                Spacer(Modifier.weight(1f).fillMaxHeight().background(scrim))
                ScanGuide(Modifier.width(frameWidth).fillMaxHeight(), ready && !busy)
                Spacer(Modifier.weight(1f).fillMaxHeight().background(scrim))
            }
            Column(Modifier.fillMaxWidth().background(scrim).padding(bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(32.dp))
                if (types.isNotEmpty()) LazyRow(Modifier.testTag("scan-types"),
                    contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(types) { type ->
                        Box(Modifier.height(48.dp).selectable(selected == type, enabled = !busy, role = Role.Tab, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { select(type) })
                            .padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text(type.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp,
                                color = if (selected == type) Ink else Body,
                                modifier = Modifier.clip(CircleShape).background(if (selected == type) Paper else Color(0xFF202224))
                                    .padding(horizontal = 9.dp, vertical = 5.dp))
                        }
                    }
                } else Text(if (back) "BACK" else selected.uppercase(), color = Body, fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, modifier = Modifier.height(48.dp).wrapContentHeight())
                Spacer(Modifier.height(8.dp))
                Box(Modifier.size(76.dp).clip(CircleShape).border(2.dp, if (ready) Paper else Muted, CircleShape)
                    .clickable(enabled = ready && !busy, role = Role.Button, onClick = capture)
                    .semantics { contentDescription = if (back) "Capture back" else "Capture front" }
                    .testTag("scan-shutter").padding(7.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().background(if (ready) Paper else Body, CircleShape))
                    if (busy) CircularProgressIndicator(Modifier.size(26.dp), color = Ink, strokeWidth = 2.dp)
                }
                Spacer(Modifier.height(16.dp))
                Text(status, color = if (error) MaterialTheme.colorScheme.error else Body, textAlign = TextAlign.Center,
                    fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 24.dp).heightIn(min = 34.dp).testTag("scan-status"))
                Text("Read privately on your phone", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
        Row(Modifier.fillMaxWidth().background(scrim).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close card scanner", tint = Paper, modifier = Modifier.size(20.dp)) }
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 15.sp, letterSpacing = 2.sp,
                color = Paper, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            if (flash) IconButton(onClick = toggleTorch) {
                Icon(if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    if (torch) "Turn light off" else "Turn light on", tint = Paper, modifier = Modifier.size(20.dp))
            } else Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable private fun ScanGuide(modifier: Modifier, running: Boolean) {
    val movement = rememberInfiniteTransition(label = "Scanner guide")
    val scan by movement.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Scan position")
    Canvas(modifier.testTag("scan-frame")) {
        val line = 1.dp.toPx()
        drawRoundRect(Paper.copy(alpha = .6f), cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(line))
        for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
            fun point(x: Float, y: Float) = Offset(if (right) size.width - x else x, if (bottom) size.height - y else y)
            val end = 24.dp.toPx()
            val radius = 16.dp.toPx()
            val start = point(0f, end)
            val curveStart = point(0f, radius)
            val corner = point(0f, 0f)
            val curveEnd = point(radius, 0f)
            val finish = point(end, 0f)
            val path = Path().apply {
                moveTo(start.x, start.y); lineTo(curveStart.x, curveStart.y)
                quadraticBezierTo(corner.x, corner.y, curveEnd.x, curveEnd.y)
                lineTo(finish.x, finish.y)
            }
            drawPath(path, Paper, style = Stroke(2.dp.toPx()))
        }
        if (running) {
            val y = 18.dp.toPx() + scan * (size.height - 36.dp.toPx())
            drawLine(Paper.copy(alpha = .55f), Offset(8.dp.toPx(), y), Offset(size.width - 8.dp.toPx(), y), line)
        }
    }
}
