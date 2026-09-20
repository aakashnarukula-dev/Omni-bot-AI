package com.gyftalala.omni

import com.gyftalala.omni.ai.*
import com.gyftalala.omni.backup.*
import com.gyftalala.omni.data.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream

class LabelLearningIntegrationTest {
    @Before fun reset() = TestSupport.reset()
    private fun enableAi() = OmniStore(TestSupport.context).use { it.saveSettings(JSONObject().put("key","SYNTHETIC_KEY").put("cloud",true)) }
    @Test fun oatsAndSeedsDoNotAskForReminderOrCallAi() {
        enableAi(); val ai=FakeGemini("reminder"); val vm=TestSupport.vm(ai.client)
        TestSupport.await(vm.send("Order Oats")); TestSupport.await(vm.send("Order seeds"))
        assertEquals(0,ai.calls); assertEquals(2,vm.state.value.memories.size)
        assertTrue(vm.state.value.memories.all { it.category==Category.PRODUCT && it.question==null })
        assertTrue(vm.state.value.reminders.isEmpty())
    }
    @Test fun correctionPersistsAndOverridesPredictionAfterRestart() {
        enableAi(); val ai=FakeGemini("product"); val vm=TestSupport.vm(ai.client)
        TestSupport.await(vm.send("Research headphones"))
        val memory=vm.state.value.memories.single(); assertEquals(Category.PRODUCT,memory.category)
        TestSupport.await(vm.categorize(memory.id,Category.NOTE))
        assertTrue(vm.state.value.memories.single().categoryConfirmedAt > 0)
        assertTrue(vm.state.value.messages.last().text.contains("similar messages"))
        val restarted=TestSupport.vm(ai.client)
        TestSupport.await(restarted.send("Research headphones"))
        assertTrue(restarted.state.value.memories.all { it.category==Category.NOTE }); assertEquals(0,ai.calls)
    }
    @Test fun importsExistingManualCorrectionsFromChat() {
        OmniStore(TestSupport.context).use { store ->
            store.save(Memory("old","Research headphones","Research headphones",Category.NOTE))
            store.save(ChatMessage("correction",Role.ASSISTANT,"Moved to note.",10,attachmentId="old"))
        }
        val vm=TestSupport.vm(); TestSupport.await(vm.send("Research headphones"))
        assertTrue(vm.state.value.memories.all { it.category==Category.NOTE })
    }
    @Test fun twoConsistentCorrectionsTeachRelatedActionButTimedReminderWins() {
        val vm=TestSupport.vm()
        for (text in listOf("Research headphones","Research laptop")) {
            TestSupport.await(vm.send(text))
            TestSupport.await(vm.categorize(vm.state.value.memories.first { it.text==text }.id,Category.NOTE))
        }
        TestSupport.await(vm.send("Research keyboard"))
        assertEquals(Category.NOTE,vm.state.value.memories.first { it.text=="Research keyboard" }.category)
        TestSupport.await(vm.send("Remind me to research keyboard tomorrow"))
        val timed=vm.state.value.memories.first { it.text.startsWith("Remind me") }
        assertEquals(Category.REMINDER,timed.category); assertNotNull(timed.question)
    }
    @Test fun relevantCorrectionGuidesGeminiWithoutPrivateHistory() {
        OmniStore(TestSupport.context).use { store ->
            store.save(Memory("private","Private ID","Research passport 1234567890",Category.NOTE,privateToDevice=true,categoryConfirmedAt=100))
            store.save(Memory("safe","Research headphones","Research headphones",Category.NOTE,categoryConfirmedAt=50))
        }
        enableAi(); val ai=FakeGemini("note"); val vm=TestSupport.vm(ai.client)
        TestSupport.await(vm.send("Research cameras"))
        assertEquals(1,ai.calls)
        val body=JSONObject(ai.lastConnection!!.request.toString())
        val input=body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(input.contains("Research headphones")); assertTrue(input.contains("\"category\":\"note\""))
        assertFalse(input.contains("passport")); assertFalse(input.contains("1234567890"))
    }
    @Test fun correctionRemovesPreviouslyScheduledReminder() {
        val vm=TestSupport.vm(); TestSupport.await(vm.send("Remember to order seeds"))
        val id=vm.state.value.memories.single().id
        TestSupport.await(vm.schedule(id,System.currentTimeMillis()+3600000)); assertEquals(1,vm.state.value.reminders.size)
        TestSupport.await(vm.categorize(id,Category.PRODUCT))
        assertTrue(vm.state.value.reminders.isEmpty()); assertNull(vm.state.value.memories.single().question)
    }
    @Test fun accountBackupPreservesConfirmedLabels() {
        val key=ByteArray(32) { it.toByte() }; val owner=AccountBackupCipher.ownerTag("demo","owner")
        val bytes=ByteArrayOutputStream()
        OmniStore(TestSupport.context).use { store ->
            store.save(Memory("learned","Research headphones","Research headphones",Category.NOTE,categoryConfirmedAt=12345))
            VaultBackup(TestSupport.context,store).writeAccount(bytes,key,owner)
            val payload=store.readableDatabase.rawQuery("SELECT payload FROM records WHERE id='learned'",null).use { it.moveToFirst(); it.getBlob(0) }
            assertFalse(String(payload).contains("Research headphones"))
        }
        TestSupport.reset()
        OmniStore(TestSupport.context).use { store ->
            val service=VaultBackup(TestSupport.context,store)
            val staged=service.prepareAccount(bytes.toByteArray().inputStream(),key,owner)
            service.commit(staged)
            assertEquals(12345L,store.memories().single().categoryConfirmedAt)
            assertEquals(Category.NOTE,PersonalLabels.category("Research headphones",PersonalLabels.fromHistory(store.memories(),store.messages())))
        }
    }
    @Test fun laterCorrectionWinsAndDeletingSourceRemovesPreference() {
        val vm=TestSupport.vm(); TestSupport.await(vm.send("Research headphones"))
        val id=vm.state.value.memories.single().id
        TestSupport.await(vm.categorize(id,Category.NOTE)); TestSupport.await(vm.categorize(id,Category.PRODUCT))
        OmniStore(TestSupport.context).use { store ->
            assertEquals(Category.PRODUCT,PersonalLabels.category("Research headphones",PersonalLabels.fromHistory(store.memories(),store.messages())))
            store.delete(id)
            assertTrue(PersonalLabels.fromHistory(store.memories(),store.messages()).isEmpty())
        }
    }
    @Test fun voiceSuggestionsUseSameLearningAndShoppingRules() {
        OmniStore(TestSupport.context).use { store -> store.save(Memory("learned","Save checkout screen","Save checkout screen",Category.UX_DESIGN,categoryConfirmedAt=1)) }
        val vm=TestSupport.vm()
        val items=kotlinx.coroutines.runBlocking { vm.suggestVoice("Order oats and order seeds and save checkout screen") }
        assertEquals(listOf(Category.PRODUCT,Category.PRODUCT,Category.UX_DESIGN),items.map { it.category })
    }
}
