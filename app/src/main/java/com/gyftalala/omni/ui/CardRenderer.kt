package com.gyftalala.omni.ui

import android.graphics.*
import com.gyftalala.omni.data.Memory
import java.io.OutputStream

/** Deterministic rendering preserves owner-confirmed fields exactly. No image model handles card data. */
object CardRenderer {
    fun render(memory: Memory, output: OutputStream, photo: Bitmap? = null) {
        require(memory.card != null || memory.category == com.gyftalala.omni.data.Category.CARD || memory.identity != null)
        val finish = CardPalette.forMemory(memory)
        val bitmap = Bitmap.createBitmap(1200, 760, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, 1200f, 760f, finish.start, finish.end, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(0f, 0f, 1200f, 760f, 52f, 52f, paint)
            paint.shader = null
            paint.color = finish.edge
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawRoundRect(1f, 1f, 1199f, 759f, 52f, 52f, paint)
            paint.style = Paint.Style.FILL
            fun text(value: String, x: Float, y: Float, size: Float, color: Int = Color.rgb(231, 228, 222), width: Float = 1200f - x - 64f, mono: Boolean = false) {
                paint.color = color; paint.textSize = size; paint.typeface = Typeface.create(if (mono) "monospace" else "sans-serif", Typeface.NORMAL)
                val maxWidth = width
                if (paint.measureText(value) > maxWidth) paint.textSize *= maxWidth / paint.measureText(value)
                canvas.drawText(value, x, y, paint)
            }
            val card = memory.card ?: com.gyftalala.omni.data.CardDetails()
            val identity = memory.identity
            if (identity != null) {
                if (photo != null) {
                    canvas.save()
                    canvas.clipPath(Path().apply { addRoundRect(0f, 0f, 1200f, 760f, 52f, 52f, Path.Direction.CW) })
                    val scale = maxOf(1200f / photo.width, 760f / photo.height)
                    val width = photo.width * scale; val height = photo.height * scale
                    canvas.drawBitmap(photo, null, RectF((1200f - width) / 2, (760f - height) / 2, (1200f + width) / 2, (760f + height) / 2), paint)
                    paint.shader = LinearGradient(0f, 0f, 0f, 760f, intArrayOf(0x66000000, 0x33000000, 0xEE000000.toInt()), floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, 0f, 1200f, 760f, paint); paint.shader = null
                    canvas.restore()
                }
                text(identity.kind.label, 70f, 115f, 42f)
                text(identity.name.ifBlank { "Name not entered" }, 70f, 535f, 44f)
                text(identity.number.ifBlank { "Number not entered" }, 70f, 615f, 54f)
                if (identity.birthDate.isNotBlank()) text("Born ${identity.birthDate}", 70f, 685f, 30f)
            } else {
                // Match the wallet face: chip at left, type/issuer at right, compact number and footer.
                CardChipArtwork().draw(canvas, 70f, 70f, 112f, 85.12f)
                val type = card.type.removeSuffix(" card").uppercase()
                paint.textSize = 30f; paint.typeface = Typeface.MONOSPACE
                val pillWidth = (paint.measureText(type) + 48f).coerceAtMost(360f)
                paint.color = 0x22FFFFFF
                canvas.drawRoundRect(1130f - pillWidth, 60f, 1130f, 113f, 26f, 26f, paint)
                text(type, 1154f - pillWidth, 96f, 30f, width = pillWidth - 48f, mono = true)
                val issuer = card.issuer.ifBlank { memory.title }.uppercase()
                paint.textSize = 32f; paint.typeface = Typeface.MONOSPACE
                val issuerWidth = paint.measureText(issuer).coerceAtMost(830f)
                text(issuer, 1130f - issuerWidth, 163f, 32f, Color.rgb(195, 197, 200), issuerWidth, true)
                val label = Color.rgb(173, 180, 183)
                text("CARD NUMBER", 70f, 458f, 29f, label, mono = true)
                text(card.number.chunked(4).joinToString(" ").ifBlank { "Number not entered" }, 70f, 525f, 52f, mono = true)
                text("HOLDER", 70f, 620f, 28f, label, mono = true)
                text("EXPIRY", 585f, 620f, 28f, label, mono = true)
                text("CVV", 795f, 620f, 28f, label, mono = true)
                text("TYPE", 975f, 620f, 28f, label, mono = true)
                text(card.holder.ifBlank { "Not entered" }, 70f, 677f, 38f, width = 460f)
                text(card.expiry.ifBlank { "—" }, 585f, 677f, 36f, width = 165f, mono = true)
                text(card.cvv.ifBlank { "—" }, 795f, 677f, 36f, width = 140f, mono = true)
                text(card.network.uppercase(), 975f, 677f, 38f, width = 160f)

            }
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Card export failed." }
        } finally { bitmap.recycle() }
    }
}
