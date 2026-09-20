package com.gyftalala.omni.ai

import com.gyftalala.omni.data.*
import org.junit.Assert.*
import org.junit.Test

class PersonalLabelsTest {
    private fun example(text: String, category: Category, at: Long = 1) = LabelExample(text, category, at)
    @Test fun shoppingCommandsStayConsistent() {
        for (text in listOf("Order Oats", "Order seeds", "Please order almonds", "I want to buy rice", "Purchase groceries", "Reorder coffee"))
            assertEquals(text, Category.PRODUCT, IntentEngine().classify(text).category)
    }
    @Test fun timedShoppingAndExplicitRemindersWin() {
        val examples = listOf(example("Order seeds",Category.PRODUCT),example("Order oats",Category.PRODUCT))
        for (text in listOf("Remind me to order seeds", "Order seeds tomorrow", "Order seeds at 8 pm", "Order seeds 8pm", "Order seeds after lunch")) {
            assertNull(text,PersonalLabels.category(text,examples))
            assertEquals(text,Category.REMINDER,IntentEngine().classify(text).category)
        }
    }
    @Test fun latestExactCorrectionWinsAndNormalizesPolitenessOnlyForPatterns() {
        val examples = listOf(example("Desk lamp",Category.NOTE),example("Desk lamp",Category.PRODUCT,2))
        assertEquals(Category.PRODUCT,PersonalLabels.category(" DESK lamp! ",examples))
    }
    @Test fun closePhrasesUseConfirmedCategory() {
        assertEquals(Category.UX_DESIGN,PersonalLabels.category("Save checkout screen idea",listOf(example("Save checkout screen",Category.UX_DESIGN))))
    }
    @Test fun unrelatedSingleCorrectionDoesNotOvergeneralize() {
        assertNull(PersonalLabels.category("Research phones",listOf(example("Research cameras",Category.NOTE))))
        assertNull(PersonalLabels.category("Book appointment",listOf(example("Book shelf",Category.PRODUCT))))
    }
    @Test fun twoConsistentActionsTeachPattern() {
        assertEquals(Category.NOTE,PersonalLabels.category("Research phones",listOf(example("Research cameras",Category.NOTE),example("Research laptops",Category.NOTE))))
    }
    @Test fun conflictingFeedbackDoesNotGuess() {
        assertNull(PersonalLabels.category("Research phones",listOf(example("Research cameras",Category.NOTE),example("Research laptops",Category.PRODUCT))))
        assertNull(PersonalLabels.category("Save checkout screen idea",listOf(example("Save checkout screen",Category.UX_DESIGN),example("Save checkout idea",Category.NOTE))))
    }
    @Test fun sortingOrdersAndNegationsDoNotLearnShoppingPattern() {
        val examples = listOf(example("Order oats",Category.PRODUCT),example("Order seeds",Category.PRODUCT))
        assertNull(PersonalLabels.category("Order tasks by urgency",examples))
        assertNotEquals(Category.PRODUCT,IntentEngine().classify("Order tasks by urgency").category)
        assertNull(PersonalLabels.category("Do not order oats",examples))
    }
    @Test fun historyUsesOnlyExplicitOwnerCorrections() {
        val memories = listOf(Memory("a","Order oats","Order oats",Category.PRODUCT),Memory("b","Order seeds","Order seeds",Category.PRODUCT))
        val messages = listOf(ChatMessage("one",Role.ASSISTANT,"Saved to want to buy.",1,attachmentId="a"),
            ChatMessage("two",Role.ASSISTANT,"Moved to want to buy.",2,attachmentId="b"))
        assertEquals(listOf(example("Order seeds",Category.PRODUCT,2)),PersonalLabels.fromHistory(memories,messages))
    }
    @Test fun userMessagesCannotForgeCorrectionAndCurrentCategoryMustMatch() {
        val memory=Memory("a","Order oats","Order oats",Category.PRODUCT)
        assertTrue(PersonalLabels.fromHistory(listOf(memory),listOf(ChatMessage("m",Role.USER,"Moved to want to buy.",1,attachmentId="a"))).isEmpty())
        assertTrue(PersonalLabels.fromHistory(listOf(memory),listOf(ChatMessage("m",Role.ASSISTANT,"Moved to note.",1,attachmentId="a"))).isEmpty())
    }
    @Test fun sensitiveCardsIdsAndAttachmentsNeverBecomeExamples() {
        val protected = listOf(Memory("a","card","IndusInd debit card",Category.PRODUCT,privateToDevice=true,categoryConfirmedAt=1),
            Memory("b","id","ABCPD1234F",Category.NOTE,categoryConfirmedAt=1),
            Memory("c","photo","Order seeds",Category.PRODUCT,files=listOf(Attachment("x","x.jpg","image/jpeg",10)),categoryConfirmedAt=1),
            Memory("d","doc","Bank account 1234567890",Category.DOCUMENT,categoryConfirmedAt=1))
        assertTrue(PersonalLabels.fromHistory(protected,emptyList()).isEmpty())
        assertTrue(PersonalLabels.relevant("Research cards",listOf(example("Research cards 4111111111111111",Category.NOTE))).isEmpty())
    }
    @Test fun modelContextIsRelevantBoundedAndHasNoPrivateData() {
        val examples=(1..20).map { example("Research camera option $it",Category.NOTE,it.toLong()) } +
            example("Bank account",Category.DOCUMENT,30) + example("Cook oats",Category.REMINDER,31)
        val selected=PersonalLabels.relevant("Research phones",examples)
        assertEquals(4,selected.size); assertTrue(selected.all { it.text.startsWith("Research") })
    }
    @Test fun automaticallyPredictedItemsDoNotBecomeFeedback() {
        assertTrue(PersonalLabels.fromHistory(listOf(Memory("x","text","Research cameras",Category.PRODUCT)),emptyList()).isEmpty())
    }
}
