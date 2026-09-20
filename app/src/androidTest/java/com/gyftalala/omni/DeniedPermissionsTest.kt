package com.gyftalala.omni

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run separately after revoking permissions outside the test process: revocation kills the app. */
@RunWith(AndroidJUnit4::class)
class DeniedPermissionsTest {
    @Test fun deniedPermissionsKeepReminderAndExplainFallback() {
        TestSupport.requireIsolated()
        assumeTrue(InstrumentationRegistry.getArguments().getString("permissionsDenied") == "true")
        val vm = TestSupport.vm()
        TestSupport.await(vm.send("Do puja in 20 minutes"))
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.reminders.isNotEmpty())
        assertTrue(vm.state.value.messages.last().text.contains("may delay"))
        assertTrue(vm.state.value.messages.last().text.contains("Notifications are off"))
    }
}
