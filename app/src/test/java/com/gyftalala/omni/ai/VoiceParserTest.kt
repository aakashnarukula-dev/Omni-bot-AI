package com.gyftalala.omni.ai

import com.gyftalala.omni.data.*
import org.junit.Assert.*
import org.junit.Test

class VoiceParserTest {
    @Test fun multipleKindsStaySeparate() {
        val items = VoiceParser.parse("Call Sujatha tomorrow at 8 pm and buy a USB Type-C cable. Note my design idea")
        assertEquals(3, items.size)
        assertEquals(listOf(Category.REMINDER, Category.PRODUCT, Category.NOTE), items.map { it.category })
        assertEquals(3, items.map { it.id }.distinct().size)
    }
    @Test fun conjunctionsInsideNamesAndProductsStayTogether() {
        assertEquals(listOf("Buy bread and butter", "call Ram and Sita"), VoiceParser.split("Buy bread and butter and call Ram and Sita"))
    }
    @Test fun reminderWithoutTimeWaitsForClarification() {
        val item = VoiceParser.parse("Do puja").single()
        assertEquals(Category.REMINDER, item.category); assertNull(item.at)
        assertEquals(TopicTag.HOME, item.tag)
    }
    @Test fun retrievalIsNotSavedAsNewCard() {
        assertTrue(VoiceParser.parse("Show my saved IndusInd debit card").single().query)
    }
    @Test fun urgentHealthAndLowWorkTags() {
        val a = VoiceParser.parse("Call doctor urgently").single()
        assertEquals(TopicTag.HEALTH, a.tag)
        assertEquals(TaskPriority.URGENT, TaskHints.priority("Call doctor ASAP"))
        assertEquals(TaskPriority.LOW, TaskHints.priority("Review office notes, no rush"))
        assertEquals(TopicTag.WORK, TaskHints.tag("Review office notes"))
    }
    @Test fun spokenTimesAndMeridiemPunctuationAreUnderstood() {
        assertNotNull(VoiceParser.time("Call doctor tomorrow at eight thirty p.m."))
        assertNotNull(VoiceParser.time("Do puja in ten minutes"))
        assertNull(VoiceParser.time("Call doctor tomorrow"))
        assertNull(VoiceParser.time("Call doctor at eight"))
    }
    @Test fun emptyAndOversizeInputHandled() {
        assertTrue(VoiceParser.parse("  ").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { VoiceParser.parse("x".repeat(16001)) }
        assertThrows(IllegalArgumentException::class.java) { VoiceParser.parse((1..13).joinToString(" and ") { "call person $it" }) }
    }
}
