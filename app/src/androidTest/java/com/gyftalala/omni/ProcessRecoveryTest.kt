package com.gyftalala.omni

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gyftalala.omni.data.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The runner launches prepare and verify separately, with force-stop between them. */
@RunWith(AndroidJUnit4::class)
class ProcessRecoveryTest {
    @Test fun prepare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("recovery") == "true")
        TestSupport.reset()
        val vm = TestSupport.vm()
        TestSupport.await(vm.send("Call Sujatha madam in 20 minutes"))
        TestSupport.await(vm.schedule(vm.state.value.reminders.single().id, System.currentTimeMillis() + 15000))
        OmniStore(TestSupport.context).use { it.save(Memory("interrupted-import", "Interrupted file", "Original text", Category.UNKNOWN, status = "Sorting")) }
        assertEquals(1, vm.state.value.reminders.size)
    }

    @Test fun verifyAfterProcessStop() {
        TestSupport.requireIsolated()
        assumeTrue(InstrumentationRegistry.getArguments().getString("recovery") == "true")
        OmniStore(TestSupport.context).use { assertFalse("Alarm must still be pending before recovery", it.reminders().single().delivered) }
        val vm = TestSupport.vm()
        assertEquals(1, vm.state.value.reminders.size)
        assertEquals(2, vm.state.value.memories.size)
        assertEquals("Needs review", vm.state.value.memories.first { it.id == "interrupted-import" }.status)
        TestSupport.await(vm.send("find Sujatha"))
        assertEquals(vm.state.value.reminders.single().id, vm.state.value.messages.last().attachmentId)
        TestSupport.waitUntil(20000) { OmniStore(TestSupport.context).use { it.reminders().single().delivered } }
        OmniStore(TestSupport.context).use { store ->
            assertEquals(1, store.messages().count { it.id.startsWith("delivery:") })
        }
    }
}
