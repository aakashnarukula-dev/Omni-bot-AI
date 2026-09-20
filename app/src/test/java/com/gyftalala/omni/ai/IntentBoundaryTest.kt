package com.gyftalala.omni.ai

import com.gyftalala.omni.data.Category
import org.junit.Assert.*
import org.junit.Test

class IntentBoundaryTest {
    private val engine = IntentEngine()
    @Test fun `card-related task is still a task`() {
        assertEquals(Category.REMINDER, engine.classify("Call bank about my credit card").category)
        assertEquals(Category.REMINDER, engine.classify("Remind me to pay credit card bill").category)
    }
    @Test fun `mention of book in note does not create reminder`() {
        assertEquals(Category.NOTE, engine.classify("This book was a great read").category)
    }
    @Test fun `shopping domain inside screenshot does not skip clarification`() {
        assertEquals(Category.UNKNOWN, engine.classify("https://amazon.in Shoes Checkout Add to cart", isImage = true).category)
    }
    @Test fun `generic category remains in search query`() {
        assertEquals("card", engine.searchTerms("show my card"))
        assertEquals("document", engine.searchTerms("send me document"))
    }
    @Test fun `search and list commands retrieve existing items`() {
        assertTrue(engine.isRetrievalQuery("list my cards"))
        assertTrue(engine.isRetrievalQuery("search for bank statement"))
    }
}
