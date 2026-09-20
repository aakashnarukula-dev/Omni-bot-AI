package com.gyftalala.omni.ai

import com.gyftalala.omni.data.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentEngineTest {
    private val engine = IntentEngine()

    @Test fun `cable becomes product`() {
        assertEquals(Category.PRODUCT, engine.classify("USB Type-C to Type-C cable").category)
    }

    @Test fun `short task becomes reminder`() {
        val result = engine.classify("Do puja")
        assertEquals(Category.REMINDER, result.category)
        assertEquals("When should I remind you?", result.clarificationQuestion)
    }

    @Test fun `implicit medical update becomes reminder`() {
        assertEquals(Category.REMINDER, engine.classify("angiogram discharge summary update").category)
    }

    @Test fun `call becomes reminder`() {
        assertEquals(Category.REMINDER, engine.classify("call Sujatha madam").category)
    }

    @Test fun `amazon link becomes product`() {
        assertEquals(Category.PRODUCT, engine.classify("https://amazon.in/dp/B012345").category)
    }

    @Test fun `apk stays apk`() {
        assertEquals(Category.APK, engine.classify("", "release.apk", "application/vnd.android.package-archive").category)
    }

    @Test fun `product screenshot asks user`() {
        val result = engine.classify("Checkout Shoe Add to cart", isImage = true)
        assertEquals(Category.UNKNOWN, result.category)
        assertEquals(listOf(Category.UX_DESIGN, Category.PRODUCT), result.alternatives)
    }

    @Test fun `card is sensitive and masked`() {
        val result = engine.classify("IndusInd debit card 4111 1111 1111 1234 valid thru 09/29")
        assertEquals(Category.CARD, result.category)
        assertEquals("1234", result.lastFour)
        assertEquals("IndusInd", result.issuer)
    }

    @Test fun `retrieval strips command words`() {
        assertTrue(engine.isRetrievalQuery("send me my IndusInd debit card"))
        assertEquals("IndusInd debit card", engine.searchTerms("send me my IndusInd debit card"))
    }
}
