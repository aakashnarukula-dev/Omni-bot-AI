package com.gyftalala.omni.ai

import com.gyftalala.omni.data.*
import org.junit.Assert.*
import org.junit.Test

class ChatAttachmentTest {
    private val front = Attachment("front", "front.jpg", "image/jpeg", 100)
    private val back = Attachment("back", "back.jpg", "image/jpeg", 100)
    private val memory = Memory("card", "Card", "", Category.CARD, files = listOf(front, back))

    @Test fun messageKeepsOnlyItsOwnAttachmentsAfterAnotherSideIsAdded() {
        val message = ChatMessage("one", Role.USER, "", 1, attachmentId = memory.id, fileIds = listOf(front.id))
        assertEquals(listOf(front), message.files(memory))
        assertEquals(listOf(back), message.copy(fileIds = listOf(back.id)).files(memory))
        assertEquals(emptyList<Attachment>(), message.copy(fileIds = emptyList()).files(memory))
    }

    @Test fun legacyFilenameBecomesPhotoAndCaptionSurvives() {
        val message = ChatMessage("one", Role.USER, front.name, 1, attachmentId = memory.id)
        assertEquals(listOf(front), message.files(memory))
        assertEquals("", message.caption(message.files(memory)))
        val caption = message.copy(text = "Remember this design")
        assertEquals("Remember this design", caption.caption(caption.files(memory)))
        assertEquals(front.name, message.copy(fileIds = listOf(front.id)).caption(listOf(front)))
    }

    @Test fun missingFilesAndAssistantMessagesCannotShowUnrelatedPhotos() {
        val message = ChatMessage("one", Role.USER, "", 1, fileIds = listOf("deleted"))
        assertTrue(message.files(memory).isEmpty())
        assertTrue(message.files(null).isEmpty())
        assertTrue(message.copy(role = Role.ASSISTANT, fileIds = listOf(front.id)).files(memory).isEmpty())
    }
}
