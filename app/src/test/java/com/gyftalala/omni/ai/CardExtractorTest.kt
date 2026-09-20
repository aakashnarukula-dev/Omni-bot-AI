package com.gyftalala.omni.ai

import org.junit.Assert.*
import org.junit.Test

class CardExtractorTest {
    @Test fun `full fields remain intact`() {
        val card = CardExtractor.extract("IndusInd debit card Visa\n4111 1111 1111 1111\nvalid thru 09/29\nCVV: 123\nCardholder: TEST OWNER")
        assertEquals("4111111111111111", card.number)
        assertEquals("123", card.cvv)
        assertEquals("TEST OWNER", card.holder)
        assertEquals("09/29", card.expiry)
    }
    @Test fun `unreadable or invalid number remains empty`() {
        assertEquals("", CardExtractor.extract("IndusInd debit card 4111 1111 1111 1234").number)
        assertEquals("", CardExtractor.extract("IndusInd debit card").number)
    }
    @Test fun `unlabeled CVV never guessed`() { assertEquals("", CardExtractor.extract("Call 123 for service").cvv) }
    @Test fun `sensitive content remains local`() {
        listOf("CVV 123", "bank statement", "angiogram discharge summary", "4111 1111 1111 1111").forEach { assertTrue(CardExtractor.sensitive(it)) }
    }
    @Test fun `plain product can use AI`() { assertFalse(CardExtractor.sensitive("USB Type-C cable")) }
}
