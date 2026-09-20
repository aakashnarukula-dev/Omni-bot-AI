package com.gyftalala.omni.ai

import com.gyftalala.omni.data.Category
import org.junit.Assert.*
import org.junit.Test

class GeminiClientTest {
    @Test fun `current model uses supported low thinking and structured response`() {
        val config = GeminiClient().generationConfig("gemini-3.8-flash")
        assertEquals("LOW", config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        assertEquals("APPLICATION_JSON", config.getJSONObject("responseFormat").getJSONObject("text").getString("mimeType"))
        assertFalse(config.has("temperature"))
    }
    @Test fun `older Flash model uses budget not thinking level`() {
        val config = GeminiClient().generationConfig("gemini-2.5-flash")
        assertEquals(0, config.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
        assertFalse(config.getJSONObject("thinkingConfig").has("thinkingLevel"))
    }
    @Test fun `unknown intent remains reviewable`() {
        val result = GeminiClient().parse("""{"intent":"unexpected","title":"A screenshot","summary":"Review","confidence":0.4}""")
        assertEquals(Category.UNKNOWN, result.category)
    }
    @Test fun `product design ambiguity preserved`() {
        val result = GeminiClient().parse("""{"intent":"unknown","title":"Shoe page","confidence":0.5,"clarification_question":"Design or shopping?","alternatives":["ux_design","product","execute_code"]}""")
        assertEquals(listOf(Category.UX_DESIGN, Category.PRODUCT), result.alternatives)
        assertEquals("Design or shopping?", result.clarificationQuestion)
    }
    @Test fun `nullable clarification parsed correctly`() {
        val result = GeminiClient().parse("""{"intent":"product","title":"Cable","confidence":2,"clarification_question":null}""")
        assertNull(result.clarificationQuestion)
        assertEquals(1f, result.confidence)
    }
    @Test fun `malformed response fails so local fallback can run`() {
        assertTrue(runCatching { GeminiClient().parse("Not JSON") }.isFailure)
    }

    @Test fun `request errors show provider reason without leaking key`() {
        val key = "AIza" + "a".repeat(35)
        val error = GeminiClient().errorMessage(400,
            """{"error":{"message":"Invalid value at generation_config.response_format.text.mime_type; key $key"}}""", key)
        assertTrue(error.contains("mime_type"))
        assertFalse(error.contains(key))
        assertFalse(error.contains("Check the key"))
    }

    @Test fun `invalid key and unparseable errors have distinct guidance`() {
        assertTrue(GeminiClient().errorMessage(400, """{"error":{"message":"API key not valid. Please pass a valid API key."}}""", "test").contains("Replace it"))
        assertTrue(GeminiClient().errorMessage(400, "<html>upstream error</html>", "test").contains("request format"))
        assertTrue(GeminiClient().errorMessage(429, "", "test").contains("quota"))
    }
}
