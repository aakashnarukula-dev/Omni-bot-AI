package com.gyftalala.omni

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.ai.GeminiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeminiContractTest {
    @Before fun reset() = TestSupport.reset()

    @Test fun modelListPaginatesFiltersAndKeepsKeyInHeader() = runBlocking {
        val requests = mutableListOf<FakeConnection>()
        val client = GeminiClient { url ->
            val body = if (requests.isEmpty()) """{"models":[
                {"name":"models/gemini-3.8-flash","displayName":"Gemini 3.8 Flash","supportedGenerationMethods":["generateContent"]},
                {"name":"models/gemini-3.1-flash-image","supportedGenerationMethods":["generateContent"]},
                {"name":"models/gemini-embedding-2","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"next/+="}"""
            else """{"models":[{"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent"]}]}"""
            FakeConnection(url, 200, body).also { requests += it }
        }
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash-lite"), client.listModels("synthetic-key").map { it.id })
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.requestMethod == "GET" && it.disconnected && !it.url.toString().contains("synthetic-key") })
        assertTrue(requests.all { it.getRequestProperty("x-goog-api-key") == "synthetic-key" })
        assertTrue(requests.last().url.toString().contains("pageToken=next%2F%2B%3D"))
    }

    @Test fun repeatedPaginationStopsWithoutHanging() = runBlocking {
        var calls = 0
        val client = GeminiClient { url -> calls++; FakeConnection(url, 200, """{"models":[],"nextPageToken":"same"}""") }
        assertTrue(runCatching { client.listModels("synthetic-key") }.isFailure)
        assertEquals(2, calls)
    }

    @Test fun unavailableAndDeniedModelsHaveActionableErrors() = runBlocking {
        for (status in listOf(401, 403, 404, 429, 503)) {
            val connection = FakeConnection(java.net.URL("https://generativelanguage.googleapis.com"), status, "private server response")
            val error = runCatching { GeminiClient { connection }.listModels("synthetic-key") }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty().contains(status.toString()))
            assertFalse(error.message.orEmpty().contains("private server response"))
            assertTrue(connection.disconnected)
        }
    }

    @Test fun modelProbeUsesOnlySampleAndDoesNotModifyVault() {
        val fake = FakeGemini("product")
        val vm = TestSupport.vm(fake.client)
        TestSupport.await(vm.send("Private journal not for AI"))
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", false))
        TestSupport.await(vm.checkModel(null, "gemini-3.8-flash"))
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.modelCheck?.passed == true)
        assertEquals(1, vm.state.value.memories.size)
        val request = fake.lastConnection!!.request.toString("UTF-8")
        assertTrue(request.contains("USB Type-C"))
        val config = org.json.JSONObject(request).getJSONObject("generationConfig")
        assertEquals("APPLICATION_JSON", config.getJSONObject("responseFormat").getJSONObject("text").getString("mimeType"))
        assertEquals("LOW", config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        java.io.File(TestSupport.context.getExternalFilesDir(null), "gemini-synthetic-request.json").writeText(request)
        assertFalse(request.contains("Private journal"))
        assertFalse(vm.state.value.cloud)
    }

    @Test fun changedModelOrKeyClearsOldConnectionResultAndBrowsingKeepsIt() {
        val fake = FakeGemini("product")
        val client = GeminiClient { url -> if (url.path.endsWith("/models")) FakeConnection(url, 200, """{"models":[]}""") else fake.clientConnection(url) }
        val vm = TestSupport.vm(client)
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", false))
        TestSupport.await(vm.checkModel(null, "gemini-3.8-flash"))
        TestSupport.await(vm.loadModels(null))
        assertTrue(vm.state.value.modelCheck?.passed == true)
        TestSupport.await(vm.settings(null, "gemini-3.5-flash-lite", false))
        assertNull(vm.state.value.modelCheck)
        TestSupport.await(vm.checkModel(null, "gemini-3.5-flash-lite"))
        TestSupport.await(vm.settings("replacement-synthetic-key", "gemini-3.5-flash-lite", false))
        assertNull(vm.state.value.modelCheck)
        TestSupport.await(vm.settings("", "gemini-3.5-flash-lite", true))
        assertFalse(vm.state.value.cloud)
    }

    @Test fun rejectedRequestExplainsFormatWithoutBlamingOrExposingKey() {
        val body = """{"error":{"code":400,"message":"Invalid value at generation_config.response_format.text.mime_type; synthetic-key"}}"""
        val vm = TestSupport.vm(GeminiClient { url -> FakeConnection(url, 400, body) })
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        TestSupport.await(vm.checkModel(null, "gemini-3.8-flash"))
        val check = vm.state.value.modelCheck!!
        assertFalse(check.passed)
        assertTrue(check.message.contains("mime_type"))
        assertFalse(check.message.contains("synthetic-key"))
        assertFalse(check.message.contains("Check the key"))
    }

    @Test fun invalidModelPathIsRejectedBeforeNetwork() = runBlocking {
        var calls = 0
        val client = GeminiClient { url -> calls++; FakeConnection(url, 200, "{}") }
        assertTrue(runCatching { client.classify("synthetic-key", "../other?key=bad", "Sample", "") }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun sharedBatchLimitIsReportedInsteadOfSilentlyDroppingFiles() {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList((1..11).map { android.net.Uri.parse("content://synthetic/$it") }))
        val share = IncomingShare.parse(intent)!!
        assertEquals(11, share.files.size)
        val vm = TestSupport.vm()
        TestSupport.await(vm.send(share.text, share.files))
        assertTrue(vm.state.value.error.orEmpty().contains("10 files"))
        assertTrue(vm.state.value.memories.isEmpty())
    }
}
