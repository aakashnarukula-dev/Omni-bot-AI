package com.gyftalala.omni.sharing

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import com.gyftalala.omni.data.Memory
import com.gyftalala.omni.security.Vault
import com.gyftalala.omni.ui.CardRenderer
import com.gyftalala.omni.ui.ImagePreviews
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.util.UUID

/** Temporary, read-only PNG grants. Neither card fields nor decrypted photos are written to a share cache. */
object WalletShares {
    private data class Entry(val bytes: ByteArray, val expires: Long)
    private val entries = linkedMapOf<String, Entry>()
    internal const val LIFETIME_MS = 10 * 60 * 1000L

    fun prepare(context: Context, memory: Memory, vault: Vault): Uri {
        val photo = if (memory.identity != null) memory.files.firstOrNull { it.mime.startsWith("image/") }?.let {
            val bytes = vault.read(it)
            try { ImagePreviews.decode(bytes) } finally { bytes.fill(0) }
        } else null
        val bytes = try { ByteArrayOutputStream().use { CardRenderer.render(memory, it, photo); it.toByteArray() } }
        finally { photo?.recycle() }
        return register(context, bytes)
    }

    @Synchronized internal fun register(context: Context, bytes: ByteArray, now: Long = SystemClock.elapsedRealtime()): Uri {
        require(bytes.size <= 12 * 1024 * 1024) { "Card image is too large to share." }
        entries.entries.removeAll { it.value.expires <= now }
        while (entries.size >= 4) entries.remove(entries.keys.first())
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(bytes, now + LIFETIME_MS)
        val uri = Uri.Builder().scheme("content").authority("${context.packageName}.wallet-shares").appendPath("card").appendPath(token).build()
        Handler(Looper.getMainLooper()).postDelayed({ discard(uri) }, LIFETIME_MS)
        return uri
    }

    @Synchronized internal fun read(context: Context, uri: Uri, now: Long = SystemClock.elapsedRealtime()): ByteArray {
        entries.entries.removeAll { it.value.expires <= now }
        if (uri.scheme != "content" || uri.authority != "${context.packageName}.wallet-shares" ||
            uri.pathSegments.size != 2 || uri.pathSegments.first() != "card" || uri.query != null || uri.fragment != null)
            throw FileNotFoundException("Card share is unavailable.")
        return entries[uri.lastPathSegment]?.bytes ?: throw FileNotFoundException("Card share expired. Share the card again from Omni.")
    }

    @Synchronized fun discard(uri: Uri) { entries.remove(uri.lastPathSegment) }

    fun intent(context: Context, uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, "Omni card", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

class WalletShareProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String { WalletShares.read(requireNotNull(context), uri); return "image/png" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val bytes = WalletShares.read(requireNotNull(context), uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> "Omni card.png"
            OpenableColumns.SIZE -> bytes.size
            else -> null
        } }) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Card shares are read-only.")
        val bytes = WalletShares.read(requireNotNull(context), uri)
        return openPipeHelper(uri, "image/png", null, bytes) { output, _, _, _, data ->
            // A receiver may close its preview early; the stream still must close without crashing Omni.
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(data) } }
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only")
}
