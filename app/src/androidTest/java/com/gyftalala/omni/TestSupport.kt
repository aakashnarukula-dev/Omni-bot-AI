package com.gyftalala.omni

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.gyftalala.omni.ai.GeminiClient
import com.gyftalala.omni.data.OmniStore
import com.gyftalala.omni.reminders.ReminderScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object TestSupport {
    val context: Context get() = ApplicationProvider.getApplicationContext()
    fun requireIsolated() {
        check(context.packageName == "com.gyftalala.omni.verification") {
            "Refusing to run destructive setup in the personal app. Build with -PomniVerification=true."
        }
    }
    fun reset() {
        requireIsolated()
        OmniStore(context).use { store -> store.reminders().forEach { ReminderScheduler(context).cancel(it.id) } }
        context.getSystemService(NotificationManager::class.java).cancelAll()
        context.deleteDatabase("omni.db")
        File(context.noBackupFilesDir, "vault").deleteRecursively()
        File(context.cacheDir, "captures").deleteRecursively()
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
    }
    fun shell(command: String) {
        requireIsolated()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
    }
    fun vm(client: GeminiClient = GeminiClient()): OmniViewModel = OmniViewModel(context.applicationContext as Application, client).also { await(it.refresh()) }
    fun await(job: Job) = runBlocking { job.join() }
    fun file(name: String, content: ByteArray = "Synthetic test content".toByteArray()): Pair<File, Uri> {
        requireIsolated()
        val file = File(context.cacheDir, "captures/$name").apply { parentFile?.mkdirs(); writeBytes(content) }
        return file to FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    fun image(name: String, lines: List<String>): Uri {
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 46f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        lines.forEachIndexed { index, text -> canvas.drawText(text, 60f, 100f + index * 82f, paint) }
        return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); file(name, it.toByteArray()).second } }
        finally { bitmap.recycle() }
    }
    fun pdf(name: String, pages: Int = 1): Pair<Uri, ByteArray> {
        val bytes = ByteArrayOutputStream()
        val pdf = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 800, index + 1).create())
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 24f }
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("Bank statement September", 30f, 70f, paint)
                page.canvas.drawText("Savings statement page ${index + 1}", 30f, 120f, paint)
                page.canvas.drawText("MARKERPAGE${index + 1}", 30f, 180f, paint)
                pdf.finishPage(page)
            }
            pdf.writeTo(bytes)
        } finally { pdf.close() }
        return file(name, bytes.toByteArray()).second to bytes.toByteArray()
    }
    fun waitUntil(timeout: Long = 12000, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < end) { "Timed out waiting for expected test state." }
            Thread.sleep(100)
        }
    }
}

class FakeGemini(private val intent: String = "note", private val status: Int = 200, private val malformed: Boolean = false) {
    var calls = 0
    var lastConnection: FakeConnection? = null
    val client = GeminiClient { url -> clientConnection(url) }
    fun clientConnection(url: URL): FakeConnection {
        calls++
        val classification = JSONObject().put("intent", intent).put("title", "AI test title").put("summary", "Test classification")
            .put("confidence", .9).put("clarification_question", if (intent == "unknown") "Design or shopping?" else JSONObject.NULL)
            .put("alternatives", if (intent == "unknown") JSONArray(listOf("ux_design", "product")) else JSONArray())
        val body = if (malformed) "invalid response" else JSONObject().put("candidates", JSONArray().put(
            JSONObject().put("content", JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("thought", true).put("text", "Not user-facing"))
                .put(JSONObject().put("text", classification.toString())))))).toString()
        return FakeConnection(url, status, body).also { lastConnection = it }
    }
}

class FakeConnection(url: URL, private val status: Int, private val body: String) : HttpURLConnection(url) {
    val request = ByteArrayOutputStream()
    var disconnected = false
    override fun connect() = Unit
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun getOutputStream() = request
    override fun getResponseCode() = status
    override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
}
