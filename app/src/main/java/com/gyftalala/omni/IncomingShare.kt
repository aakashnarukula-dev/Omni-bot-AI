package com.gyftalala.omni

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

data class IncomingShare(val text: String, val files: List<Uri>) {
    companion object {
        fun parse(intent: Intent): IncomingShare? {
            if (intent.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
            val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE)
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            val clip = intent.clipData?.let { data -> (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri } }.orEmpty()
            return IncomingShare(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().take(16000),
                (streams + clip).distinct().filter { it.scheme == "content" })
        }
    }
}
