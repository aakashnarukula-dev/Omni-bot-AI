package com.gyftalala.omni.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.gyftalala.omni.data.Memory
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Do not hand a horizontal strip's end-of-scroll or fling to the page behind it. */
internal val KeepHorizontalScroll = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(available.x, 0f)
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(available.x, 0f)
}

/** The deepest horizontal detector owns the whole gesture; vertical scrolling stays with the list. */
@Composable internal fun SwipeMemory(memory: Memory, delete: () -> Unit, complete: () -> Unit,
    restore: () -> Unit = complete,
    tag: String = "swipe-item-${memory.id}", content: @Composable () -> Unit) {
    var offset by remember(memory.id) { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val maxThreshold = with(LocalDensity.current) { 96.dp.toPx() }
    val onDelete by rememberUpdatedState(delete)
    val onComplete by rememberUpdatedState(complete)
    val onRestore by rememberUpdatedState(restore)
    val done by rememberUpdatedState(memory.status == "Done")
    val threshold = minOf(width * .32f, maxThreshold)
    val red = MaterialTheme.colorScheme.error
    fun reset() {
        settling?.cancel()
        settling = scope.launch { animate(offset, 0f, animationSpec = spring(dampingRatio = .9f, stiffness = 650f)) { value, _ -> offset = value } }
    }
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat() }.clip(RoundedCornerShape(16.dp)).testTag(tag)
        .semantics { customActions = buildList {
            add(CustomAccessibilityAction("Delete item") { onDelete(); true })
            add(CustomAccessibilityAction(if (done) "Move back to active" else "Mark done") { if (done) onRestore() else onComplete(); true })
        } }
        .pointerInput(memory.id) {
            detectHorizontalDragGestures(
                onDragStart = { settling?.cancel() },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    offset = (offset + amount).coerceIn(-width * .85f, width * .85f)
                },
                onDragEnd = {
                    if (abs(offset) >= threshold) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (offset >= minOf(width * .32f, maxThreshold)) onDelete()
                    else if (offset <= -minOf(width * .32f, maxThreshold)) { if (done) onRestore() else onComplete() }
                    reset()
                },
                onDragCancel = { reset() },
            )
        }) {
        if (abs(offset) > .5f) {
            val deleting = offset > 0f
            val color = if (deleting) red else Mint
            Row(Modifier.matchParentSize().background(color.copy(alpha = if (abs(offset) >= threshold) .2f else .1f))
                .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (deleting) Arrangement.Start else Arrangement.End) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (deleting) Icons.Rounded.DeleteOutline else Icons.Rounded.CheckCircleOutline, null, tint = color)
                    Text(if (deleting) "Delete" else if (done) "Move back" else "Mark done", color = color, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }) { content() }
    }
}
