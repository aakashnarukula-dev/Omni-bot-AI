package com.gyftalala.omni

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletPrivacyTest {
    @Before fun reset() = TestSupport.reset()

    @Test fun reviewedCardsAndEveryIdStayLocalAfterRenameReloadAndRecategorization() {
        // An unreadable photo ensures privacy does not depend on recognizing its text.
        val image = TestSupport.image("private-original.png", listOf(""))
        val fake = FakeGemini(); val vm = TestSupport.vm(fake.client)
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        TestSupport.await(vm.send("", listOf(image), Category.CARD,
            reviewedCard = CardDetails("IndusInd", "TEST OWNER", "4111111111111111", "09/29", "123")))
        for (kind in IdKind.entries) TestSupport.await(vm.send("", listOf(image), Category.DOCUMENT,
            reviewedIdentity = IdentityDetails(kind, "PRIVATE NUMBER", "TEST OWNER")))
        assertEquals(0, fake.calls)
        val saved = vm.state.value.memories
        assertEquals(5, saved.size)
        assertTrue(saved.all { it.privateToDevice })
        for (memory in saved) {
            TestSupport.await(vm.edit(memory.id, "Personal ${memory.id}", memory.card, memory.identity))
            TestSupport.await(vm.categorize(memory.id, Category.UX_DESIGN))
            TestSupport.await(vm.categorize(memory.id, Category.PRODUCT))
        }
        assertEquals(0, fake.calls)
        OmniStore(TestSupport.context).use { store ->
            assertTrue(store.memories().all { it.staysOnDevice })
            assertTrue(store.memories().all { it.files.size == 1 })
        }
    }

    @Test fun legacyWalletItemsRemainLocalAndChatRetrievesOnlyByLabels() {
        val image = TestSupport.image("legacy-photo.png", listOf(""))
        val fake = FakeGemini(); val vm = TestSupport.vm(fake.client)
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        val card = Memory("legacy", "IndusInd debit card", "", Category.CARD, files = listOf(vm.vault.import(image)),
            ocr = "4111111111111111 SECRET CONTENT", card = CardDetails("IndusInd", "TEST OWNER", "4111111111111111", "09/29", "123"))
        OmniStore(TestSupport.context).use { it.save(card) }
        TestSupport.await(vm.send("get me IndusInd debit card"))
        assertEquals(card.id, vm.state.value.messages.last().attachmentId)
        assertTrue(MemorySearch.find(listOf(card), "SECRET CONTENT").isEmpty())
        assertTrue(MemorySearch.find(listOf(card), "4111111111111111").isEmpty())
        assertEquals(0, fake.calls)
        TestSupport.await(vm.categorize(card.id, Category.UX_DESIGN))
        assertEquals(0, fake.calls)
        assertTrue(vm.state.value.memories.single().privateToDevice)
    }

    @Test fun bareGovernmentNumbersAndTypedCardDetailsNeverReachGemini() {
        val fake = FakeGemini(); val vm = TestSupport.vm(fake.client)
        TestSupport.await(vm.settings("synthetic-key", "gemini-3.8-flash", true))
        for (text in listOf("2345 6789 0123", "ABCDE1234F", "DL-14 2011 0012345", "4111 1111 1111 1111 CVV 123")) {
            TestSupport.await(vm.send(text))
        }
        assertEquals(0, fake.calls)
        assertEquals(4, vm.state.value.memories.size)
        assertTrue(vm.state.value.memories.all { it.privateToDevice })
    }
}
