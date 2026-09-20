package com.gyftalala.omni.ai

import com.gyftalala.omni.MemorySearch
import com.gyftalala.omni.data.*
import org.junit.Assert.*
import org.junit.Test

class MemorySearchTest {
    private val memories = listOf(
        Memory("card1", "IndusInd debit card", "", Category.CARD, card = CardDetails(issuer = "IndusInd")),
        Memory("doc1", "Bank statement", "", Category.DOCUMENT, ocr = "Savings account September"),
        Memory("apk1", "Example app", "", Category.APK, files = listOf(Attachment("f1", "example.apk", "application/octet-stream", 100))),
    )
    @Test fun `bank and card type retrieves correct card`() {
        assertEquals(listOf("card1"), MemorySearch.find(memories, IntentEngine().searchTerms("send me IndusInd debit card")).map { it.id })
    }
    @Test fun `missing card yields no invented result`() { assertTrue(MemorySearch.find(memories, "HDFC card").isEmpty()) }
    @Test fun `document OCR is searchable`() { assertEquals("doc1", MemorySearch.find(memories, "September savings").single().id) }
    @Test fun `plural category works`() { assertEquals("apk1", MemorySearch.find(memories, "my saved APKs").single().id) }
    @Test fun `all query works`() { assertEquals(3, MemorySearch.find(memories, "all saved files").size) }
    @Test fun `send task is not retrieval`() { assertFalse(IntentEngine().isRetrievalQuery("send bank report")) }
    @Test fun `PDF instructions do not become reminders`() { assertEquals(Category.DOCUMENT, IntentEngine().classify("Please pay bank invoice for phone", "statement.pdf", "application/pdf").category) }
}
