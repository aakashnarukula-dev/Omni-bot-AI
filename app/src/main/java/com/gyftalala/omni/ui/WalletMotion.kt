package com.gyftalala.omni.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object WalletPhysics {
    const val DRAG_DISTANCE = 180f
    fun progress(start: Float, delta: Float) = (start + delta / DRAG_DISTANCE).coerceIn(0f, 1f)
    fun target(progress: Float, velocity: Float): Float = when {
        velocity > 600f -> 1f
        velocity < -600f -> 0f
        progress > .5f -> 1f
        else -> 0f
    }
    fun scale(depth: Int, progress: Float) = 1f - depth.coerceIn(0, 4) * .015f * (1f - progress.coerceIn(0f, 1f))
}

@Stable class WalletMotion(initiallyExpanded: Boolean, private val scope: CoroutineScope, private val saveTarget: (Boolean) -> Unit) {
    var progress by mutableFloatStateOf(if (initiallyExpanded) 1f else 0f)
        private set
    var dragging by mutableStateOf(false)
        private set
    var animating by mutableStateOf(false)
        private set
    private var animation: Job? = null
    fun begin() { animation?.cancel(); animating = false; dragging = true }
    fun drag(deltaDp: Float) { progress = WalletPhysics.progress(progress, deltaDp) }
    fun release(velocityDp: Float) { dragging = false; settle(WalletPhysics.target(progress, velocityDp), velocityDp) }
    fun settle(target: Float, velocityDp: Float = 0f) {
        animation?.cancel(); dragging = false; saveTarget(target == 1f)
        animation = scope.launch {
            animating = true
            try {
                animate(progress, target, initialVelocity = velocityDp / WalletPhysics.DRAG_DISTANCE,
                    animationSpec = spring(dampingRatio = .83f, stiffness = 275f)) { value, _ -> progress = value.coerceIn(0f, 1f) }
                progress = target
            } finally { animating = false }
        }
    }
}
