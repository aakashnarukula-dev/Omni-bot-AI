package com.gyftalala.omni.ai

import com.gyftalala.omni.data.CardDetails
import org.junit.Assert.*
import org.junit.Test

class CardScanReaderTest {
    @Test fun `front reads printed name with valid number and avoids guessing type`() {
        val card = CardScanReader.extract("IndusInd\n4111 1111 1111 1111\nVALID THRU 09/29\nTEST OWNER\nVISA", false)
        assertEquals("TEST OWNER", card.holder)
        assertEquals("4111111111111111", card.number)
        assertEquals("09/29", card.expiry)
        assertEquals("Payment card", card.type)
    }
    @Test fun `multiple possible names and bank marketing are not guessed`() {
        assertEquals("", CardScanReader.extract("4111 1111 1111 1111\nTEST OWNER\nANOTHER NAME", false).holder)
        assertEquals("", CardScanReader.extract("4111 1111 1111 1111\nVALID THRU\nPLATINUM CARD", false).holder)
        assertEquals("", CardScanReader.extract("TEST OWNER", false).holder)
    }
    @Test fun `common printed issuer letter confusion is normalized`() {
        assertEquals("IndusInd", CardScanReader.extract("Induslnd debit card", false).issuer)
    }
    @Test fun `bare back code requires explicit back scan and one candidate`() {
        assertEquals("123", CardScanReader.extract("Authorized signature\n123", true).cvv)
        assertEquals("", CardScanReader.extract("123", false).cvv)
        assertEquals("", CardScanReader.extract("Call 123 for service", true).cvv)
        assertEquals("", CardScanReader.extract("123\n456", true).cvv)
        assertEquals("", CardExtractor.extract("123").cvv)
    }
    @Test fun `back signature number must match front suffix`() {
        val front = CardDetails(number = "4111111111111111")
        assertEquals("123", CardScanReader.extract("1111 123", true, front).cvv)
        assertEquals("", CardScanReader.extract("2222 123", true, front).cvv)
    }
    @Test fun `back preserves corrected front fields`() {
        val front = CardDetails("My Bank", "CORRECTED OWNER", "4111111111111111", "09/29", type = "Credit card")
        val result = CardScanReader.extract("Other bank\nCardholder: WRONG\nCVV: 123\nRuPay", true, front)
        assertEquals(front.issuer, result.issuer)
        assertEquals(front.holder, result.holder)
        assertEquals(front.number, result.number)
        assertEquals(front.type, result.type)
        assertEquals("123", result.cvv)
        assertEquals("RuPay", result.network)
    }
    @Test fun `different card number is detected before pairing`() {
        val front = CardDetails(number = "4111111111111111")
        assertTrue(CardScanReader.differentCard("5555 5555 5555 4444", front))
        assertFalse(CardScanReader.differentCard("4111 1111 1111 1111", front))
        assertFalse(CardScanReader.differentCard("CVV: 123", front))
    }
    @Test fun `auto capture needs three consecutive matching valid numbers`() {
        val stable = StableCardRead()
        val card = CardDetails(number = "4111111111111111")
        assertFalse(stable.accept(card, false)); assertFalse(stable.accept(card, false))
        assertTrue(stable.accept(card, false))
        assertFalse(stable.accept(CardDetails(), false))
        assertFalse(stable.accept(card, false))
        assertFalse(stable.accept(CardDetails(number = "4111111111111112"), false))
    }
    @Test fun `changed number resets stability and back needs code`() {
        val stable = StableCardRead()
        assertFalse(stable.accept(CardDetails(number = "4111111111111111"), false))
        val other = CardDetails(number = "5555555555554444")
        assertFalse(stable.accept(other, false)); assertFalse(stable.accept(other, false))
        assertTrue(stable.accept(other, false))
        val back = StableCardRead()
        assertFalse(back.accept(other, true))
        repeat(2) { assertFalse(back.accept(CardDetails(cvv = "123"), true)) }
        assertTrue(back.accept(CardDetails(cvv = "123"), true))
    }
}
