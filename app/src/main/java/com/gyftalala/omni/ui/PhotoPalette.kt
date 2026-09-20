package com.gyftalala.omni.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.palette.graphics.Palette
import androidx.core.graphics.ColorUtils

/** Sample a bounded central crop; keep hues, darken finishes until off-white text stays legible. */
object PhotoPalette {
    fun fromUri(context: Context, uri: Uri): List<Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return emptyList()
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 400) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return emptyList()
        return try { fromBitmap(bitmap) } finally { bitmap.recycle() }
    }
    fun fromBitmap(bitmap: Bitmap): List<Int> {
        val w = bitmap.width; val h = bitmap.height
        val palette = Palette.from(bitmap).maximumColorCount(12).resizeBitmapArea(12000)
            .setRegion(w / 8, h / 8, w - w / 8, h - h / 8).generate()
        val source = palette.vibrantSwatch?.rgb ?: palette.mutedSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: return emptyList()
        fun readable(color: Int): Int {
            var value = ColorUtils.setAlphaComponent(color, 255)
            repeat(20) {
                if (ColorUtils.calculateContrast(0xFFE7E4DE.toInt(), value) >= 5.5) return value
                value = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(value, Color.BLACK, .12f), 255)
            }
            return value
        }
        val start = readable(source)
        return listOf(start, readable(ColorUtils.blendARGB(start, Color.BLACK, .48f)), ColorUtils.setAlphaComponent(ColorUtils.blendARGB(start, Color.WHITE, .28f), 255))
    }
}
