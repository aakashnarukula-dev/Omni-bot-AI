package com.gyftalala.omni

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.data.*
import com.gyftalala.omni.sharing.WalletShares
import com.gyftalala.omni.ui.ImagePreviews
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException

@RunWith(AndroidJUnit4::class)
class WalletSharingTest {
    @Before fun reset() = TestSupport.reset()
    private fun card() = Memory("share", "Test bank", "", Category.CARD,
        card = CardDetails("TEST BANK", "TEST OWNER", "4111111111111111", "09/29", "123", "Debit", "VISA"))
    private fun pngText(uri: Uri): String {
        val bytes = TestSupport.context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(1200, bitmap.width); assertEquals(760, bitmap.height)
        val reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try { Tasks.await(reader.process(InputImage.fromBitmap(bitmap, 0))).text }
        finally { reader.close(); bitmap.recycle() }
    }
    @Test fun shareIsPngWithFullFieldsAndReadOnlyGrant() {
        val context = TestSupport.context; val vm = TestSupport.vm()
        val uri = WalletShares.prepare(context, card(), vm.vault)
        val intent = WalletShares.intent(context, uri)
        assertEquals(Intent.ACTION_SEND, intent.action); assertEquals("image/png", intent.type)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
        context.contentResolver.query(uri, null, null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals("Omni card.png", it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertTrue(it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE)) > 1000)
        }
        val text = pngText(uri)
        listOf("TEST BANK", "TEST OWNER", "09/29", "123", "VISA").forEach { assertTrue("Missing $it in PNG", text.contains(it)) }
        assertTrue(text.replace(" ", "").contains("4111111111111111"))
        try { context.contentResolver.openFileDescriptor(uri, "rw"); fail("Write grant accepted") } catch (_: FileNotFoundException) { }
        WalletShares.discard(uri)
        try { context.contentResolver.openInputStream(uri); fail("Revoked token accepted") } catch (_: FileNotFoundException) { }
    }
    @Test fun expiredUnknownAndAlteredUrisAreRejected() {
        val context = TestSupport.context
        val expired = WalletShares.register(context, byteArrayOf(1), SystemClock.elapsedRealtime() - WalletShares.LIFETIME_MS)
        val live = WalletShares.prepare(context, card(), TestSupport.vm().vault)
        listOf(expired, live.buildUpon().appendPath("original").build(), live.buildUpon().query("key=123").build(),
            live.buildUpon().path("/card/missing").build()).forEach { uri ->
            try { context.contentResolver.openInputStream(uri); fail("Invalid share URI accepted") } catch (_: FileNotFoundException) { }
        }
    }
    @Test fun identityShareUsesSavedCorrectionsWithoutPlaintextCache() {
        val context = TestSupport.context; val vm = TestSupport.vm()
        val photo = TestSupport.image("share-id.png", listOf("SYNTHETIC ID", "ABCDE1234F"))
        val attachment = vm.vault.import(photo)
        val encrypted = File(context.noBackupFilesDir, "vault/${attachment.id}").readBytes()
        val before = context.cacheDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(context.cacheDir).path }.toSet()
        val memory = Memory("id", "PAN", "", Category.DOCUMENT, files = listOf(attachment),
            identity = IdentityDetails(IdKind.PAN, "ABCDE1234F", "EDITED OWNER", "09/08/1990"))
        val text = pngText(WalletShares.prepare(context, memory, vm.vault))
        assertTrue(text.contains("EDITED OWNER")); assertTrue(text.contains("ABCDE1234F")); assertTrue(text.contains("09/08/1990"))
        assertArrayEquals(encrypted, File(context.noBackupFilesDir, "vault/${attachment.id}").readBytes())
        assertEquals(before, context.cacheDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(context.cacheDir).path }.toSet())
        val original = context.contentResolver.openInputStream(photo)!!.use { it.readBytes() }
        assertArrayEquals(original, vm.vault.read(attachment))
    }
}
