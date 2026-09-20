package com.gyftalala.omni

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.data.OmniStore
import com.gyftalala.omni.ui.ImagePreviews
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImagePreviewTest {
    @Before fun reset() = TestSupport.reset()
    @Test fun cameraOrientationAndMemoryCachePreserveOriginal() = runBlocking {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() }
        bitmap.recycle()
        val (original, uri) = TestSupport.file("camera.jpg", bytes)
        ExifInterface(original.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val rotatedBytes = original.readBytes()
        OmniStore(TestSupport.context).use { store ->
            val file = store.vault.import(uri)
            assertTrue(original.delete()) // Preview must use the vault, not an expired picker URI.
            val previews = ImagePreviews(store.vault)
            val preview = previews.load(file)
            assertEquals(40, preview.width)
            assertEquals(80, preview.height)
            assertSame(preview, previews.load(file))
            assertArrayEquals(rotatedBytes, store.vault.read(file))
            val legacy = ImagePreviews.decodeLegacy(rotatedBytes)
            assertEquals(40, legacy.width)
            assertEquals(80, legacy.height)
            legacy.recycle()
        }
    }

    @Test fun largeImageIsDownsampledAndCorruptImageIsRejected() {
        val bitmap = Bitmap.createBitmap(4000, 2000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        for (decode in listOf<(ByteArray) -> Bitmap>(ImagePreviews::decode, ImagePreviews::decodeLegacy)) {
            val preview = decode(bytes)
            assertTrue(maxOf(preview.width, preview.height) <= 1400)
            assertEquals(2f, preview.width.toFloat() / preview.height, .01f)
            assertEquals(Color.BLUE, preview.getPixel(0, 0))
            preview.recycle()
            assertTrue(runCatching { decode(byteArrayOf(1, 2, 3)) }.isFailure)
        }
    }
}
