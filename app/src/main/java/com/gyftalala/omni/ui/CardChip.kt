package com.gyftalala.omni.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/** Decorative contact module. Shared by the live card and its PNG export. */
@Composable fun CardChip(modifier: Modifier = Modifier) {
    Spacer(modifier.drawWithCache {
        val artwork = CardChipArtwork()
        onDrawBehind { drawIntoCanvas { artwork.draw(it.nativeCanvas, 0f, 0f, size.width, size.height) } }
    })
}

/** Normalized paths and paints are allocated once per displayed chip, not per animation frame. */
internal class CardChipArtwork {
    private val outline = RectF(1f, 1f, 99f, 75f)
    private val inset = RectF(2f, 2f, 98f, 74f)
    private val plate = Path().apply { addRoundRect(outline, 10f, 10f, Path.Direction.CW) }
    private val contacts = Path().apply {
        addRoundRect(33f, 12f, 67f, 64f, 5f, 5f, Path.Direction.CW)
        moveTo(50f, 1f); lineTo(50f, 12f)
        moveTo(50f, 64f); lineTo(50f, 75f)
        moveTo(1f, 25f); lineTo(33f, 25f)
        moveTo(1f, 51f); lineTo(33f, 51f)
        moveTo(67f, 25f); lineTo(99f, 25f)
        moveTo(67f, 51f); lineTo(99f, 51f)
        moveTo(33f, 38f); lineTo(67f, 38f)
    }
    private val metal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(8f, 0f, 91f, 76f,
            intArrayOf(0xFF99723B.toInt(), 0xFFE2C98D.toInt(), 0xFFF3E4B6.toInt(),
                0xFFB79250.toInt(), 0xFFD8BD7F.toInt(), 0xFF9D773E.toInt()),
            floatArrayOf(0f, .2f, .39f, .58f, .79f, 1f), Shader.TileMode.CLAMP)
    }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(33f, 12f, 67f, 64f,
            intArrayOf(0xFFD2B572.toInt(), 0xFFF1DDA5.toInt(), 0xFFB38F4D.toInt()),
            floatArrayOf(0f, .46f, 1f), Shader.TileMode.CLAMP)
    }
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF70552F.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.15f
        strokeJoin = Paint.Join.ROUND
    }
    private val bevel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFF1C6.toInt(); style = Paint.Style.STROKE; strokeWidth = .65f
    }
    private val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x12FFF5D1; strokeWidth = .35f }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF806135.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.1f
    }

    fun draw(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        val checkpoint = canvas.save()
        try {
            canvas.translate(left, top)
            canvas.scale(width / 100f, height / 76f)
            canvas.drawPath(plate, metal)
            canvas.save()
            canvas.clipPath(plate)
            canvas.drawRoundRect(33f, 12f, 67f, 64f, 5f, 5f, center)
            // Fine machining lines remain quiet at normal card size.
            for (line in 1..48) {
                val y = line * 1.55f
                canvas.drawLine(0f, y, 100f, y - .7f, grain)
            }
            canvas.save()
            canvas.translate(.45f, .7f)
            canvas.drawPath(contacts, bevel)
            canvas.restore()
            canvas.drawPath(contacts, groove)
            canvas.restore()
            canvas.drawRoundRect(outline, 10f, 10f, edge)
            canvas.drawRoundRect(inset, 9f, 9f, bevel)
        } finally { canvas.restoreToCount(checkpoint) }
    }
}
