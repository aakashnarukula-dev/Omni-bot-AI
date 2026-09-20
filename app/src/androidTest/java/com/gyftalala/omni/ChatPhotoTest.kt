package com.gyftalala.omni

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.ai.GeminiClient
import com.gyftalala.omni.data.*
import com.gyftalala.omni.ui.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatPhotoTest {
    @get:Rule val compose = createAndroidComposeRule<VerificationActivity>()
    @Before fun reset() = TestSupport.reset()
    private fun render(vm: OmniViewModel) {
        compose.setContent { OmniTheme {
            val state by vm.state.collectAsState()
            var selected by remember { mutableStateOf<String?>(null) }
            OmniApp(state, vm, selected, { selected = it }, { _, _, _ -> }, {}, {}, {}, {})
        } }
    }
    private fun showPhoto(id: String) {
        compose.onNodeWithTag("chat-messages").performScrollToNode(hasTestTag("chat-image-$id-container"))
        compose.waitUntil(10000) { compose.onAllNodesWithTag("chat-image-$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-image-$id", useUnmergedTree = true).assertIsDisplayed()
    }
    private fun photo() = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888).let { bitmap ->
        val canvas = Canvas(bitmap).apply { drawColor(Color.rgb(25, 88, 100)) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 180, 85) }
        canvas.drawCircle(400f, 400f, 230f, paint)
        paint.color = Color.WHITE; paint.textSize = 65f
        canvas.drawText("A photo to remember", 85f, 780f, paint)
        try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            TestSupport.file("remember.png", it.toByteArray()).second } } finally { bitmap.recycle() }
    }

    @Test fun existingFilenameMessageShowsPhotoAndOpensOriginal() {
        val store = OmniStore(TestSupport.context)
        val file = store.vault.import(photo())
        val memory = Memory("legacy-photo", "Saved design", "", Category.UX_DESIGN, files = listOf(file))
        store.use {
            it.save(memory)
            it.save(ChatMessage("old-message", Role.USER, file.name, 1, attachmentId = memory.id))
            it.save(ChatMessage("old-reply", Role.ASSISTANT, "Saved to UX design.", 2, attachmentId = memory.id))
        }
        render(TestSupport.vm())
        showPhoto(file.id)
        compose.onNodeWithText(file.name).assertDoesNotExist()
        File(TestSupport.context.getExternalFilesDir(null), "chat-photo-preview.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription("Library").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("receipt-image-${memory.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("receipt-image-${memory.id}", useUnmergedTree = true).assertIsDisplayed()
        File(TestSupport.context.getExternalFilesDir(null), "library-photo-preview.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription("Chat").performClick()
        showPhoto(file.id)
        compose.onNodeWithTag("chat-image-${file.id}", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Original files").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(file.name).performScrollTo().assertIsDisplayed()
    }

    @Test fun captionAndMixedFileSurviveReload() {
        val vm = TestSupport.vm()
        TestSupport.await(vm.send("Keep this reference", listOf(photo(), TestSupport.file("notes.txt").second), Category.UX_DESIGN))
        val memory = vm.state.value.memories.single()
        val reloaded = TestSupport.vm()
        val message = reloaded.state.value.messages.first { it.role == Role.USER }
        assertEquals(memory.files.map { it.id }, message.fileIds)
        render(reloaded)
        showPhoto(memory.files.first().id)
        compose.onNodeWithTag("chat-messages").performScrollToNode(hasText("Keep this reference"))
        compose.onNodeWithText("Keep this reference").assertIsDisplayed()
        compose.onNodeWithText("notes.txt").assertIsDisplayed()
        // The saved-item receipt may use the filename as its title; the photo bubble must not.
        compose.onAllNodesWithText("remember.png").assertCountEquals(1)
        compose.onNodeWithText("remember.png").assert(hasText("UX design • 2 files"))
    }

    @Test fun multiplePhotosCanBeBrowsedAndAppendedSideStaysSeparate() {
        val vm = TestSupport.vm()
        val first = TestSupport.image("front.png", listOf("Front photo"))
        val second = TestSupport.image("back.png", listOf("Back photo"))
        TestSupport.await(vm.send("", listOf(first, second), Category.CARD))
        val memory = vm.state.value.memories.single()
        TestSupport.await(vm.send("", listOf(TestSupport.image("extra.png", listOf("Extra photo"))), Category.CARD, memory.id))
        val reloaded = TestSupport.vm()
        val messages = reloaded.state.value.messages.filter { it.role == Role.USER }
        assertEquals(memory.files.map { it.id }, messages.first().fileIds)
        assertEquals(1, messages.last().fileIds!!.size)
        assertFalse(messages.first().fileIds!!.contains(messages.last().fileIds!!.single()))
        render(reloaded)
        val gallery = "photo-gallery-${memory.files.first().id}"
        compose.onNodeWithTag("chat-messages").performScrollToNode(hasTestTag(gallery))
        compose.onNodeWithTag(gallery).performScrollToIndex(1)
        compose.waitUntil(10000) { compose.onAllNodesWithTag("chat-image-${memory.files[1].id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-image-${memory.files[1].id}", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun corruptImageHasFallbackAndOriginalIsStillAvailable() {
        val vm = TestSupport.vm()
        TestSupport.await(vm.send("", listOf(TestSupport.file("broken.png", byteArrayOf(1, 2, 3)).second), Category.UX_DESIGN))
        render(vm)
        compose.waitUntil(10000) { compose.onAllNodesWithText("Preview unavailable").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-messages").performScrollToNode(hasText("Preview unavailable"))
        compose.onNodeWithText("Preview unavailable").assertIsDisplayed().performClick()
        compose.onNodeWithText("Original files").performScrollTo().assertIsDisplayed()
        assertArrayEquals(byteArrayOf(1, 2, 3), vm.vault.read(vm.state.value.memories.single().files.single()))
    }

    @Test fun photoAppearsWhileAiIsStillPending() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val vm = TestSupport.vm(GeminiClient { url ->
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS))
            FakeConnection(url, 503, "Synthetic failure")
        })
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        val job = vm.send("", listOf(photo()))
        try {
            assertTrue(entered.await(12, TimeUnit.SECONDS))
            render(vm)
            showPhoto(vm.state.value.memories.single().files.single().id)
            assertTrue(vm.state.value.busy)
        } finally { release.countDown(); TestSupport.await(job) }
    }
}
